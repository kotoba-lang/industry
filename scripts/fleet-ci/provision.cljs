#!/usr/bin/env nbb
;; provision.cljs — fleet-ci gate 実行に必要な toolchain を murakumo ノードに入れる。
;;
;; ADR-2607178000 時点では JVM gate を実行できるのが zebulun 1 台だけで、
;; 14 repo の gate が事実上直列だった。CI として使うにはノード側 toolchain を
;; 増やす必要がある — その部分だけを冪等に行う（murakumo 本体の
;; `bb murakumo provision`（kotoba-server mesh node の設置）とは別物・非干渉。
;; ここで入れるのは homebrew の clojure/openjdk/node/zig だけで、常駐 agent は
;; 一切増やさない = murakumo の「ノードには kotoba バイナリ2本以外置かない」
;; 方針と衝突しない）。
;;
;; 使い方:
;;   nbb scripts/fleet-ci/provision.cljs --hosts judah,simeon --need jvm
;;   nbb scripts/fleet-ci/provision.cljs --hosts benjamin --need jvm,node
;;   nbb scripts/fleet-ci/provision.cljs --hosts judah,benjamin --need zig
;;   nbb scripts/fleet-ci/provision.cljs --from-nodes --need jvm   ;; nodes.edn の
;;       reachable かつ空き 8GB 以上で :jvm を持たないノードを対象にする
;;   nbb scripts/fleet-ci/provision.cljs ... --dry-run
;;
;; 終わったら必ず `nbb scripts/fleet-ci/probe.cljs` を回して nodes.edn を更新する
;; （tick.cljs は nodes.edn しか見ない）。
(ns fleet-ci.provision
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [promesa.core :as p]))

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

(defn ssh-script
  "リモートで bash script を実行（stdin 経由 — quote 崩れを構造的に避ける）。
  -> promise {:exit n :out s}"
  [host script timeout-ms]
  (p/create
   (fn [resolve _]
     (let [ps (cp/spawn "ssh" #js ["-o" "BatchMode=yes" "-o" "ConnectTimeout=10" host "bash" "-s"]
                        #js {:stdio #js ["pipe" "pipe" "pipe"]})
           out (atom "") done (atom false)
           finish (fn [m] (when-not @done (reset! done true) (resolve m)))
           timer (js/setTimeout #(do (try (.kill ps "SIGKILL") (catch :default _))
                                     (finish {:exit 124 :out (str @out "\n[timeout]")}))
                                timeout-ms)]
       (doto (.-stdin ps) (.write script) (.end))
       (.on (.-stdout ps) "data" #(swap! out str %))
       (.on (.-stderr ps) "data" #(swap! out str %))
       (.on ps "close" (fn [code] (js/clearTimeout timer) (finish {:exit code :out @out})))
       (.on ps "error" (fn [e] (js/clearTimeout timer) (finish {:exit -1 :out (str e)})))))))

(defn install-script [needs]
  (str/join
   "\n"
   (concat
    ["set -u"
     "export PATH=/opt/homebrew/bin:/usr/local/bin:$PATH"
     "export HOMEBREW_NO_AUTO_UPDATE=1 HOMEBREW_NO_INSTALL_CLEANUP=1"
     "command -v brew >/dev/null || { echo 'NO-BREW'; exit 3; }"
     (str "install_formula() { formula=\"$1\"; brew list \"$formula\" >/dev/null 2>&1"
          " && { echo \"STEP $formula already-present\"; return 0; };"
          " brew install --quiet \"$formula\"; rc=$?;"
          " echo \"STEP $formula exit=$rc\"; [ \"$rc\" -eq 0 ] || exit \"$rc\"; }")]
    (when (contains? needs :jvm)
      ;; clojure formula は openjdk を依存として引くので clojure だけで足りる。
      ;; 既に入っていれば brew は no-op（冪等）。
      ["echo '--- clojure/openjdk'"
       ;; 失敗を黙って飲まない: 実測で clojure の install が一部ノードで落ちていたのに
       ;; openjdk だけ入って「provision 成功」に見えていた（|| で繋いだ結果 exit 0）。
       "install_formula clojure"
       "install_formula openjdk"])
    (when (contains? needs :node)
      ["echo '--- node'"
       "install_formula node"])
    (when (contains? needs :zig)
      ["echo '--- zig'"
       "install_formula zig"])
    ["echo '--- verify'"
     "echo clojure=$(command -v clojure)"
     "for j in /opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk@26; do [ -x \"$j/bin/java\" ] && echo javahome=$j && break; done"
     "echo node=$(command -v node)"
     "echo zig=$(command -v zig)"
     "echo zigv=$(zig version 2>/dev/null)"
     ;; 実際に JVM が起動するかまで確かめる（PATH に居るだけでは gate は通らない）
     "if [ -x /opt/homebrew/opt/openjdk/bin/java ]; then JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk/bin/java -version 2>&1 | head -1; fi"])))

(defn -main []
  (let [needs (into #{} (map keyword) (-> (or (:need opts) "jvm")
                                          (str/split #",")))
        hosts (cond
                (:hosts opts) (->> (str/split (str (:hosts opts)) #",")
                                   (map str/trim) (remove str/blank?) vec)
                (:from-nodes opts)
                (let [{:keys [nodes]} (reader/read-string
                                       (str (fs/readFileSync (path/join here "nodes.edn") "utf8")))]
                  (->> nodes
                       (filter :reachable?)
                       (filter #(>= (or (:free-gb %) 0) 8))
                       (remove #(every? (:caps % #{}) needs))
                       (mapv :host)))
                :else (do (println "usage: --hosts a,b | --from-nodes  [--need jvm,node,zig] [--dry-run]")
                          (js/process.exit 2)))
        script (install-script needs)]
    (println "provision" (pr-str needs) "->" (str/join "," hosts))
    (if (:dry-run opts)
      (println script)
      ;; brew install は帯域とディスクを食うので 2 台ずつ。
      (-> (p/loop [[h & more] hosts]
            (if-not h
              (p/resolved nil)
              (p/let [{:keys [exit out]} (ssh-script h script 1800000)]
                (println (str "=== " h " exit " exit))
                ;; STEP 行・Error 行・verify 出力は必ず見せる（take-last だけだと
                ;; brew の進捗表示に押し流されて失敗を見落とす）
                (doseq [l (str/split-lines (str/trim out))]
                  (when (re-find #"STEP |Error|error:|javahome=|clojure=|node=|zig=|zigv=|openjdk version" l)
                    (println "   " l)))
                (p/recur more))))
          (p/then (fn [_] (println "\ndone — now re-run: nbb scripts/fleet-ci/probe.cljs")))))))

(-main)
