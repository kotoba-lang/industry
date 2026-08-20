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
    ;; Kotoba's native CLI, for gates that compile `.kotoba` to Wasm and hold
    ;; it to the Clojure implementation (sigv4's percent-encoder is the first).
    ;; Probed rather than assumed: it is a Homebrew tap and is on some nodes
    ;; and not others, and a parity gate that cannot build its module has to
    ;; fail rather than land somewhere it will always be red.
    ;; `kotoba wasm` with no subcommand prints the command list and exits
    ;; non-zero, so presence is `command -v`, not a version flag -- this CLI
    ;; has no `--version` (it answers `:command/unknown`).
    "echo kotoba=$(command -v kotoba)"
    ;; The Wasm toolchain amu's JVM suite shells out to. Probed for the same
    ;; reason as `kotoba` above -- so a gate can say it needs them instead of
    ;; being placed somewhere it will always be red -- and because the reason
    ;; recorded in gates.edn for amu having no `clojure -M:test` gate was
    ;; measurably wrong.
    ;;
    ;; Measured 2026-08-20 on benjamin: amu's full suite RUNS on a fleet node in
    ;; 74s and resolves every git dep (`clojure -Spath -M:test` exits 0, cloning
    ;; provider / kototama-native / kgraph), so the "no egress on the nodes"
    ;; explanation does not hold. What it produced was 87 errors, 84 of them
    ;; `Cannot run program "wasm-tools"` (82) and `"wasmtime"` (2).
    ;;
    ;; No node has either today -- all nine were checked -- so this cap is
    ;; expected to be empty. That is the point: the gap becomes a field in
    ;; nodes.edn instead of folklore in a comment, and the day somebody
    ;; `brew install`s them it appears without anyone editing this file.
    ;; `~/.gftd/wasm-pin/bin` comes FIRST, and that ordering is the whole
    ;; mechanism rather than a convenience.
    ;;
    ;; amu pins its Wasm toolchain (`component-model-v1.edn`,
    ;; `{:wasm-tools \"1.243.0\" :wac-cli \"0.10.1\"}`) and refuses any other
    ;; version outright -- measured 2026-08-20, Homebrew's 1.257.1 produced 76
    ;; `wasm-tools version is not pinned` errors. Homebrew has no way to install
    ;; an old version and no `wac` formula at all, so the pinned pair is
    ;; installed from the upstream release binaries into a prefix of its own.
    ;;
    ;; Probing PATH first would report Homebrew's newer copy and the cap would
    ;; be earned by a node that still cannot run the suite -- exactly the defect
    ;; this cap was corrected for earlier the same day.
    ;;
    ;; Measured with the pinned pair on that prefix: amu's full suite runs on
    ;; benjamin in 74s, 1,099 tests, 8,302 assertions, **0 failures 0 errors**,
    ;; exit 0. Gates that want this cap must put the prefix on PATH the same
    ;; way; the version recorded below is the one this prefix answers with.
    "echo wasmpin=$HOME/.gftd/wasm-pin/bin"
    "echo wasmtools=$(PATH=$HOME/.gftd/wasm-pin/bin:$PATH command -v wasm-tools)"
    "echo wasmtoolsv=$(PATH=$HOME/.gftd/wasm-pin/bin:$PATH wasm-tools --version 2>/dev/null | awk '{print $2}')"
    "echo wasmtime=$(command -v wasmtime)"
    "echo wasmtimev=$(wasmtime --version 2>/dev/null | awk '{print $2}')"
    ;; `wac` is the third tool amu's component tests reach for, and the reason
    ;; the cap is a conjunction of three rather than two. It is NOT in Homebrew
    ;; ("No available formula with the name \"wac\"", measured 2026-08-20), so
    ;; unlike the other two it cannot be installed by the obvious route.
    ;; Rosetta 2. amu's suite emits an x86_64 native artifact and EXECUTES it
    ;; (`kotoba-isa-probe-x86_64.bin`), so on Apple Silicon the toolchain being
    ;; present is not enough -- without Rosetta the run dies with
    ;; `Bad CPU type in executable`.
    ;;
    ;; Measured 2026-08-20 by installing the pinned toolchain on four more nodes
    ;; and running the suite on each rather than trusting the tools:
    ;;
    ;;   benjamin rosetta=yes  0 errors      judah rosetta=yes  0 errors
    ;;   levi     rosetta=no   16 errors     simeon/joseph rosetta=no
    ;;
    ;; A perfect split. Had the cap been granted on tool presence alone, three
    ;; nodes would have earned it and then gone red about themselves -- the
    ;; defect this cap exists to prevent, reached for the third time from a new
    ;; direction.
    "echo rosetta=$(arch -x86_64 /usr/bin/true 2>/dev/null && echo yes || echo no)"
    "echo wac=$(PATH=$HOME/.gftd/wasm-pin/bin:$PATH command -v wac)"
    "echo wacv=$(PATH=$HOME/.gftd/wasm-pin/bin:$PATH wac --version 2>/dev/null | awk '{print $2}')"
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

(def operator-hosts
  "gate rotation から**明示的に外す**ホスト。

  fleet の不変条件 3 は「ノードに credential を置かない」である。常駐 writer
  （hayari tick 等）は git push のために credential を要するので、それを置く
  マシンは gate を実行してはならない —— さもないと *repo から送られてきた
  gate コードを実行するマシンが credential を持つ* ことになり、条文は守れても
  趣旨で負ける。

  外れたホストは gate 容量を 1 台分失う。それが常駐 writer を fleet に置く実費。

  ⚠ ここに足すだけでは足りない。**そのホストで実際に gate が動いていないこと**を
  確認してから credential を置くこと（tick.cljs の slots は :caps で絞るので、
  probe を回し直して nodes.edn が更新されるまで古い caps が使われる）。

  この集合は ADR-2608111721 の `:slot/attested`（書き込み鍵を持つ常駐スロット）の
  実装である。attested なホストは 1 台に固定し、台数を増やさない。

  **asher（2026-08-11）**: hayari の収集 tick が Radicle 経路で移設され、
  `~/.radicle/keys/radicle` と `com.gftd.hayari-collect` LaunchDaemon を持つ
  （ADR-2608110300 の決定 B）。一度は空に戻してあった —— deploy key 経路が
  org ポリシーで止まっていた間、完了できない移設のために gate 容量を人質に
  取らないため。Radicle 経路で実際に attested になったので埋めた。

  ⚠ **戻すときは nodes.edn を再生成するまで効かない、が逆向きには危険側に効く。**
  ここを空にしただけでは古い caps が残るので外れて見えるが、次の probe で
  そのホストが rotation に戻る。source が空で生成物が外している状態は、
  幸運であって設計ではない（2026-08-11 に実際にこの状態だった）。"
  #{"asher"})

(defn classify
  "実測値 → gate 割り当てに使う capability。ディスク余力を cap の条件に含めるのは
  clojure の maven cache / tarball 展開が数 GB 食うため — 空き 1–2GB のノードに
  JVM gate を投げると途中で落ちて false fail になる（naphtali/issachar が実際に
  この状態）。"
  [{:keys [reachable? javahome clojure npx zig kotoba wasmtools wasmtime wac rosetta curl tar host] :as n}]
  (if (or (not reachable?) (contains? operator-hosts host))
    (assoc n :caps #{} :max-parallel 0
           :role (if (contains? operator-hosts host) :operator :unreachable))
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
          zig? (and base? (seq npx) (seq zig) (>= free 5))
          ;; Same shape as zig: the gate is an nbb-script that shells out to
          ;; the compiler, so it needs npx as well as `kotoba`.
          kotoba? (and base? (seq npx) (seq kotoba) (>= free 5))
          ;; All THREE, not the two this started as. Corrected 2026-08-20 after
          ;; installing the first two on benjamin and running amu's suite there:
          ;; the 82 `Cannot run program "wasm-tools"` errors became 76
          ;; `wasm-tools version is not pinned`, because amu's language contract
          ;; (`kotoba/lang/component-model-v1.edn`, `[:spec-baseline :wasi
          ;; :toolchain]`) requires `{:wasm-tools "1.243.0" :wac-cli "0.10.1"}`
          ;; and Homebrew ships only latest -- 1.257.1 that day.
          ;;
          ;; So the cap as first written was the very defect it was meant to
          ;; prevent: benjamin earned `:wasm-tools` while being unable to run the
          ;; suite, and any gate declaring that cap would have landed there and
          ;; gone red about the node rather than the repo (ADR-2608198600).
          ;;
          ;; Requiring `wac` too makes the cap honest, and today that means NO
          ;; node earns it -- `wac` is not in Homebrew at all. That is the
          ;; correct answer: nothing can run this suite yet.
          ;;
          ;; Presence is still not qualification. The pinned VERSIONS are amu's
          ;; to check (the `zig` precedent: probe records `zigv`, the gate
          ;; fail-closes on an unknown one), which is why the versions are
          ;; recorded below rather than compared here -- this file must not
          ;; carry a copy of another repo's pin.
          wasm-tools? (and jvm? (seq wasmtools) (seq wasmtime) (seq wac)
                           (= "yes" rosetta))]
      (assoc n
             :cores cores
             :free-gb free
             :caps (cond-> #{} jvm? (conj :jvm) node? (conj :node) zig? (conj :zig)
                           kotoba? (conj :kotoba) wasm-tools? (conj :wasm-tools))
             ;; 1 gate ≒ 1 JVM + maven。10 コアで 2 本までに抑える（他の
             ;; fleet 用途 — 推論・マイニング — と同居している前提）。
             :max-parallel (max 1 (min 2 (quot cores 4)))))))

;; `classify` is the only place a measurement becomes a placement decision, and
;; it has never had a test. The `:wasm-tools` cap cannot be checked against the
;; fleet -- no node has the binaries -- so the alternative to testing the
;; function directly is landing a cap that has never once been seen to appear.
(defn self-test! []
  (let [fails (atom 0)
        check (fn [ok? label] (when-not ok? (swap! fails inc) (println "FAIL" label)))
        base {:host "t" :reachable? true :cores 10 :freegb "40"
              :curl "/usr/bin/curl" :tar "/usr/bin/tar" :loopback "yes"
              :javahome "/jdk" :clojure "/bin/clojure" :npx "/bin/npx"}
        full {:wasmtools "/bin/wasm-tools" :wasmtime "/bin/wasmtime"
              :wac "/bin/wac" :rosetta "yes"}
        caps-of (fn [extra] (:caps (classify (merge base extra))))]
    (check (= #{:jvm :node} (caps-of {}))
           "a plain jvm+node machine carries neither zig nor wasm-tools")
    (check (contains? (caps-of full) :wasm-tools)
           "all three tools AND Rosetta -> :wasm-tools appears")
    (check (not (contains? (caps-of (dissoc full :rosetta)) :wasm-tools))
           "the three tools WITHOUT Rosetta is not enough. Measured: levi had
            exactly this and produced 16 x `Bad CPU type in executable`, because
            the suite executes an x86_64 artifact it emitted")
    (check (not (contains? (caps-of (assoc full :rosetta "no")) :wasm-tools))
           "and an explicit rosetta=no is refused, not merely a missing key")
    (check (not (contains? (caps-of (dissoc full :wac)) :wasm-tools))
           "wasm-tools + wasmtime WITHOUT wac is not enough. This is the case
            that was wrong when the cap first landed: benjamin had exactly this
            pair, earned the cap, and still could not run the suite")
    (check (not (contains? (caps-of (dissoc full :wasmtime)) :wasm-tools))
           "nor wasm-tools + wac without wasmtime")
    (check (not (contains? (caps-of (dissoc full :wasmtools)) :wasm-tools))
           "nor wasmtime + wac without wasm-tools")
    (check (not (contains? (caps-of (assoc full :javahome "" :clojure "")) :wasm-tools))
           "without a JVM the cap is withheld: there is no gate that wants these
            without one, and a cap nothing can use is a promise that misleads")
    (check (empty? (:caps (classify (assoc base :reachable? false))))
           "an unreachable node carries no caps at all")
    (if (zero? @fails)
      (println "probe: self-test OK (10 cases)")
      (do (println "probe: self-test FAILED" @fails) (js/process.exit 1)))))

(defn edn-node [n]
  (let [{:keys [host reachable? os cores free-gb javahome clojure node nodev npx zig zigv kotoba wasmtools wasmtoolsv wasmtime wasmtimev wac wacv rosetta caps max-parallel detail loopback role]} n]
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
         (when (seq kotoba) (str "\n   :kotoba " (pr-str kotoba)))
         (when (seq zigv) (str " :zig-version " (pr-str zigv)))
         ;; Written even though no node carries them today. `:caps` alone cannot
         ;; distinguish "the tool is absent" from "the probe never looked", and
         ;; the whole reason these are probed is that the previous answer to
         ;; "why is there no JVM gate for amu" was a guess nobody could check.
         (when (seq wasmtools) (str "\n   :wasm-tools " (pr-str wasmtools)))
         (when (seq wasmtoolsv) (str " :wasm-tools-version " (pr-str wasmtoolsv)))
         (when (seq wasmtime) (str "\n   :wasmtime " (pr-str wasmtime)))
         (when (seq wasmtimev) (str " :wasmtime-version " (pr-str wasmtimev)))
         (when (seq wac) (str "\n   :wac " (pr-str wac)))
         (when (seq wacv) (str " :wac-version " (pr-str wacv)))
         (when (and reachable? (= "no" rosetta)) (str "\n   :rosetta? false"))
         (when (and reachable? (= "no" loopback))
           (str "\n   :loopback? false"))
         (when reachable? (str "\n   :caps " (pr-str (or caps #{})) " :max-parallel " (or max-parallel 0)))
         ;; caps 空だけでは「意図的に外した」と「壊れて到達不可」が区別できない。
         ;; 外した理由を書かないと、後から読む人には故障に見える。
         (when role (str " :role " (pr-str role)))
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
  (when (:self-test opts) (self-test!) (js/process.exit 0))
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
