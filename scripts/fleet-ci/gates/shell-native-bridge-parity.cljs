#!/usr/bin/env nbb
;; shell-native-bridge-parity.cljs — kotoba-lang/shell の gate。
;;
;; 検査する不変条件は1つ:
;;
;;   `kotoba.shell.native-bridge/bridge-provider-commands` が宣言する command
;;   集合と、iOS/macOS 側 (kotoba_shell_bridge.swift)・Android 側
;;   (KotobaShellBridge.java) が実際に実装している command 集合が、3つとも
;;   完全に一致すること。
;;
;; なぜこれが load-bearing か:
;;
;; - 宣言にあって Swift/Java に無い command は、**その platform でだけ**実行時に
;;   `unknown-command` で落ちる。policy asset には capability 付きで載るので、
;;   deny ではなく「許可されているのに動かない」という一番読みにくい壊れ方をする。
;; - Swift/Java にあって宣言に無い command は、policy asset の capabilities に
;;   出てこない。すると capability による allow（`clipboard/text` で
;;   clipboard/* をまとめて許す書き方）が効かず、command 名を直接書いた policy
;;   でしか通らない。これも silent な非対称。
;;
;; 片側だけ足す/消すのは、コードを読んでいる限り自然に見える変更なので、
;; 人間のレビューではなく機械で押さえる。
;;
;; ノード側で `npx nbb shell-native-bridge-parity.cljs <dir>` として実行される。
;;
;; false-pass 対策: 3ファイルのどれかが読めなければ FAIL する（tree の絞り込み
;; ミスで「0 command 対 0 command なので一致」と報告する事故を防ぐ）。空集合も
;; FAIL 扱い。
(ns fleet-ci.gates.shell-native-bridge-parity
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.set :as set]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))

(def sources
  {:declared "src/kotoba/shell/native_bridge.clj"
   :swift "resources/kotoba/shell/app/kotoba_shell_bridge.swift"
   :java "resources/kotoba/shell/app/KotobaShellBridge.java"})

(defn- read-source [rel]
  (let [p (path/join root rel)]
    (when (fs/existsSync p)
      (str (fs/readFileSync p "utf8")))))

;; command は "family/verb"（clipboard/read-text 等）。3ファイルとも
;; 二重引用符で括った文字列として現れるので、同じ1本の正規表現で拾う。
(def command-pattern #"\"([a-z][a-z0-9-]*/[a-z][a-z0-9-]*)\"")

(defn- commands-in [text]
  (set (map second (re-seq command-pattern (or text "")))))

(defn- declared-commands
  "native-bridge.clj の `bridge-provider-commands` ベクタの中だけを見る。
  ファイル全体を舐めると docstring 中の例（`webauthn/register` 等、
  意図的に bridge から外している command）を拾ってしまう。"
  [text]
  (let [start (str/index-of (or text "") "(def bridge-provider-commands")]
    (when start
      (let [tail (subs text start)
            end (str/index-of tail "])")]
        (when end
          (commands-in (subs tail 0 end)))))))

(defn- switch-commands
  "Swift / Java の provider dispatch にある `case \"x/y\":` だけを見る。
  ファイル全体だとコメントや policy の説明文中の command 名も拾う。"
  [text]
  (set (map second (re-seq #"case\s+\"([a-z][a-z0-9-]*/[a-z][a-z0-9-]*)\"" (or text "")))))

(defn- fail [& msg]
  (println (str/join " " (cons "FAIL:" msg)))
  (js/process.exit 1))

(let [texts (into {} (map (fn [[k rel]] [k (read-source rel)])) sources)
      missing-files (keep (fn [[k text]] (when-not text (get sources k))) texts)]
  (when (seq missing-files)
    (fail "source not found under" root "—" (str/join ", " missing-files)))

  (let [declared (declared-commands (:declared texts))
        swift (switch-commands (:swift texts))
        java (switch-commands (:java texts))]
    (when-not declared
      (fail "could not locate bridge-provider-commands in" (:declared sources)))
    (doseq [[label found] [["declared" declared] ["swift" swift] ["java" java]]]
      (when (empty? found)
        (fail "no provider commands found in" label "— the scan is broken, not the code")))

    (let [problems
          (concat
           (map #(str "declared but missing from Swift: " %) (sort (set/difference declared swift)))
           (map #(str "declared but missing from Java: " %) (sort (set/difference declared java)))
           (map #(str "implemented in Swift but not declared: " %) (sort (set/difference swift declared)))
           (map #(str "implemented in Java but not declared: " %) (sort (set/difference java declared))))]
      (if (seq problems)
        (fail (str "in-app provider bridge is asymmetric\n  " (str/join "\n  " problems)))
        (println (str "OK: " (count declared) " provider commands declared and implemented "
                      "on both native halves (" (str/join ", " (sort declared)) ")"))))))
