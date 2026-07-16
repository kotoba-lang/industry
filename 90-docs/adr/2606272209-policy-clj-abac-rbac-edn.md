---
id: adr-2606272209-policy-clj-abac-rbac-edn
title: "ADR-2606272209: policy-clj — ABAC/RBAC 認可ポリシーを EDN/Clojure データとして扱う再利用ライブラリ。model(id-keyed roles + rule vector) + validate + 純粋 decision engine(IAttribute port)。combining algorithm(:deny-overrides/:allow-overrides/:first-applicable)。third-party dep ゼロの portable .cljc"
status: proposed
doc_type: adr
topic: library-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - ABAC/RBAC 認可ポリシーを Clojure で第一級の EDN データとして表現する正準モデルの定義
  - policy-clj の責務境界(モデル/検証/ports/execute)と host-injected IAttribute port の設計
  - combining algorithm(:deny-overrides/:allow-overrides/:first-applicable)の意味論と実装
  - 認可カーネルの3-org 配置(共通=com-junkawasaki / 公益=etzhayyim / 事業=gftdcojp)
related:
  - orgs/kotoba-lang/policy        # 本 ADR のライブラリ
  - orgs/kotoba-lang/authenticator # 認証(who) — policy-clj は認可(what)を担う相補関係
  - orgs/com-junkawasaki/ghosthacker       # リソースガードのユースケース(ports を注入する側)
  - orgs/kotoba-lang/org-omg-bpmn          # host-injected ports パターンの先例(IActivity/ICondition)
  - orgs/kotoba-lang/org-omg-dmn           # 同型の純粋評価カーネル(IExpression/IUnary)
supersedes: []
superseded_by: []
---

# ADR-2606272209: policy-clj — ABAC/RBAC 認可ポリシーを EDN で扱う再利用ライブラリ

**Status**: proposed（実装済み・テスト緑。ports を注入する actor 側は別 PR）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

認可(Authorization)の需要は authenticator-clj が解決する認証(Authentication)とは
別の関心事だが、両者を結びつける再利用可能な EDN カーネルが不在だった。既存の
認可フレームワーク(Casbin, OPA/Rego, AWS IAM JSON)は (1) 外部プロセスや重い JVM
依存を必要とし (2) ポリシーを Clojure データとして `assoc`/`diff` できず (3)
SCI/WASM ホストで動かない。本リポの方針(shallow 既定・portable `.cljc`・
host-injected ports — koe-clj / bpmn-clj / dmn-clj の先例)に沿う、
**ポリシーを素の EDN として扱う軽量ライブラリ**が必要だった。

authenticator-clj が「誰であるか(who)」を確立し、policy-clj が「何をして良いか(what)」
を決定する。ghosthacker のリソースガード等でこの2ライブラリが組み合わせて使われる
ことを想定している。

## Decision

`com-junkawasaki/policy-clj` を新設する。**third-party 実行時依存ゼロ**、全
namespace `.cljc`(JVM/CLJS/SCI)。責務を4層に分離する:

- **`policy.model`** — ABAC/RBAC ポリシーの正準 EDN モデル。ルールは **vector**
  (`:first-applicable` で宣言順が重要)、ロールは id-keyed map(O(1) 参照)。条件は
  再帰データ — 葉(`:policy/attr`/`:policy/op`/`:policy/value`)とコンビネータ
  (`:policy/and`/`:policy/or`/`:policy/not`)で構成。threadable builder
  (`policy`/`allow`/`deny`/`role`)と条件コンストラクタ(`leaf`/`and-cond`/`or-cond`/
  `not-cond`)。ops: `:=` `:!=` `:<` `:>` `:<=` `:>=` `:in` `:contains`。
- **`policy.validate`** — 構造検証。`{:policy/severity :policy/code :policy/id :policy/msg}`
  の vector を返す純関数。error(unknown algorithm / unknown effect / unknown op)を分離、
  `valid?` は error 無しで真。警告は未定義 condition shape。
- **`policy.ports`** — host-injected ports。`IAttribute`(resolve: attr-path + request →
  value)のみ。attr-path は dotted string("user.role" → `[:user :role]` → 値)。
  ホストは JWT claims map / LDAP / DB row など何でも差し込める。
- **`policy.execute`** — **純粋 decision engine**。`decide` は ports + policy + request
  → `{:policy/decision :allow|:deny|:not-applicable :policy/by rule-id-or-nil}`。
  条件評価(葉: IAttribute で解決 → op 適用; コンビネータ: 再帰)、combining algorithm
  (`:deny-overrides`: deny 優先; `:allow-overrides`: allow 優先; `:first-applicable`:
  宣言順で最初に適合したルール)。RBAC ヘルパー `permits?` は `:policy/roles` の
  静的テーブルをポート不要で参照。`default-ports` は dotted path を keyword→string 順で
  request map から辿る実装。

## Rationale

- **データ第一**: ポリシーが EDN なので生成・差分・バージョニング・Datomic 格納が自明。
  JSON/YAML の不透明な文字列や外部 DSL を持たない。
- **依存ゼロ × 可搬**: OPA(wasm + Go バイナリ)や Casbin(Java 依存)の重さを避ける
  本リポ方針と、SCI/WASM ホストでの実行要件を両立。
- **host-injected ports**: bpmn-clj(IActivity/ICondition)・dmn-clj(IExpression/IUnary)
  と同じパターン。カーネルは属性ストア・認証プロバイダ・式言語を持たず、意思決定
  ロジックだけが純粋に残る → オフラインで fixture port によりテスト可能。
- **authenticator-clj との補完**: authenticator-clj が principals を確立し、policy-clj
  がその claims を受け取って認可判定を下す。2ライブラリは deps として並列に使われる
  想定で、policy-clj は認証に一切依存しない(request はただの EDN map)。
- **ghosthacker との連携**: ghosthacker のリソースガードが ports を注入して実際の JWT
  claims を IAttribute に橋渡しするユースケースが primary ターゲット。

## Consequences

- `policy.ports/resolve` が `clojure.core/resolve` を JVM でシャドウする(警告のみ、
  機能上は無問題。`p/resolve` として完全修飾で呼ぶため衝突しない)。
- OR-join の semantics はなし(ABAC 文脈で不要)。`:policy/or` はコンビネータとして
  条件内でのみ機能する。
- combining algorithm が未知の場合、validate がエラーを返し decide は
  `:not-applicable` を返す(fail-closed ではなく、呼び出し側が validate を通すことを
  前提とする)。
- 最初の消費者は別 org(公益=etzhayyim / 事業=gftdcojp)の actor が ports を注入して
  実際の属性ストアと接続する(本ライブラリにドメインは入れない)。

## Verification

`clojure -X:test` 緑(12 tests / 27 assertions)。allow/deny 各 op、deny-overrides /
allow-overrides / first-applicable の3アルゴリズム、:in / :contains op、
:and/:or/:not ネスト、RBAC permits? true/false、not-applicable、不明 op の
validate エラー、不明 algorithm の validate エラーを確認。
