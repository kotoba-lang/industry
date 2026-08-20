#!/usr/bin/env nbb
;; 新しい「面」を作ろうとしたとき、**既に在るものを見せる**（止めない）。
;;
;; ## なぜ hook なのか
;;
;; CLAUDE.md には既に『作る前に、同種の重なる仕組みが既にないか確認する』と
;; 書いてある。2026-08-03、それを読んだうえで 4 件重複させた ——
;; murakumo の `/signup`（`authn.kotobase.net/sign-in` が既に稼働）、
;; email OTP（`/v1/email/start` が既に稼働）、hosted UI、そして sekisho の一部。
;;
;; 同じ結論が既に branch 同期で出ている（CLAUDE.md）:
;;
;;   > agent が都度思い出して確認する運用は **機能しなかった** ため、
;;   > hook で強制する
;;
;; ## 止めずに見せる
;;
;; **deny しない。** 重複が正当な場合はある（意図的な分離・別テナント・
;; 移行期の並走）。今日必要だったのはブロックではなく
;; `authn.kotobase.net/sign-in` という 1 行の提示だった。
;;
;; 判断できないときも通す（fail-open）—— この hook が壊れて作業が止まる方が、
;; 重複 1 件より高くつく。

(ns duplication-warn
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(def root "/Users/junkawasaki/github/com-junkawasaki")
(def index-file (path/join root "90-docs" "surface" "surface.datoms.edn"))

(def ^:private concepts
  "**概念**ごとの綴りの集合。リテラル部分一致では当たらないので束ねる。

   最初の版は `signup` を部分一致で探し、索引の `/sign-in` `/signin-url` に
   1 件も当たらなかった（実測）—— つまり**今日の失敗をそのまま見逃す** hook
   だった。黙って何も出さない警告は、無いより悪い（『警告が出なかった』が
   『重複が無い』の証拠として使われる）。

   語は経験から選ぶ。2026-08-03 に重複させたのは全部 auth まわりだった。
   増やしすぎると雑音になり、雑音は無視されるので警告そのものが死ぬ。"
  {:signin  ["signup" "sign-up" "sign_up" "signin" "sign-in" "sign_in"
             "login" "log-in" "register" "account" "auth"]
   :session ["session" "logout" "sign-out" "signout" "token" "verify-session"]
   :passkey ["passkey" "webauthn" "credential"]
   :social  ["oauth" "oidc" "saml" "sso" "github" "google"]
   :email   ["email" "otp" "magic-link" "magiclink"]
   :billing ["checkout" "billing" "pricing" "subscribe" "subscription" "plan"]})

(defn- concepts-in
  "対象文字列が触れている概念。"
  [s]
  (let [l (str/lower-case (or s ""))]
    (keep (fn [[c terms]] (when (some #(str/includes? l %) terms) c)) concepts)))

(defn- read-index []
  (try (edn/read-string (fs/readFileSync index-file "utf8"))
       (catch :default _ nil)))

(defn- matches
  "その概念に属する既存の面（同一 repo は除く —— 自分の面は重複ではない）。"
  [index concept self-repo]
  (let [terms (concepts concept)]
    (->> index
         (filter (fn [d]
                   (let [p (str/lower-case (str (:surface/path d)))]
                     (and (some #(str/includes? p %) terms)
                          (not= (:surface/repo d) self-repo)))))
         ;; **ホスト+パスで畳む。** 同じ面が複数 repo（wave2/wave3/obsidian-story
         ;; のような並走 checkout）に現れると、同じ 1 行が 3 回出る。
         ;; 雑音は無視され、無視される警告は存在しないのと同じ。
         (map (fn [d] [(str (:surface/host d) (:surface/path d))
                       (:surface/repo d) (:surface/registered? d)]))
         (group-by first)
         (map (fn [[k v]]
                ;; **west 登録済みの repo を優先して見せる。** 未登録は正本とは
                ;; 限らない作業コピーで、そこを編集しても何も起きない（実測
                ;; 2026-08-03: authn の面 34 個は全部未登録コピー由来だった）。
                (let [reg (first (filter #(nth % 2) v))
                      pick (or reg (first v))]
                  (str k "  ← " (second pick)
                       (when-not (nth pick 2) "  ⚠west未登録")
                       (when (> (count v) 1) (str " 他 " (dec (count v)) " repo"))))))
         sort
         (take 5))))

(defn- self-repo-of [file-path]
  (when file-path
    (some->> (re-find #"orgs/[^/]+/[^/]+" (str file-path)) str)))

(defn -main []
  (let [raw (try (fs/readFileSync 0 "utf8") (catch :default _ ""))
        input (try (js->clj (js/JSON.parse raw) :keywordize-keys true)
                   (catch :default _ nil))
        tool (:tool_name input)
        ti (:tool_input input)
        ;; Write の新規ファイル / Bash の `gh repo create` を見る
        target (case tool
                 "Write" (:file_path ti)
                 "Bash" (when (re-find #"gh repo create" (str (:command ti)))
                          (:command ti))
                 nil)]
    (when target
      (when-let [index (read-index)]
        (let [self (self-repo-of target)
              cs (concepts-in target)
              hits (distinct (mapcat #(matches index % self) cs))]
          (when (seq hits)
            (println
             (str "⚠ 既に稼働している近い面があります（止めていません。参考です）:\n"
                  (str/join "\n" (map #(str "    " %) (distinct hits)))
                  "\n\n  索引: 90-docs/surface/surface.datoms.edn"
                  "\n  再生成: nbb scripts/gen-surface-index.cljs"
                  "\n\n  2026-08-03、murakumo に /signup を手書きしたが"
                  " authn.kotobase.net/sign-in は既に稼働していた。"))))))
    ;; 常に通す
    (set! (.-exitCode js/process) 0)))

(-main)
