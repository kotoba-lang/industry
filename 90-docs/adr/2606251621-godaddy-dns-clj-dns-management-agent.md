---
id: adr-2606251621-godaddy-dns-clj-dns-management-agent
title: "ADR-2606251621: godaddy-dns-clj — GoDaddy DNS を AI エージェントがターミナルから管理（dry-run 既定・datom 監査）"
status: accepted
doc_type: adr
topic: agent-tooling
authoritative: true
last_verified: 2026-06-25
implemented: 2026-06-25
implementation:
  repo: com-junkawasaki/godaddy-dns-clj
  submodule: orgs/kotoba-lang/godaddy-dns
  pinned: e279abf
  landed_via: "com-junkawasaki/root main fast-forward 4bbfe812（GitHub Data API server-side commit）"
authoritative_for:
  - GoDaddy DNS を操作する AI エージェントを com-junkawasaki に OSS 部品として新設する判断
  - DNS API を注入される host capability（IDns）として扱い、実 GoDaddy 実装と mock を分離する設計
  - 破壊的な DNS 書き込みを dry-run 既定 + datom 監査ログで安全化する方針
  - 既存 clj スタック（langchain-clj → langgraph-clj → *-use-clj）への載せ方
related:
  - orgs/kotoba-lang/langchain       # 0-dep foundation + Datomic-compat store
  - orgs/kotoba-lang/langgraph       # StateGraph / checkpoint / create-react-agent
  - orgs/kotoba-lang/computer-use    # IComputer host capability（直接のテンプレート）
  - orgs/kotoba-lang/browser-use     # IBrowser host capability（姉妹）
  - 90-docs/adr/2606250956-browser-agent-clj-genspark-style-super-agent.md
supersedes: []
superseded_by: []
---

# ADR-2606251621: godaddy-dns-clj — GoDaddy DNS を AI エージェントがターミナルから管理

**Status**: accepted — **実装済み・main マージ済み**（2026-06-25）
**Date**: 2026-06-25
**Deciders**: Jun Kawasaki

> **実装サマリ（2026-06-25）**: `com-junkawasaki/godaddy-dns-clj` を新設し（MIT, public,
> init `e279abf`）、superproject `root` に submodule 登録（`orgs/kotoba-lang/godaddy-dns`,
> pin `e279abf`）。`.cljc` で IDns capability + 実 GoDaddy 実装 + dry-run ゲート + datom
> 監査ログ + langgraph ループを実装。**テスト 8 件 / 38 assertions / 0 failures**（mock のみ、
> ネット・鍵不要）。ローカル Ollama での end-to-end も起動確認。詳細は末尾「実装状況」。

## Context

「GoDaddy の DNS をこのターミナルから AI エージェントで管理し、それを kotoba-clj
（＝本 org の `*-clj` ファミリー）で設計・実装し、OSS として公開したい」という要望。

調査の結果、要望の本質は **既存 clj スタックの一貫パターン**（langchain-clj →
langgraph-clj → computer-use-clj / browser-use-clj）にそのまま乗る:「ドメイン能力を
**注入される host capability protocol** + **mock 実装** で表現し、**langgraph
StateGraph のサンプリングループ**で駆動し、**操作履歴を datom 監査ログ**として残す」。
本 repo は GoDaddy Domains API をその capability（DNS）として実装する、最も
computer-use-clj に近い形になる。

GoDaddy 固有事情として **DNS 書き込みは破壊的**（A レコード誤更新でサイト断）であり、
既定を安全側（計画のみ）に倒す必要がある。

## Decision

com-junkawasaki に新規 OSS submodule **`godaddy-dns-clj`**（MIT, public）を新設。
computer-use-clj をテンプレートに、以下を `.cljc`（JVM / SCI / cljs / kotoba-clj-WASM
で動く）で実装する。

### 1. DNS = 注入される host capability（IDns）

`godaddydns.dns/IDns`（`-list-domains` / `-list-records` / `-upsert-records!` /
`-delete-records!`）。

- `godaddydns.godaddy/godaddy-dns` — 実 GoDaddy 実装。HTTP/JSON は注入された
  `:http-fn` / `:json-read` / `:json-write` のみ使用し **第三者依存ゼロ**を維持。
  認証 `sso-key KEY:SECRET`、`:base` で prod / OTE 切替。
- `godaddydns.dns/mock-dns` — 決定的インメモリゾーン。鍵・ネット不要で
  テスト/デモを成立（computer-use-clj の `mock-computer` 相当）。

### 2. GoDaddy セマンティクスを忠実に写す

`PUT /v1/domains/{domain}/records/{type}/{name}` は (type,name) のレコード集合を
**丸ごと置換**する。`-upsert-records!` がこの置換契約を実装し、mock と実ホストの
挙動が一致（テストで保証）。

### 3. 書き込みは dry-run 既定（安全不変条件）

`godaddydns.tool` の write ツール（`upsert_record` / `delete_record`）は `dry-run?`
ゲート: 真（既定）なら **IDns を呼ばず** plan に積み `PLANNED: …` を返す。`:dry-run false`
明示時のみ適用し `APPLIED: …`。read ツールは常に実行。`agent/run` は `:plan`（計画変更）
と `:applied?` を返す。「鍵・権限なしでも計画まで安全に試せる」。

### 4. サンプリングループ + datom 監査ログ

`godaddydns.agent` — langgraph StateGraph（`:agent ⇄ :tools`、`done` 終端、
`:recursion-limit`）。`:history-conn` で全ツール呼び出しが datom 化
（`:dnsaction/{tool,domain,record,input,result,applied?}`）→ Datalog で照会可能。

### 5. ターミナル入口

`examples/dns_agent.clj`（`clojure -M:examples -m dns-agent "<task>"`）。GODADDY_* 未設定
なら mock ゾーンにフォールバック、`DRY_RUN`（既定 true）、`LLM`（ollama/gemini/anthropic）。
`examples/jvm_host.clj` が `java.net.http` の `:http-fn` + JSON + model 切替を提供。

### 6. 非スコープ

- 他 DNS プロバイダ（Cloudflare / Route53 等）— 将来別 IDns 実装で対応可能だが本 repo は
  GoDaddy に集中。
- ドメイン購入・移管・証明書 — レコード管理に絞る。

## 論点の決着

1. **repo 名 / スコープ** → **`godaddy-dns-clj`（GoDaddy 専用）**に決定（owner）。
   プロトコル自体はプロバイダ非依存だが実装は GoDaddy に集中。
2. **書き込みの既定挙動** → **dry-run + datom 監査ログ**を既定（owner）。
3. **公開先** → **com-junkawasaki org に public 新規 repo + root submodule**（owner）。
4. **main への載せ方** → ローカルブランチが shallow かつ origin/main と乖離、先行コミットが
   無関係な kotoba ポインタ bump だったため、CLAUDE.md 方針どおり **GitHub Data API で
   main の tree をベースにクリーン commit を起こし fast-forward**（`4bbfe812`）。shallow
   ancestry の誤判定・dirty submodule への影響・破壊的マージを回避。
5. **ライセンス** → ファミリーに合わせ **MIT**（plan 当初の Apache-2.0 から変更）。

## 実装状況（2026-06-25）

- **repo**: `com-junkawasaki/godaddy-dns-clj`（MIT, public, init `e279abf`）。
- **submodule**: `orgs/kotoba-lang/godaddy-dns` を `root` に登録、pin `e279abf`
  （main fast-forward `4bbfe812`、GitHub Data API server-side commit）。
- **実装名前空間**: `godaddydns.{dns,godaddy,tool,agent}`（全 `.cljc`）+
  `examples/{jvm_host,dns_agent}.clj` + `docs/adr/0001-architecture.md` + CI。
- **検証**: `clojure -M:test`（公開 git deps）/ `-M:dev:test`（local checkout）とも
  **8 tests / 38 assertions / 0 failures**（mock のみ、ネット・鍵不要）。dry-run（計画のみ・
  ゾーン不変・datom ログ）と live（適用・`applied? true`）を end-to-end でアサート。ローカル
  Ollama 駆動の CLI も起動確認（小型 `gemma4:e4b` は tool-calling が遅くタイムアウトしたが
  コードは健全＝モデル性能の問題）。
- **未実装（次段）**: 他プロバイダ IDns 実装、CI を実運用に乗せる、GoDaddy 書き込み API の
  アカウント条件下での実適用検証（OTE → prod）。

## Consequences

- **＋** 既存スタックの一貫パターンに完全に乗り、新規は薄い GoDaddy capability + DNS ツール
  + dry-run ゲートに集約。鍵・ネットなしで全ループをテスト/デモ可能（mock + dry-run）。
  破壊的な DNS 変更を既定で安全化し、全操作を datom で監査・time-travel できる。
- **－** GoDaddy 書き込み API はアカウント条件（保有ドメイン数等）で制限される場合があり、
  実 prod 適用は利用者の権限に依存。実適用の e2e 検証は次段。
- **中立** HTTP/JSON/LLM/DNS はすべて host capability 注入として抽象化済みで、実体（java.net.http /
  data.json / Anthropic|Ollama / GoDaddy）は利用側が差す。ライブラリ本体は純粋 `.cljc` を維持。