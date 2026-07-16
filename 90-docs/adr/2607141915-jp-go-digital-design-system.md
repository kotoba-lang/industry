# ADR-2607141915: jp-go-digital-design-system — デジタル庁 DADS の cljc 化と itad LP の light 固定移行

**Status**: accepted, implemented
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki

## Context

itad.gftd.ai の LP は kotoba-ui（`:appearance :auto`）で、OS が dark の閲覧者には
dark で描画されていた。オーナー指示（スクリーンショット付き）: **light mode に
固定**し、**デジタル庁デザインシステム（DADS）の HTML コンポーネント例
[digital-go-jp/design-system-example-components-html](https://github.com/digital-go-jp/design-system-example-components-html)
（MIT, © 2025 デジタル庁）をベースに cljs の design system を作り、この website は
そちらを使う**。

## Decision

1. **新規 repo `kotoba-lang/jp-go-digital-design-system`（public）**:
   - `jp-go-dds.core` — button / heading / accordion / input-text / textarea /
     checkbox / form-control-label / table / chip-label / divider を、上流の
     markup・`dads-*` class に忠実な cljc hiccup で提供。
   - CSS は**上流を vendor**（`resources/jp_go_dds/dds.css`、冒頭に upstream
     commit `3b34f4c3` + MIT 表記。`scripts/vendor.sh` で再生成 — 手編集禁止。
     `LICENSE-upstream` 同梱）。
   - 上流に無い layout 補助（container/section/grid/stack/card/hero）は
     **`dds-ext-*` prefix** で明確に区別（上流 class 名前空間を汚さない）。
   - `jp-go-dds.page` — **light mode 固定**（`color-scheme: light` meta+CSS。
     上流に dark palette は無い）。既定で**外部リクエストゼロ**
     （Noto Sans JP の Google Fonts は `:google-fonts? true` の opt-in）。
     描画は kotoba-lang/html。
2. **itad LP を DADS に移行**（kotoba-ui / uikit / liquid-glass 依存を LP から
   除去）。**kotoba-uiux 規約（ADR-2607122200）からの opt-out 理由**:
   オーナー指示 + 行政手続き文脈のサービス（廃棄物処理法・監査証跡）として
   官公庁デザイン言語の信頼感に合わせるため — persona run 20260714-0700 の
   最弱軸 trust への追加打ち手でもある。
   - art.cljc（SVG イラスト）は無変更 — 参照する `--hig-*` 変数を app CSS で
     DADS token に**橋渡し**（イラストがデジタル庁ブルーに自動追従）。
   - コピー（itad.edn）・フォーム JS・マーケタグ規約・追従 CTA は addendum 2 の
     設計を維持。
3. kotoba-uiux 標準スタックとの関係: 本ライブラリは「日本の公共・行政文脈
   サービス向けの明示的 opt-out 先」。採用 repo は理由を ADR に書く（README にも明記）。

## 実測知見（このセッションで live が捕まえた分）

- `html.core` は `style`/`script` を raw-text tag として無エスケープ出力する —
  子を `[:hiccup/raw …]` で包むと**ベクタごと文字列化されて CSS/JS が壊れる**
  （初回描画が完全無スタイルになる実測）。子は素の文字列で渡す。
- design-quality audit は light 固定でも `color-scheme` の CSS 宣言・
  tap-targets 44px・safe-area 全辺を要求 — DADS 素のままでは 81.97、
  app CSS 3 行の補正で 100.00。

## Verification

- lib: 5 tests / 21 assertions green（nbb）。itad: 11 tests / 35 assertions green。
- design-quality audit **100.00**。
- live（version `6c47f244`）: CDP mobile capture で白背景（rgb 255,255,255）・
  デジタル庁 key blue の CTA・DADS テーブル/アコーディオン/フォームの描画を確認。

## Consequences

- (+) OS 設定に関わらず light で安定描画（dark 見え問題の根治）。
- (+) DADS 準拠の見た目は官公庁文脈での信頼感に直結（trust 軸の次回 persona 計測で検証する）。
- (−) 上流 CSS の更新は vendor 再実行で追従（自動追従はしない — 意図的）。
- (−) kotoba-ui と DADS の二本立てになる repo が今後増えうる — opt-out 理由の
  ADR 記載を必須とすることで乱立を抑える。

## Follow-ups

1. persona panel 再実行（DADS 化 + light 固定の trust/readability への効果測定）。
2. 必要に応じて DADS コンポーネント追加 vendor（select / radio / date-picker 等）。
3. Noto Sans JP のセルフホスト検討（外部リクエストゼロのままフォント忠実度を上げる）。
