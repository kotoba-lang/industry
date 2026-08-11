#!/usr/bin/env nbb
;; probe.cljs — fleet-ci node capability probe (ADR-2607178000 の Phase B)。
;;
;; murakumo fleet の各 mac mini に SSH して CI gate を実行できる toolchain
;; （JVM/clojure・node/npx・Zig）と余力（cores・空きディスク）を実測し、
;; scripts/fleet-ci/nodes.edn を生成する。tick.cljs はこの registry だけを見て
;; gate を割り当てるので、ノードの増減・provision は「probe を回し直す」だけで
;; 反映される（ノード名を runner にハードコードしない — ADR の
;; zebulun/asher ハードコードを解消するのがこの層の目的）。
;;
;; 使い方:
;;   nbb scripts/fleet-ci/probe.cljs                       ;; 既定のホスト一覧を probe
;;   nbb scripts/fleet-ci/probe.cljs --hosts judah,levi     ;; 指定ホストのみ
;;   nbb scripts/fleet-ci/probe.cljs --out /tmp/nodes.edn   ;; 出力先を変える
;;   nbb scripts/fleet-ci/probe.cljs --dry-run              ;; 標準出力に出すだけ
;;
;; 到達不能なホストは :reachable? false として記録する（消さない — 消すと
;; 「そもそも fleet に居るのか」が分からなくなる）。
(ns fleet-ci.probe
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [promesa.core :as p]))

(def default-hosts
  ["zebulun" "asher" "judah" "levi" "naphtali" "simeon"
   "dan" "joseph" "issachar" "benjamin"])

(defn parse-args [argv]
  (loop [opts {} [a & more] argv]
    (cond
      (nil? a) opts
      (str/starts-with? a "--")
      (let [k (keyword (subs a 2))]
        (if (or (nil? (first more)) (str/starts-with? (first more) "--"))
          (recur (assoc opts k true) more)
          (recur (assoc opts k (first more)) (rest more))))
      :else (recur opts more))))

(def opts (parse-args *command-line-args*))

(def here (path/dirname *file*))   ;; nbb: js/__filename は nil。*file* が正

;; 1 往復で全部取る。**スクリプトは stdin で `bash -s` に流す** —
;; `ssh host bash -lc "<script>"` 形式はリモート側 login shell が先に展開するため
;; ネストした quote / $( ) が壊れる（実測: 最初の実装は clojure/java の検出が
;; 全ノードで空になり caps が全部空だった）。stdin なら quote 問題が原理的に起きない。
(def probe-script
  (str/join
   "\n"
   ["export PATH=/opt/homebrew/bin:/usr/local/bin:$PATH"
    "echo os=$(uname -s)"
    "echo cores=$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null)"
    "echo freegb=$(df -k / | awk 'NR==2{printf \"%d\", $4/1048576}')"
    ;; homebrew の openjdk を優先（/usr/bin/java は macOS の未設定スタブ）
    "for j in /opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk@26 /opt/homebrew/opt/openjdk@21 /usr/lib/jvm/default-java; do [ -x \"$j/bin/java\" ] && echo javahome=$j && break; done"
    ;; **loopback TCP が通るか**。ping(ICMP) は通るのに TCP connect が
    ;; EADDRNOTAVAIL になるノードが実在する（実測 2026-07-26 zebulun: python の
    ;; 127.0.0.1 listener が LISTEN しているのに同ホストの curl / nc が繋がらない。
    ;; app firewall も pf も無効、lo0 に 127.0.0.1 はある）。ローカルに server を
    ;; 立てるテストは軒並み落ちるので、これを cap として持たないと「ノードが壊れて
    ;; いる」を「テストが失敗した」と読み違える — bonsai の gate が実際そうなった。
    "python3 -c 'import socket,sys\ns=socket.socket();s.bind((\"127.0.0.1\",0));s.listen(1)\nc=socket.socket()\ntry:\n c.settimeout(3);c.connect(s.getsockname());print(\"loopback=yes\")\nexcept Exception:\n print(\"loopback=no\")' 2>/dev/null || echo loopback=no"
    "echo clojure=$(command -v clojure)"
    "echo node=$(command -v node)"
    "echo nodev=$(node -v 2>/dev/null)"
    "echo npx=$(command -v npx)"
    "echo zig=$(command -v zig)"
    "echo zigv=$(zig version 2>/dev/null)"
    "echo curl=$(command -v curl)"
    "echo tar=$(command -v tar)"
    "echo git=$(command -v git)"]))

(defn ssh-probe [host]
  (p/create
   (fn [resolve _]
     (let [ps (cp/spawn "ssh" #js ["-o" "BatchMode=yes" "-o" "ConnectTimeout=8"
                                   host "bash" "-s"]
                        #js {:stdio #js ["pipe" "pipe" "pipe"]})
           _ (doto (.-stdin ps) (.write probe-script) (.end))
           out (atom "") err (atom "") done (atom false)
           finish (fn [m] (when-not @done (reset! done true) (resolve m)))
           timer (js/setTimeout #(do (try (.kill ps "SIGKILL") (catch :default _))
                                     (finish {:host host :reachable? false :detail "timeout"}))
                                45000)]
       (.on (.-stdout ps) "data" #(swap! out str %))
       (.on (.-stderr ps) "data" #(swap! err str %))
       (.on ps "close"
            (fn [code]
              (js/clearTimeout timer)
              (if (zero? code)
                (let [kv (into {} (for [line (str/split-lines (str @out))
                                        :let [[k v] (str/split (str/trim line) #"=" 2)]
                                        :when (seq k)]
                                    [(keyword k) (str/trim (or v ""))]))]
                  (finish (assoc kv :host host :reachable? true)))
                (finish {:host host :reachable? false
                         :detail (str "ssh exit " code " " (str/trim (str @err)))}))))
       (.on ps "error" (fn [e] (js/clearTimeout timer)
                         (finish {:host host :reachable? false :detail (str e)})))))))

(defn- num [s] (let [n (js/parseInt (str s) 10)] (if (js/isNaN n) 0 n)))

(defn classify
  "実測値 → gate 割り当てに使う capability。ディスク余力を cap の条件に含めるのは
  clojure の maven cache / tarball 展開が数 GB 食うため — 空き 1–2GB のノードに
  JVM gate を投げると途中で落ちて false fail になる（naphtali/issachar が実際に
  この状態）。"
  [{:keys [reachable? javahome clojure npx zig curl tar] :as n}]
  (if-not reachable?
    (assoc n :caps #{} :max-parallel 0)
    (let [free (num (:freegb n))
          cores (num (:cores n))
          ;; loopback が無いノードは gate を回せない。JVM/node のどちらの
          ;; テストでもローカルに server を立てるものは普通にあるので、cap
          ;; ごとではなく base 条件に入れる。
          loopback? (= "yes" (:loopback n))
          base? (and (seq curl) (seq tar) loopback?)
          jvm? (and base? (seq javahome) (seq clojure) (>= free 8))
          node? (and base? (seq npx) (>= free 5))
          ;; Zig gates are nbb-script gates, so the runner itself still needs
          ;; npx/nbb in addition to the compiler it will invoke.
          zig? (and base? (seq npx) (seq zig) (>= free 5))]
      (assoc n
             :cores cores
             :free-gb free
             :caps (cond-> #{} jvm? (conj :jvm) node? (conj :node) zig? (conj :zig))
             ;; 1 gate ≒ 1 JVM + maven。10 コアで 2 本までに抑える（他の
             ;; fleet 用途 — 推論・マイニング — と同居している前提）。
             :max-parallel (max 1 (min 2 (quot cores 4)))))))

(defn edn-node [n]
  (let [{:keys [host reachable? os cores free-gb javahome clojure node nodev npx zig zigv caps max-parallel detail loopback]} n]
    (str "  {:host " (pr-str host)
         " :reachable? " (pr-str (boolean reachable?))
         (when os (str " :os " (pr-str os)))
         (when (and reachable? cores) (str " :cores " cores))
         (when (and reachable? free-gb) (str " :free-gb " free-gb))
         (when (seq javahome) (str "\n   :java-home " (pr-str javahome)))
         (when (seq clojure) (str " :clojure " (pr-str clojure)))
         (when (seq node) (str "\n   :node " (pr-str node)))
         (when (seq nodev) (str " :node-version " (pr-str nodev)))
         (when (seq npx) (str " :npx " (pr-str npx)))
         (when (seq zig) (str "\n   :zig " (pr-str zig)))
         (when (seq zigv) (str " :zig-version " (pr-str zigv)))
         (when (and reachable? (= "no" loopback))
           (str "\n   :loopback? false"))
         (when reachable? (str "\n   :caps " (pr-str (or caps #{})) " :max-parallel " (or max-parallel 0)))
         (when detail (str "\n   :detail " (pr-str detail)))
         "}")))

(defn seq-limited
  "同時実行数を n に絞って f を順に走らせる（結果順は items 順を保つ）。
  実測: tailnet 越しに 10 本同時 SSH を張ると handshake が詰まって全部
  timeout した（並列度を上げすぎると『到達不能』と誤検出する）。"
  [n items f]
  (let [results (atom (vec (repeat (count items) nil)))
        idx (atom 0)
        worker (fn worker []
                 (let [i @idx]
                   (if (>= i (count items))
                     (p/resolved nil)
                     (do (swap! idx inc)
                         (-> (f (nth items i))
                             (p/then (fn [r] (swap! results assoc i r) (worker))))))))]
    (-> (p/all (repeatedly (min n (max 1 (count items))) worker))
        (p/then (fn [_] @results)))))

(defn -main []
  (let [hosts (if (:hosts opts)
                (->> (str/split (str (:hosts opts)) #",") (map str/trim) (remove str/blank?))
                default-hosts)
        outf (or (:out opts) (path/join here "nodes.edn"))]
    (println "probing" (count hosts) "hosts …")
    (-> (seq-limited 4 (vec hosts) ssh-probe)
        (p/then
         (fn [raw]
           (let [nodes (mapv classify raw)
                 body (str ";; nodes.edn — generated by scripts/fleet-ci/probe.cljs. DO NOT EDIT BY HAND.\n"
                           ";; 再生成: nbb scripts/fleet-ci/probe.cljs\n"
                           "{:probed-at " (pr-str (.toISOString (js/Date.))) "\n"
                           " :nodes\n [\n"
                           (str/join "\n" (map edn-node nodes))
                           "]}\n")]
             (doseq [n nodes]
               ;; cljs.core has no `format` under nbb — pad by hand.
               (println (str "  " (subs (str (:host n) "            ") 0 12)
                             (if (:reachable? n)
                               (str (pr-str (:caps n)) " cores=" (:cores n)
                                    " free=" (:free-gb n) "GB par=" (:max-parallel n))
                               (str "UNREACHABLE " (:detail n))))))
             (if (:dry-run opts)
               (println body)
               (do (fs/writeFileSync outf body)
                   (println "wrote" outf)))))))))

(-main)
