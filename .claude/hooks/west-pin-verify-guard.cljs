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
         '[scripts.nbb-compat :as compat])

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
            field-b64 (some-> (re-find (re-pattern (str "(?:-F|--field)[=\\s]+content=@(" path-tok-src ")")) cmd)
                              second strip-quotes
                              ((fn [f] (when (and f (.exists (io/file f))) (compat/slurp f)))))
            b64   (or (:content body) field-b64)]
        (if-not b64
          (allow!)   ; content を取り出せない形は fail-open
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
                (verify! top "--candidate" tmp)))))))

    ;; --- 経路1: git push で west.yml が origin/main と異なる ------------------
    (when (re-find (re-pattern (str "git\\s+(?:-C\\s+(?:" path-tok-src ")\\s+)?push\\b")) cmd)
      (when (str/includes? cmd "--dry-run") (allow!))
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
          (when (or (str/blank? head-blob) (str/blank? main-blob) (= head-blob main-blob))
            (allow!))
          (verify! top "--baseline" "origin/main" "--candidate" "HEAD"))))

    (allow!))
  (catch :default _ (compat/exit 0)))
