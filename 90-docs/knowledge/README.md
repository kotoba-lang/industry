# 90-docs/knowledge — 構造化ナレッジ（datoms.edn）

会話・調査で得た一般知識を DataScript / Datomic / kotoba-datomic で query できる
EDN transaction として蓄積するディレクトリ。形式の先行例は
`90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn`（schema entity + data entity を
1つの tx vector に同居させる）。

方針:

- **捏造ゼロ**: 一次ソース未確認の行には `:confidence :medium` 以下を付す。
  不明な値は attribute ごと省略する（"unknown" 文字列や当て推量を入れない）。
- schema は各ファイル先頭に `:db/ident` entity として同梱（self-describing）。
- lookup ref（`[:country/id :us]` 等）で entity 間を参照する。

## ファイル一覧

| file | 内容 |
|---|---|
| `childrens-content-crude-humor.datoms.edn` | 子供向けコンテンツの下ネタ・露出規制の国別比較 + クレヨンしんちゃんの国別取り扱い事例 |
| `anime-distribution.datoms.edn` | アニメ作品の国際配信: 国 × 放送局/プラットフォーム (`:network/*`) × 作品 × リリース (`:release/*`)。裏取り済み行は `:release/source` に URL |

`:country/*` / `:work/*` の attribute 定義は両ファイルで同一なので、**2ファイルを同じ DB に
transact して横断クエリできる**（`:country/id` / `:work/id` が `:db.unique/identity` の
ため同一 entity は upsert でマージされる）。例: 「`:country/crude-humor-policy` が
`:prohibited` の国で、実際にどの放送局がどの作品をどの `:release/edit-status` で流したか」
という規制×配信の join が可能。

## DataScript での使い方

DataScript は Datomic と違い schema を `create-conn` に map で渡す必要がある
（`:db/valueType` は `:db.type/ref` 以外無視される）。tx vector 内の schema entity
から schema map を組み立ててから、data entity だけを transact する:

```clojure
;; nbb --classpath . で実行（npm i datascript 済みの前提）
(ns query-example
  (:require [datascript.core :as d]
            [clojure.edn :as edn]
            ["fs" :as fs]))

(def tx (edn/read-string
         (fs/readFileSync "90-docs/knowledge/childrens-content-crude-humor.datoms.edn" "utf8")))

(def schema-entities (filter :db/ident tx))
(def data-entities   (remove :db/ident tx))

(def schema
  (into {} (for [{:keys [db/ident db/valueType db/cardinality db/unique]} schema-entities]
             [ident (cond-> {}
                      (= valueType :db.type/ref) (assoc :db/valueType :db.type/ref)
                      (= cardinality :db.cardinality/many) (assoc :db/cardinality :db.cardinality/many)
                      unique (assoc :db/unique unique))])))

(def conn (d/create-conn schema))
(d/transact! conn data-entities)
```

## クエリ例

```clojure
;; Q1: 子供向け枠で下ネタが事実上不可 (:prohibited) の国
(d/q '[:find ?name ?regulator
       :where [?c :country/crude-humor-policy :prohibited]
              [?c :country/name-ja ?name]
              [?c :country/regulator ?regulator]]
     @conn)
;; => #{["アメリカ" "FCC…"] ["インド" "Ministry of…"] ["ベトナム" …] ["インドネシア" …]}

;; Q2: クレヨンしんちゃんが「子供向けでない」と扱われた国と扱い
(d/q '[:find ?name ?treatment ?detail
       :where [?w :work/id :crayon-shinchan]
              [?case :case/work ?w]
              [?case :case/treatment ?treatment]
              [(contains? #{:banned :adult-rated :publication-halted :restricted} ?treatment)]
              [?case :case/country ?c]
              [?c :country/name-ja ?name]
              [?case :case/detail ?detail]]
     @conn)

;; Q3: 規制根拠が宗教・道徳規範 (:religious-norms) を含む国
(d/q '[:find ?name
       :where [?c :country/policy-basis :religious-norms]
              [?c :country/name-ja ?name]]
     @conn)

;; Q4: 確度の低い (:medium) 事例 — 一次ソースを当たるべき行の棚卸し
(d/q '[:find ?id ?detail
       :where [?case :case/confidence :medium]
              [?case :case/id ?id]
              [?case :case/detail ?detail]]
     @conn)

;; Q5: 国の厳格度と、その国での実際の扱いの突合（政策→事例のjoin）
(d/q '[:find ?name ?policy ?treatment
       :where [?case :case/country ?c]
              [?c :country/name-ja ?name]
              [?c :country/crude-humor-policy ?policy]
              [?case :case/treatment ?treatment]]
     @conn)
```
