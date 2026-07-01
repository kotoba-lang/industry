# ADR-2607011200: cloud-itonami 成熟度ループ完了 — UIUX/export/テスト/設計システムの飽和

**Status**: accepted
**Date**: 2026-07-01
**Deciders**: Jun Kawasaki

## Context

ADR-2607011000 で cloud-itonami の robotics 前提と ISIC section coverage 21/21
を確立した後、15 分毎の /loop で「それぞれの成熟度を向上、実際に業務として
使える品質に、UIUX も設計実装、kotoba-lang/html, css などを使う」を反復した。

## Decision

成熟度向上を 8 層で完結させ、各層を全 11 ライブラリ(+ 26 cloud-itonami repo)
で飽和させた:

1. **コア API + 契約テスト** — 9 capability lib の純 cljc データ契約
2. **UIUX ダッシュボード** — `kotoba-lang/html`(Hiccup→HTML) + `kotoba-lang/css`
   (EDN→CSS) で読み取り専用 operator console。`<form>`/`<button>` を出さない
   (governor-gated、書き込みパスなし)
3. **UI 契約テスト** — render 契約 + 読み取り専用不変量
4. **エクスポート (CSV/JSON)** — RFC-4180 quoting + JSON エスケープ。
   card は PAN 最後4桁マスク、property はリース期間重複フラグ
5. **エクスポート契約テスト**
6. **README ドキュメント + Maturity 表** — 自己文書化
7. **共有 CSS デザインシステム** — `css.core/operator-theme` + `merge-theme`
   で 9x の重複 sheet を 1 つに集約
8. **エッジケーステスト** — 境界長/型エラー/多通貨/enum 拒否

さらに:
- **technology registry** に `:ui?`/`:export?` フラグ追加 →
  `industry/execution-plan` が `:ui-ready?`/`:export-ready?` を計算
- **cloud-itonami-{ISIC} 全 26 repo** にレンダリング済みサンプル HTML を埋め込み
  (業務側がブラウザで UIUX を検証可能)
- **6310** (参照実装) の robotics retrofit を完了 → 全 26 repo で robotics 前提 100%

## Consequences

- (+) 11 ライブラリ + 26 cloud-itonami repo 全層で業務利用可能品質が達成・検証済
- (+) テスト総数 110 / 363 assertions (capability + UI 基盤)
- (+) UIUX と export の readiness が `execution-plan` で機械可読 →
  cloud-itonami runtime が vertical ごとに console/export の有無を表示可能
- (+) CSS デザインシステム集約で DRY、設計の一貫性
- (−) 218 group spec entry は blueprint repo 未作成 (registry-only)。
  必要なら段階的に repo 化可能
- (−) cloud-itonami-* の多くは robotics 共通テンプレートのサンプル HTML。
  独自 UI を持つのは 9 capability lib に裏付けられた 8 vertical のみ

## Coverage & Maturity (closure snapshot)

- ISIC: section 21/21, group 238/238, class (Rev.4) 419/419 (+ Rev.5 6) = 425
- industry entries: 643 (425 class + 218 group)
- maturity: implemented 1 / blueprint 25 / spec 617
- ui+export ready: 643/643
- robotics 前提: 全 26 repo
- superproject: PR #182 merged (9 lib west pin + ADR-2607011000)

## References

- ADR-2607011000 (robotics 前提 + ISIC 21/21)
- kotoba-lang/{swift,banking,phone,card,retail,logistics,property,labor,robotics,html,css}
- 本 ADR とペアの `.edn`
