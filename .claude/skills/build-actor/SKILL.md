---
name: build-actor
description: Pattern and conventions for building a new "actor" in this workspace (an LLM/advisor node contained behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger, west/RAD registration), plus the CACAO self-mint auth convention actors use to authenticate to kotoba-server. Use when creating or extending an actor-pattern repo, or asked about the Actors pattern.
---

## Actors（langgraph-clj StateGraph アクター）

ドメインを「actor」として作るときは、既存3例の同型パターンに揃える:
**robotaxi-actor**（AR1 ⊣ SafetyGovernor）/ **gftd-talent-actor**（HR-LLM ⊣
PolicyGovernor）/ **cloud-itonami**（ops-LLM ⊣ CertGovernor）。

> 2026-07-04 追記: 上記3例目は当初 standalone repo `gftdcojp/ai-gftd-itonami` として
> 計画されていたが、実際にはその repo は作成されず（GitHub 上に実在しない・ローカルの
> 空 placeholder checkout も削除済み）、実装は `gftdcojp/cloud-itonami` 本体の
> `itonami`/`cloud_itonami.edge.*` namespace にそのまま統合された。以下の CACAO 手本
> パスも `cloud-itonami` 側を参照する。

- **封じ込め + 独立 governor + 不変台帳。** 知能ノード（LLM/研究モデル）を1ノードに
  封じ込め *proposal のみ* 返させ、別系統の Governor が検閲して 可決/拒否/人間承認 に
  振る。単一不変条件「**governor が拒否する 書込/開示/作動/認証 を actor は決して
  行わない**」。全 commit/hold を append-only の監査台帳に積む（台帳＝データ主権/
  トレーサビリティの核）。
- **langgraph-clj StateGraph。** 1 run = 1 操作（無限内部ループ無し）。`interrupt-before`
  を human-in-the-loop（承認/テレオペ/耐空性サインオフ）に転用。checkpoint で監査可能。
  長期耐久 loop が必要な kotoba code / Claude Code 型 agent は、StateGraph 内で回さず
  **durable outer loop**（lease / tick / budget / governor / crash recovery）で有界 run を
  反復する。継続状態は `:checkpoint/*` と `:agent.loop/*` / `:agent.tick/*` /
  `:agent.lease/*` / `:agent.budget/*` / `:agent.event/*` datom に分離して積む。
- **注入境界（swap）。** Store（`MemStore` ‖ `DatomicStore`）/ Advisor（mock ‖ 実LLM=
  `langchain.model`）/ Phase（0→3 段階導入）を注入で差し替え、コアは不変。
- **Store は `:db-api` 駆動。** backend へは langchain.db の `{:q :transact! :db :pull
  :entid}` マップ越しにのみ喋る。`langchain.db/api`（in-process）と
  `langchain.kotoba-db/kotoba-api`（kotoba-server XRPC）が同マップを実装するので、
  同一 record が in-mem / 実 Datomic / kotoba pod を選ばず動く（contract test で
  `MemStore ≡ DatomicStore` を保証）。直呼びせず必ず `:db-api` を介す。
- **Store の共通機構は `kotoba-lang/langchain-store` を使い、自前でハンドロールしない
  （ADR-2607141600）。** EDN-blob コーデック（`enc`/`dec*`）・`:db.unique/identity`
  schema・seq-keyed event-log の read/append・entity の `map<->tx<->pull` は
  `langchain-store.core` に集約済み。新規 store はこれを require し、
  `(defn- enc [v] (pr-str v))` 等の**自前コピーを書かない**（この 2 行は既に 190 repo
  に complete-identical で複製されている＝止めるべき複製源）。使い分け:
  - event-sourced store: `ls/identity-schema` + `ls/read-stream` / `ls/append-blob!`
    （手本 `cryptoexchange.store`）。
  - entity store: entity ごとに field-spec `{logical-key {:attr :ns/attr :blob?
    :default :coerce}}` を書き、`ls/map->tx` / `ls/pull->map` / `ls/pull-pattern` で
    駆動（手本 `underwriting.store` = 6511、reference entity adopter）。domain 固有の
    field 列だけが per-store のデータになる。
  - deps に `io.github.kotoba-lang/langchain-store {:local/root
    "../../kotoba-lang/langchain-store"}` を足す。既存の hand-rolled store は
    「触るついでに漸進移行」（一括書き換えはしない）。
- **deps / lint / test。** `io.github.kotoba-lang/langgraph
  {:local/root "../../kotoba-lang/langgraph"}` ＋ `:dev` で langchain を
  override（手本 `gftdcojp/gftd-talent-actor/deps.edn`）。`clojure -M:lint`（clj-kondo・errors fail）/
  `clojure -M:dev:test`。`.cljc` は `edn`/`Exception` を `#?(:clj/:cljs)` 条件化して
  JVM/cljs/WASM 可搬に保つ。
  - ⚠ **`cloud-itonami/deps.edn` に残る `io.github.com-junkawasaki/langgraph-clj`
    （旧 artifact ID・`:local/root` は新パス）を「古いから」と消さない。** 依存の一部が
    その ID で宣言しており、tools.deps は同一 lib を別 ID・別 coordinate 種別で
    掴むと全 alias の classpath 構築に失敗する。旧 ID は shallowest 宣言として
    意図的に残してある（同 deps.edn の madoguchi 節のコメントが理由）。
    **新しい actor は旧 ID を持ち込まない**が、既存の旧 ID を掃除もしない。
- **west / RAD 登録。** 新 actor workflow は `20-actors/{name}` に実装を置くだけで完了
  しない。actor 単位 repo `etzhayyim/com-etzhayyim-{name}` を作り、
  `orgs/etzhayyim/com-etzhayyim-{name}` として west に登録し、RAD identity 台帳にも
  同じ actor identity を登録するまでを完了条件にする。west は `manifest/repos.edn` を
  SSoT とし、GitHub API の単一 entry クリーン commit で登録 / pin 前進する
  （`manifest/west.yml` は生成物、手書き禁止）。diff は当該 entry のみ、
  `nbb scripts/gen-west-manifest.cljs --check` と **pin == repo HEAD** を確認。RAD は
  etzhayyim/root の `80-data/kotoba-rad/{name}.identity.journal.edn`（または同等の
  RAD identity ledger）に `:rad/repo "github.com/etzhayyim/com-etzhayyim-{name}"`、
  `:rad/did-web "did:web:etzhayyim.github.io:com-etzhayyim-{name}"`、署名 /
  attestation 参照を積む。`20-actors/{name}` だけに存在する actor は **未分離** と扱い、
  child repo 作成 → west entry → RAD identity の follow-up を残す。

### kotoba-server（kotobase.net）= actor が自分の鍵で CACAO を自己発行

- 認証は **CACAO**（SIWE/EIP-4361 を Ed25519 did:key で署名、kotoba-auth
  DelegationChain）。**actor ごとに鍵を発行**し、その**鍵由来 IPNS 名がその actor の
  graph**（`kotoba/write.cljs`: *AUTHORITY は鍵由来 IPNS 名への署名であってサーバでは
  ない*）。actor は鍵を持つことで自分の graph の owner → depth-1 の自己 mint が
  構造的に authorized。**owner hand-off も共有 token も要らない**（「token をもらう／
  owner が grant する」前提は誤り）。
- **CACAO の実装は書かない。`kotoba-lang/org-chainagnostic-cacao` に依存する**
  （ADR-2607268000。GitHub 上の旧名 `kotoba-lang/cacao` は同一 repo への redirect）。
  JVM actor は `cacao.core`（mint / verify）、edge/Worker は `cacao.edge.mint` /
  `cacao.edge.verify`。did:key(0xED01+base58btc → `z6Mk…`)・SIWE/EIP-4361 平文の
  再構成・CBOR envelope・Ed25519・temporal window はすべてそこにあり、**他のどこにも
  あってはならない**。

  ```clojure
  ;; deps.edn
  io.github.kotoba-lang/org-chainagnostic-cacao
  {:git/url "https://github.com/kotoba-lang/org-chainagnostic-cacao.git"
   :git/sha "b395935018d97ee281c9b4564f855c8242b438e7"}
  ```

  **旧 手本（`cloud-itonami/src/cloud_itonami/edge/cacao.cljc` を写す）は廃止。**
  この skill がそれを指していた結果、`<actor>/cacao.clj` が約 25 repo に複製され、
  しかも「keep in sync」コメント付きのまま**実際に乖離した**（実測 2026-07-26:
  `denrei` は共有 `ed25519.core`/`ipns.core` を使い multi-cap `grant->resources` を
  持つが、`gijiroku` は JDK Ed25519 と最小 CBOR を自前で持ち multi-cap が無い）。
  手で同期する取り決めは機能しない。**新しい actor でこれらを写さない。**
  既存コピーを持つ repo を触る機会があれば、その時に上記依存へ寄せる。

- 鍵の扱いは actor 側の責任として残る: `load-or-create-identity!` 相当で初回生成→
  永続→再読込し、**秘密鍵は `.<actor>/identity.edn` に置き gitignore（git に絶対
  コミットしない）**。鍵由来 IPNS 名は `ipns.core`（`k51qzi5uqu5d…`）。
  `kotoba-store {:identity me}` で graph 既定＝鍵由来 IPNS ＋ 自己 mint。
  設定参照は `manifest/repos.edn` の `:kotoba`。
