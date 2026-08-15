#!/usr/bin/env nbb
;; PreToolUse(Bash) ガード: manifest/west.yml の pin 変更を main に載せる前に
;; サーバ側検証(scripts/verify-west-pins.cljs)を強制する(ADR-2607022900)。
;;
;; 対象2経路:
;;   1. `git push`(superproject)で HEAD の west.yml が origin/main と異なる場合
;;      → baseline=origin/main / candidate=HEAD で検証。
;;   2. `gh api -X PUT ... contents/manifest/west.yml --input <json>`(API single-entry 経路)
;;      → JSON body の content(base64)を decode して検証。
;;
;; deny は verify が exit 1(検証 FAIL)の時だけ。検証不能(gh 無し等 exit 2/3)や
;; このスクリプト自身のあらゆる失敗は fail-open(許可)。緊急スキップは
;; WEST_PIN_VERIFY_SKIP=1(verify script 側で解釈)。

(require '[cheshire.core :as json]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[scripts.nbb-compat :as compat]
         '[scripts.west-pin-guard-policy :as policy])

(def node-fs (js/require "node:fs"))
(def node-os (js/require "node:os"))
(def node-path (js/require "node:path"))

(defn- run [& args]
  (try (let [{:keys [exit out err]} (p/sh (mapv str args))]
         {:exit exit :out (str/trim (or out "")) :err (str/trim (or err ""))})
       (catch :default e {:exit -1 :out "" :err (str e)})))

(defn- git [dir & args] (apply run "git" "-C" dir args))

(defn allow! [] (compat/exit 0))

(defn deny! [reason]
  (println (json/generate-string
             {:hookSpecificOutput
              {:hookEventName "PreToolUse"
               :permissionDecision "deny"
               :permissionDecisionReason reason}}))
  (compat/exit 0))

(def ^:private path-tok-src "\"[^\"]*\"|'[^']*'|[^\\s;&|]+")

(defn- strip-quotes [s]
  (when s
    (if (and (>= (count s) 2)
             (or (and (str/starts-with? s "\"") (str/ends-with? s "\""))
                 (and (str/starts-with? s "'") (str/ends-with? s "'"))))
      (subs s 1 (dec (count s)))
      s)))

(defn- nbb-bin
  "裸の \"nbb\" は PATH に無い環境(repo-local install のみ)で spawn が即失敗し、
  exit -1 → fail-open で検証が黙って素通りする。repo-local の
  node_modules/.bin/nbb を優先し、無ければ PATH に頼る。"
  [top]
  (let [local (io/file top "node_modules" ".bin" "nbb")]
    (if (.exists local) (.getPath local) "nbb")))

;; deny 文に添える「この candidate はどこから来たか」。PUT 経路だけが設定する。
;; **hook はコマンド実行前に走るので、payload を書く処理が同じ Bash 呼び出しに入って
;; いると、hook が読むのは同名の古い残骸**である。payload path が読めない場合は既に
;; deny しているが、読めてしまう場合(前回の残骸が居る)は静かに別の内容を検証して
;; 「触っていない pin が退行している」と報告する。実測 2026-08-16: /tmp/put.json が
;; 前周の残骸で、単一 entry の advance に対し 10 件超の退行が並んだ。
;; 出所と mtime を書けば、その場で残骸だと分かる。
(def payload-origin (atom nil))

(defn- verify!
  "verify-west-pins.cljs を実行し、exit 1 なら deny。それ以外は allow 側に倒す。"
  [top & extra-args]
  (let [vscript (io/file top "scripts" "verify-west-pins.cljs")]
    (when-not (.exists vscript) (allow!))
    (let [{:keys [exit out err]} (apply run (nbb-bin top) (.getPath vscript) "--dir" top extra-args)]
      (when (= 1 exit)
        (let [msg (str/join "\n" (remove str/blank? [out err]))]
          (deny! (str "west.yml の pin 検証に失敗しました(未 push commit / main 非到達 / pin 退行)。"
                      "main に載る前にブロックします。\n\n"
                      (if (> (count msg) 1500) (str (subs msg 0 1500) "\n…(truncated)") msg)
                      (when-let [o @payload-origin] (str "\n\n検証した candidate の出所: " o))
                      "\n\n修正: 子リポを push / pin を main 上の commit に / 退行なら git pull + west update 後に再生成。"
                      "緊急スキップ: WEST_PIN_VERIFY_SKIP=1(理由をコミットに残すこと)。"))))
      (allow!))))

(try
  (let [cmd (or (some-> (compat/read-stdin) (json/parse-string true) (get-in [:tool_input :command])) "")]

    ;; --- 経路2: gh api PUT contents/manifest/west.yml -------------------------
    (when (and (re-find #"gh\s+api\b" cmd)
               (re-find #"contents/manifest/west\.yml" cmd)
               (re-find #"(?:-X|--method)[=\s]+PUT\b" cmd))
      (let [input (some-> (re-find (re-pattern (str "--input[=\\s]+(" path-tok-src ")")) cmd)
                          second strip-quotes)
            body  (when (and input (.exists (io/file input)))
                    (try (json/parse-string (compat/slurp input) true) (catch :default _ nil)))
            ;; -F/--field content=@<file> 経路(repos.edn :manifest-workflow の正経路が
            ;; この形。file は raw base64)。path が $VAR 等で解決できない場合は従来通り
            ;; fail-open(hook はシェル展開前のコマンド文字列しか見えない)。
            field-path (some-> (re-find (re-pattern (str "(?:-F|--field)[=\\s]+content=@(" path-tok-src ")")) cmd)
                               second strip-quotes)
            field-b64 (when (and field-path (.exists (io/file field-path)))
                        (compat/slurp field-path))
            b64   (or (:content body) field-b64)]
        (case (policy/payload-decision (boolean (or input field-path)) (boolean b64))
          ;; payload path が名指しされているのに読めない = 検証すべき content が
          ;; 存在しない。従来ここは fail-open で、**payload を書く処理と PUT が同じ
          ;; 1コマンドに入っていると素通りしていた**(hook はコマンド実行前に走るので
          ;; ファイルはまだ無いか、前回の残骸)。実測 2026-08-03: 前回の残骸を読んで
          ;; tip にも新 payload にも無い pin を報告した。deny して2段階に分けさせる。
          :unreadable
          (deny! (str "west.yml への PUT の payload を検証できません: "
                      (or input field-path) " が読めません。\n\n"
                      "PreToolUse hook はコマンド**実行前**に走るので、payload を書く処理と "
                      "gh api PUT が同じコマンドに入っていると、hook は「まだ無いファイル」か "
                      "「前回の残骸」しか見られません。payload 生成と PUT を**別の呼び出しに"
                      "分けて**ください。\n\n"
                      "推奨: nbb scripts/west-pin-put.cljs <entry> <sha> "
                      "(tip から読んだ pin に対して検証し、同じ blob SHA を precondition に書く)"))

          :unknown (allow!)   ; payload path が無い形(inline / $VAR / wrapper)は従来どおり fail-open

          :verify
          (let [decoded (try (.toString (.from js/Buffer (str/replace b64 #"\s" "") "base64") "utf8")
                             (catch :default _ nil))]
            (if-not decoded
              (allow!)
              ;; PUT 先は superproject の manifest。cwd が別の場所でも解決できるよう
              ;; CLAUDE_PROJECT_DIR(hook 実行時に必ず渡る)を優先し、無ければ cwd の toplevel。
              (let [tmp-dir (.mkdtempSync node-fs (.join node-path (.tmpdir node-os) "west-put-"))
                    tmp     (.join node-path tmp-dir "candidate.yml")
                    top     (or (some-> (compat/getenv "CLAUDE_PROJECT_DIR")
                                        (#(when (.exists (io/file % "scripts" "verify-west-pins.cljs")) %)))
                                (let [t (:out (git "." "rev-parse" "--show-toplevel"))]
                                  (when-not (str/blank? t) t))
                                ".")]
                (compat/spit tmp decoded)
                (reset! payload-origin
                        (let [src (or input field-path)]
                          (str (or src "inline") " ("
                               (or (try (some-> (.statSync node-fs src) .-mtime
                                                (.toISOString))
                                        (catch :default _ nil))
                                   "mtime 不明")
                               ", " (count decoded) " bytes)"
                               " ← このファイルを書いたのが今回の呼び出し自身なら、hook が"
                               "読んだのは前回の残骸です(payload 生成と PUT を別の呼び出しに分ける)")))
                (verify! top "--candidate" tmp)))))))

    ;; --- 経路1: git push で west.yml が origin/main と異なる ------------------
    (when (re-find (re-pattern (str "git\\s+(?:-C\\s+(?:" path-tok-src ")\\s+)?push\\b")) cmd)
      (when (str/includes? cmd "--dry-run") (allow!))
      ;; ref の削除は内容を運ばないので検証する candidate が無い。ここを除外
      ;; しないと、下の HEAD vs origin/main 比較が「今の checkout がたまたま
      ;; どうなっているか」を測ってしまう。実測 2026-08-08: merge 済み branch を
      ;; origin/main より遅れた checkout から削除しようとして、コマンドと何の
      ;; 関係も無い pin 7 件を退行として報告し deny した。
      (when (policy/deletion-push? cmd) (allow!))
      (let [cdir (some-> (re-find (re-pattern (str "git\\s+-C\\s+(" path-tok-src ")")) cmd)
                         second strip-quotes)
            cd   (some-> (re-find (re-pattern (str "cd\\s+(" path-tok-src ")")) cmd)
                         second strip-quotes)
            dir  (or cdir cd ".")
            top  (:out (git dir "rev-parse" "--show-toplevel"))]
        (when (str/blank? top) (allow!))
        (let [head-blob (:out (git top "rev-parse" "-q" "--verify" "HEAD:manifest/west.yml"))
              main-blob (:out (git top "rev-parse" "-q" "--verify" "origin/main:manifest/west.yml"))]
          ;; west.yml を持たない repo / 差分なし → 対象外
          (when-not (policy/requires-verification? head-blob main-blob)
            (allow!))
          (verify! top "--baseline" "origin/main" "--candidate" "HEAD"))))

    (allow!))
  (catch :default _ (compat/exit 0)))
