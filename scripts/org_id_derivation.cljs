;; org_id_derivation.cljs — reverse-DNS 導出の cljs 側の唯一の実装。
;;
;; **なぜ独立したファイルなのか。** 同じ規則が `verify-repository-roles.cljs` の
;; `domain->prefix`（origin 面）と cloud-itonami の `org-id`（org id 受付）に
;; それぞれ書かれていた。実測 2026-08-08、受付側は**逆向き**を返しており
;; （`awai.network -> awai-network`、規則は `network-awai`）、宣言と受付が
;; 食い違ったまま誰も落ちなかった（ADR-2608094000）。
;;
;; 規則の**正本は `manifest/org-id.kotoba`**（`kotoba/pure`、capability 0）。
;; ここはその cljs 側の相方で、両者が一致することを fleet gate
;; `root-org-id-derivation` が全記録ドメインに対して見る —— `kotoba-lang/css`
;; の移植が取った parity gate と同じ形（原典は無変更のまま、byte 一致を機械で
;; 押さえる）。
;;
;; **一方向（domain -> prefix）だけ。** 名前からドメインを逆算しない ——
;; `com-yang-ming-api` は一意に分解できない（1 ラベルがハイフンを含まないのに
;; 主題が複数含みうる）。検査は「名前が導出した prefix で始まるか」だけ。

(ns scripts.org-id-derivation
  (:require [clojure.string :as str]))

(defn domain->prefix
  "Reverse a registrable domain's labels into the origin prefix.
   ietf.org -> org-ietf ; boj.or.jp -> jp-or-boj ; sel4.systems -> systems-sel4.
   One-way by design: a name cannot be parsed back into a domain, because a
   single label may contain no hyphen while the subject contains several."
  [domain]
  (->> (str/split (str/replace domain #"^www\." "") #"\.")
       reverse
       (str/join "-")))

(defn registrable-shape?
  "Does the string have the SHAPE of a registrable domain — at least two
   labels, none of them empty. PSL は持たないので `co.jp` のような多段 suffix は
   判定できない。だから「その形をしていないもの」だけを断る（通してから取り消す
   のは、断るより高い）。`manifest/org-id.kotoba` の `registrable-shape` と
   同じ判定で、gate がその一致を見る。"
  [domain]
  (let [labels (str/split (str domain) #"\." -1)]
    (and (>= (count labels) 2)
         (every? seq labels))))
