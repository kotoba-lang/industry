;; org_id_derivation.cljs — reverse-DNS 導出の cljs 側の唯一の実装。
;;
;; **なぜ独立したファイルなのか。** 同じ規則が `verify-repository-roles.cljs` の
;; `domain->prefix`（origin 面）と cloud-itonami の `org-id`（org id 受付）に
;; それぞれ書かれていた。実測 2026-08-08、受付側は**逆向き**を返しており
;; （`awai.network -> awai-network`、規則は `network-awai`）、宣言と受付が
;; 食い違ったまま誰も落ちなかった（ADR-2608098000）。
;;
;; 規則の**正本は `manifest/org-id.kotoba`**（`kotoba/pure`、capability 0）。
;; ここはその cljs 側の相方で、両者が一致することを fleet gate
;; `root-org-id-derivation` が**記録済みドメイン + 敵対的な入力列**に対して見る。
;;
;; **正規化の順序が契約である。** `manifest/org-id.kotoba` と同じ順序を同じ入力に
;; 対して行うこと。順序を変えるなら両方同時に:
;;
;;   1. 長さ（バイト）が 253 を超えたら不正   —— DNS の上限
;;   2. case fold                              —— ドメインは大小を区別しない
;;   3. 先頭の `www.` を落とす
;;   4. 非 ASCII が 1 つでもあれば不正         —— punycode 前の IDN は受けない
;;   5. ラベルが 2 未満、または空ラベルがあれば不正
;;
;; この 5 段は 2026-08-08 のレビューで入った。**それまで両実装は等価ではなく**、
;; kotoba だけが case fold し、cljs だけが `www.` を落とし、非 ASCII と 253 バイトで
;; kotoba が trap していた。記録済みドメイン 95 件が全て bare・小文字・ASCII・短い
;; ものだったので gate は緑のままだった —— **corpus が偶然そろっていただけ**。
;; だから gate は corpus に加えて敵対的な入力列も突き合わせる。
;;
;; **一方向（domain -> prefix）だけ。** 名前からドメインを逆算しない ——
;; `com-yang-ming-api` は一意に分解できない（1 ラベルがハイフンを含まないのに
;; 主題が複数含みうる）。検査は「名前が導出した prefix で始まるか」だけ。

(ns scripts.org-id-derivation
  (:require [clojure.string :as str]))

(def ^:private max-domain-bytes
  "DNS 名の上限。超えたものは受け付けない —— **trap ではなく不正として返す。**
  kotoba 側は 2 パス走査だと 253 バイトの合法なドメインで fuel が尽きていた
  （実測）。拒否できずに fault するのは、拒否とは違う。"
  253)

(defn- ascii-only? [^String d]
  (every? #(< (.charCodeAt d %) 128) (range (count d))))

(defn- strip-www [^String d]
  (if (str/starts-with? d "www.") (subs d 4) d))

(defn- normalize
  "手順 2-3。手順 1 と 4-5 は呼び出し側が見る。"
  [domain]
  (strip-www (str/lower-case (str domain))))

(defn registrable-shape?
  "Does the string have the SHAPE of a registrable domain — at least two labels,
  none empty, ASCII only, within the DNS length limit. PSL は持たないので
  `co.jp` のような多段 suffix は判定できない。だから「その形をしていないもの」
  だけを断る（通してから取り消すのは、断るより高い）。

  非 ASCII を断るのは ADR-2608062100 が既に決めていること
  （`:not-a-registrable-domain`）で、新しい制限ではない。punycode 済みの
  A-label（`xn--wgv71a.jp`）は普通に通る。

  長さは**元の文字列**で見る（正規化の前）—— kotoba 側と同じ順序。ASCII でない
  ものはどのみち落ちるので、バイト長と文字数の差はここでは効かない。"
  [domain]
  (let [raw (str domain)]
    (and (<= (count raw) max-domain-bytes)
         (ascii-only? raw)
         (let [labels (str/split (normalize raw) #"\." -1)]
           (and (>= (count labels) 2)
                (every? seq labels))))))

(defn domain->prefix
  "Reverse a registrable domain's labels into the origin prefix.
   ietf.org -> org-ietf ; boj.or.jp -> jp-or-boj ; sel4.systems -> systems-sel4.
   One-way by design: a name cannot be parsed back into a domain, because a
   single label may contain no hyphen while the subject contains several.

   前提: `registrable-shape?` が true を返す入力であること。kotoba 側の
   `derive-org-id` は同じ前提を持ち、非 ASCII を渡すと trap する。"
  [domain]
  (->> (str/split (normalize domain) #"\." -1)
       reverse
       (str/join "-")))
