#!/usr/bin/env nbb
;; org-id-derivation-check.cljs — reverse-DNS 導出が 1 つであることを押さえる gate。
;;
;; **なぜ要るか。** 同じ規則（registrable domain のラベル逆順）が 3 箇所に在った:
;; `manifest/repository-rules.edn` の `:org-domain` が持つ手書きの `:reversed` 列、
;; `verify-repository-roles.cljs` の `domain->prefix`、そして cloud-itonami の
;; org id 受付。実測 2026-08-08、受付は 6 org すべてで**逆向き**を返しており
;; （`awai.network -> awai-network`、規則は `network-awai` = west.yml の remote 名）、
;; どの検査もそれを見ていなかった（ADR-2608098000）。
;;
;; **記録済みドメインだけでは足りない。** この gate の最初の版は 95 件の記録済み
;; ドメインだけを突き合わせて緑だったが、レビューで 2 実装が**等価でない**ことが
;; 判明した —— kotoba だけが case fold し、cljs だけが `www.` を落とし、非 ASCII と
;; 253 バイトで kotoba が trap していた。95 件が全て bare・小文字・ASCII・短い
;; ものだったので、4 つの分岐すべてが corpus の外に隠れていた。**corpus が偶然
;; そろっていたことを「一致」と読んでいた。** だから固定の敵対的入力列を足す。
;;
;; **この gate は規則を実装しない。** 3 箇所を 4 箇所にするだけなので。
;; 代わりに、既に在る 3 つの表現を互いに突き合わせる:
;;
;;   1. **artifact <-> source** —— `manifest/org-id.mjs` の `sourceDigest` が
;;      `manifest/org-id.kotoba` の sha256 と一致するか。`kotoba -M compile` は
;;      sha256 をそのまま焼くので、ノードに kotoba ツールチェーンが無くても
;;      束縛を確かめられる（committed な生成物は sha256 で見る、という規則）。
;;   2. **purity** —— `requiredCapabilities` が空。ドメイン文字列を畳むだけの
;;      関数が ambient authority を得たら、そこで止める。**「宣言が空」と
;;      「欄が読めない」を区別する** —— 後者で緑にすると検査が消えたことになる。
;;   3. **kotoba == cljs** —— `scripts/org_id_derivation.cljs` を**実際に呼んで**
;;      記録済みドメイン + 敵対的入力列で突き合わせる。gate は単一ファイルで配られ
;;      classpath を持たないので、`cloud-itonami-sites-check` と同じく
;;      **対象を子プロセスで起動する**（ロジックを複製しない）。
;;   4. **手書きの `:reversed` 列 == 導出** —— これまで誰も見ていなかった。
;;      7 org 分しかないので手で合っていたが、合っている理由が「たまたま」だった。
;;   5. **trap しないこと** —— 受け付けないものは「不正」として返すべきで、
;;      fault は拒否ではない。kotoba 側の trap は失敗として報告する（gate ごと
;;      落ちて診断が「artifact を import できない」になるのを防ぐ）。
;;
;; **`:reversed` を消して導出に置き換えない。** 手書きの列が残っているのは
;; 一致が**検査できる**からで、消すと突き合わせる相手がいなくなる。
;;
;; ノード側で `npx nbb org-id-derivation-check.cljs <dir> [--min N] [--min-rules N]`。

(ns fleet-ci.gates.org-id-derivation-check
  (:require ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def args (vec *command-line-args*))

(def ^:private value-flags #{"--min" "--min-rules"})

(def root
  (loop [[a & more] args prev nil]
    (cond (nil? a) "."
          (str/starts-with? a "--") (recur more a)
          (contains? value-flags prev) (recur more nil)
          :else a)))

(defn- opt [flag default]
  (let [i (.indexOf args flag)]
    (if (neg? i) default (get args (inc i)))))

(def min-domains (js/parseInt (opt "--min" "90")))
(def min-rules (js/parseInt (opt "--min-rules" "7")))

(def failures (atom []))

(defn- fail! [& parts]
  (let [m (str/join " " (map str parts))]
    (swap! failures conj m)
    (println "FAIL" m)))

(defn- read-edn [p] (edn/read-string (fs/readFileSync p "utf8")))

(defn- sha256 [buf]
  (-> (crypto/createHash "sha256") (.update buf) (.digest "hex")))

;; ---------------------------------------------------------------------------
;; 敵対的入力列。**corpus の外にある分岐を名指しで踏む。**
;; ここに 1 行足すのは、その分岐を両実装が同じに扱うと主張することに等しい。

(def adversarial
  ["www.ietf.org"        ;; 先頭 www の除去（cljs だけが持っていた）
   "WWW.IETF.ORG"        ;; 除去 + case fold の組み合わせ
   "IETF.ORG"            ;; case fold（kotoba だけが持っていた）
   "Awai.Network"        ;; 混在 case
   "wwx.ietf.org"        ;; www に似ているが違う —— 除去してはいけない
   "www.com"             ;; 除去するとラベルが 1 本になる
   "www."                ;; 除去すると空になる
   "日本.jp"              ;; punycode 前の IDN（kotoba が継続バイトで trap していた）
   "ＷＷＷ.jp"            ;; 全角。非 ASCII の別経路
   "xn--wgv71a.jp"       ;; A-label は通らなければならない
   "localhost"           ;; 単一ラベル
   "a..b"                ;; 空ラベル（中）
   ".x.com"              ;; 空ラベル（先頭）
   "x.com."              ;; 空ラベル（末尾）
   ""                    ;; 空文字列
   "co.jp"               ;; PSL を持たないので通る —— 判定しないことの確認
   "awai.network"])      ;; 素直な 1 件（対照）

(def over-length (str (str/join (repeat 250 "a")) ".com"))  ;; 254 バイト、DNS 上限超

;; ---------------------------------------------------------------------------
;; 入力: 記録されているドメインと org 規則。

(defn- recorded-domains []
  (let [p (path/join root "manifest" "origin-domains.edn")]
    (if-not (fs/existsSync p)
      (do (fail! "missing" p) [])
      (let [d (read-edn p)]
        (vec (sort (distinct (vals (merge (:anchors d {}) (:domains d {}))))))))))

(defn- org-domain-rules []
  (let [p (path/join root "manifest" "repository-rules.edn")]
    (if-not (fs/existsSync p)
      (do (fail! "missing" p) [])
      (let [doc (read-edn p)
            v (first (filter #(= :org-domain (:vocabulary/id %)) (:vocabularies doc)))]
        (when (nil? v)
          (fail! ":org-domain vocabulary not found in repository-rules.edn"
                 "— check 4 would silently check nothing"))
        (vec (:vocabulary/rules v))))))

;; ---------------------------------------------------------------------------
;; cljs 側: 検査対象を子プロセスで起動して答えだけ受け取る。**規則を写さない。**

(defn- cljs-answers [probe]
  (let [expr (str "(require '[scripts.org-id-derivation :as o])"
                  "(prn {:prefix (into {} (map (juxt identity o/domain->prefix)) "
                  (pr-str (vec probe)) ")"
                  " :shape (into {} (map (juxt identity (comp boolean o/registrable-shape?))) "
                  (pr-str (vec probe)) ")})")
        r (cp/spawnSync "npx" (clj->js ["--yes" "nbb" "--classpath" "." "-e" expr])
                        #js {:cwd root :encoding "utf8" :maxBuffer (* 32 1024 1024)})
        out (str (aget r "stdout"))
        err (str (aget r "stderr"))]
    (if (or (not= 0 (aget r "status")) (str/blank? out))
      (do (fail! "could not run scripts/org_id_derivation.cljs —"
                 (str/join " " (take-last 3 (str/split-lines (str err out)))))
          nil)
      (try (edn/read-string out)
           (catch :default e
             (fail! "unreadable output from the cljs derivation:" (.-message e))
             nil)))))

;; ---------------------------------------------------------------------------
;; kotoba 側。**trap を失敗として捕まえる** —— 外へ投げると gate ごと落ちて、
;; 診断が「artifact を import できない」になり原因を取り違える。

(defn- guarded [f label d]
  (try {:ok (f d)}
       (catch :default e {:trap (.-message e) :label label :input d})))

(defn- check-parity! [m cljs probe rules]
  (let [instantiate (aget m "instantiateKotoba")
        ;; fuel は instance ごとに尽きる（実測: 1 instance を 7 回使い回して
        ;; fuel-exhausted）。1 呼び出し 1 instance にする。
        k-derive #(guarded (fn [d] ((aget (instantiate #js {}) "derive-org-id") d)) "derive-org-id" %)
        k-shape  #(guarded (fn [d] (js/Number ((aget (instantiate #js {}) "registrable-shape") d))) "registrable-shape" %)
        prefixes (:prefix cljs)
        shapes   (:shape cljs)]
    ;; 3a. 形の判定が全入力で一致するか（**derive より先** —— derive の前提だから）
    (doseq [d probe]
      (let [k (k-shape d)]
        (cond
          (:trap k)
          (fail! "registrable-shape TRAPPED on" (pr-str d) "—" (:trap k)
                 "· 受け付けないものは不正として返すこと。fault は拒否ではない")
          (not= (= 1 (:ok k)) (boolean (get shapes d)))
          (fail! "shape disagreement on" (pr-str d)
                 "— kotoba:" (= 1 (:ok k)) "cljs:" (boolean (get shapes d))))))
    ;; 3b. 形が有効な入力で導出が一致するか
    (doseq [d probe]
      (let [ks (k-shape d)]
        (when (and (nil? (:trap ks)) (= 1 (:ok ks)) (get shapes d))
          (let [k (k-derive d) b (get prefixes d)]
            (cond
              (:trap k) (fail! "derive-org-id TRAPPED on" (pr-str d) "—" (:trap k))
              (not= (:ok k) b) (fail! (pr-str d) "— kotoba derives" (pr-str (:ok k))
                                      "but cljs derives" (pr-str b)))))))
    ;; 4. 手書きの :reversed 列 == 導出
    (doseq [{:keys [org domain reversed]} rules]
      (let [k (k-derive domain)]
        (if (:trap k)
          (fail! ":org-domain" org "— derive TRAPPED on" (pr-str domain) "—" (:trap k))
          (when-not (= (:ok k) reversed)
            (fail! ":org-domain" org "records :reversed" (pr-str reversed)
                   "but" domain "derives" (pr-str (:ok k)))))))))

(defn- main! []
  (let [kotoba-src (path/join root "manifest" "org-id.kotoba")
        artifact   (path/join root "manifest" "org-id.mjs")]
    (cond
      (not (fs/existsSync kotoba-src)) (do (fail! "missing" kotoba-src)
                                           (set! (.-exitCode js/process) 1))
      (not (fs/existsSync artifact))   (do (fail! "missing" artifact)
                                           (set! (.-exitCode js/process) 1))
      :else
      (let [want (sha256 (fs/readFileSync kotoba-src))
            js   (fs/readFileSync artifact "utf8")
            got  (second (re-find #"sourceDigest:\"([0-9a-f]+)\"" js))
            caps (second (re-find #"requiredCapabilities:Object\.freeze\(\[([^\]]*)\]\)" js))]
        ;; 1. artifact <-> source
        (when-not (= want got)
          (fail! "artifact does not belong to the committed source:"
                 "org-id.kotoba sha256" want "but org-id.mjs carries" (str got)))
        ;; 2. purity。**欄が読めないことを「空」と読まない** —— re-find が外れると
        ;; caps は nil で、(str nil) は "" なので、素朴に書くと「capability 無し」で
        ;; 緑になる。隣の digest 検査は外れれば落ちるのに、こちらだけ fail-open だった。
        (cond
          (nil? caps)
          (fail! "could not read requiredCapabilities from the artifact"
                 "— the purity check cannot run, and absence is not a pass")
          (not= "" (str/trim caps))
          (fail! "the derivation is no longer pure — requiredCapabilities:" caps))
        (println "artifact<->source:" (if (= want got) "bound" "BROKEN")
                 "· capabilities:" (cond (nil? caps) "UNREADABLE"
                                         (= "" (str/trim caps)) "none"
                                         :else caps))

        (let [domains (recorded-domains)
              rules   (org-domain-rules)
              probe   (vec (distinct (concat domains adversarial [over-length])))]
          ;; 絞り込みが壊れて 0 件を「合格」にしない床。上限ではない。
          ;; **`when (seq …)` の中に入れない** —— 0 件こそが床の存在理由なので、
          ;; 0 件のときだけ床を飛ばすのでは検査になっていない。
          (when (< (count domains) min-domains)
            (fail! "only" (count domains) "recorded domains found, floor is" min-domains))
          (when (< (count rules) min-rules)
            (fail! "only" (count rules) ":org-domain rules found, floor is" min-rules
                   "— check 4 would degrade to nothing"))
          (if-let [cljs (cljs-answers probe)]
            (-> (js/import (str "file://" (path/resolve artifact)))
                (.catch (fn [e]
                          (fail! "could not import the compiled artifact:" (.-message e))
                          nil))
                (.then (fn [m]
                         (when m (check-parity! m cljs probe rules))
                         (println (str "checked " (count domains) " recorded + "
                                       (inc (count adversarial)) " adversarial inputs, "
                                       (count rules) " org rules, "
                                       (count @failures) " failing"))
                         (when (seq @failures)
                           (println "")
                           (println "ADR-2608098000: 導出の正本は manifest/org-id.kotoba。")
                           (println "cljs 側は scripts/org_id_derivation.cljs の 1 箇所だけ。")
                           (println "正規化の順序（長さ -> fold -> www 除去 -> ASCII -> ラベル）は")
                           (println "両実装の契約。片方だけ変えると必ずここが落ちる。")
                           (set! (.-exitCode js/process) 1)))))
            (set! (.-exitCode js/process) 1)))))))

(main!)
