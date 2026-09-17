---
name: declconf
description: >
  誤った setting や脆弱性対策を「後で宣言的に評価する」ための web-service 設定
  コード化 lib / repo の設計提案（2026-09-15）。
---

# declconf — web-service 設定の宣言化と宣言的評価（提案、未承認）

オーナー要求: kotoba-lang で IaC 的に webservice の設定をコード化し、誤った
setting や脆弱性対策のために後で宣言的に評価できる lib / repo。

## 実測した前提（提案の出所）

- 対象設定の現在地: root repo の wrangler 設定は **542 件**
  （network-awai 配下 37 件、`find orgs -name 'wrangler.json{,c}' -o -name 'wrangler.toml'`、
  除外 dir あり）。**「集める」のではなく、これ自体が 1 設定 corpus である。**
- 同型の先行検査が**実在する**: `scripts/verify-awai-state-store-policy.cljk`
  （kbb + 137 行、fail-closed、SCANNED 行、exit 0/1/2、gate
  `root-awai-state-store-policy`）。**declconf はその一般化であって新種ではない。**
- 同じ検査の 8 問の実装が `AGENTS.md` の repo-wide mandatory 節に在る。
- `manifest/repository-rules.edn` には機械可読の workspace policy が既に 4 面ある。
- 決定の正本は **ADR（`.kotoba`）** で、new-project-scaffold に standing authorization
  （scaffold → GitHub repo → manifest 登録は都度確認なしで実行してよい）。

## 設計（4 層）

1. **declconf-model** — 設定 1 件を EDN にする。スキーマは個別に宣言し、
   未知 shape は黙って nil にしない（edn-query ローダの反例）。
2. **declconf-facts** — 設定 1 件が policy 要求を満たすか述語化。
   属性名は datom 面の慣習に合わせ、出自を属性に埋め込まない。
3. **declconf-policy** — 設定値の制約と、**規則の性質 / 実装状態の区別**
   （ADR-2809041200）を載せる。譲れない不変条件は fail-closed。
4. **declconf-eval** — 実測設定 × policy の全束が分かる評価器。
   判定語彙は UNSPECIFIED / SATISFIED / VIOLATED / UNVERIFIED。
   理由を記録しない「合格」は合格と扱わない。

## 検査の原則（検査の 8 問を直接引用）

- 0 件走査は緑にしない（config が 1 件も無い tree は clean ではなく未測定）。
- 読めない設定・jsonc parse 失敗は fail。
- 走査件数を出力する。
- 規則が禁止するとき、**規則の literal を名指す**（単に「違反」を出さない）。

## 名付け

- **どの面でもない。** 出所ドメインを持たない（generic toolchain であって
  他者の仕様の移植でない）、実行 role でも family でもない。だから subject 面
  （bare 名）。
- 候補: `declconf`（宣言的設定）/ `tenken`（点検）/ `yakkan`（約款、ポリシー約束）。
- **メタファ名なら README 冒頭の名乗り + `manifest/concept-vocabulary.edn` 登録**。
- repo は kotoba-lang 配下に 1 本。portable `.cljc`（runtime: wasm > cljs > nbb >
  JVM）。検査は kbb script。

## 実装段階（2 步ある。1 步目は scaffold のみ）

1. scaffold + 最小 model（wrangler 設定の EDN 化 + policy 1 種）。
2. 実在 542 設定 × awai-policy で走らせて、実測 VIOLATION が awai checker の
   現行値と一致することを確かめる。

## 罠

- **既存 repo を「無い」と結論しない。** repo-search / concept-lookup を先に引く。
  実測: 同型の先行検査が root scripts に居た。
- **設計と名前だけ返して「動作確認」を済ませない。** 設計提案の完了条件は
  「A. 提案（名前・4 層）」と「B. 最小 scaffold を実際に着地させる」の 2 段。
- **検査の 8 問を検査の実装に落とす前に読む。** 8 問はスローガンでなく実装パターン。
- **graceful に降格させない。** 宣言が無ければ finding のまま残る。
  既定値で緑にしない。
