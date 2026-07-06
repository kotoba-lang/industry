# ADR-2607061930: `kotoba-lang/kotoba-document-contracts` — htmldom/cssom を aiueos の mainuiux でも使えるよう共通 stub 契約を新設

**Status**: accepted — landed
**Date**: 2026-07-06
**Deciders**: Jun Kawasaki
**Scope**: 新規 repo `orgs/kotoba-lang/kotoba-document-contracts`（stub のみ）。既存の `kotoba-lang/htmldom`/`kotoba-lang/cssom`/`kotoba-lang/browser`/`kotoba-lang/aiueos` は無変更。

## Context

`kotoba-lang/browser`（ADR-0001 の kotoba-only・WASM-only document runtime）が
消費する `kotoba-lang/htmldom`（HTML parser+DOM）と `kotoba-lang/cssom`（CSS
cascade+layout）は、オーナーの指摘どおり **既に `browser` への直接コード依存を
持たない、素の `.cljc`**（`deps.edn` の唯一の依存は `kotoba.wasm.dom` 基盤である
`dom-gpu` のみ）。したがって `aiueos`（capability-secure な Wasm-component OS、
`orgs/kotoba-lang/aiueos`）が自前の system UI 層（オーナーが `mainuiux` と呼ぶ、
現時点でコード上の実体はまだ無い新規概念）を持つ際、この parse→cascade→
layout→draw-ops パイプラインを再利用できる可能性がある。

ただし `aiueos` の checkout は今日時点で **実際に Rust crate**（`Cargo.toml`/
`src/`）であり、`htmldom`/`cssom` は JVM Clojure + ClojureScript（shadow-cljs
経由）で動く — ランタイムが異なるため `aiueos` 側が `htmldom`/`cssom` の
**実装（アルゴリズム）そのもの**を直接実行することは今日時点でできない。

**訂正（オーナー指摘、初稿時の誤認）**: 初稿では「aiueos は Rust の
Wasm-component OS」であり将来の mainuiux 実装も「Rust/Wasm」になると書いたが、
これは `aiueos` 自身が明文化している方向性と矛盾する誤認だった。
`kotoba-lang/kotoba-lang` の `ADR-safe-capability-language.md` は
**self-hosting** の方針を明記する：意味論の正本を Rust から `.kotoba`
（safe Kotoba、Clojure 形の capability-safe 言語）ソースへ段階移行し、
Wasm component としてコンパイル・実行する。Rust は **恒久的に** adapter/
bootstrap 専用（意味論の正本にしない）。`kotoba-lang/kotoba` は自身の
compiler path で既にこの移行を完了済み — 旧 Rust `kotoba-clj` compiler を
撤去し、JVM Clojure（`com.dylibso.chicory` 経由で Wasm 実行）に置き換えた
実例がある。したがって将来 mainuiux レンダラーが実装されるとすれば、それは
**`.kotoba`（safe Kotoba）ソース、Wasm component としてコンパイル**される
ものであるべきで、Rust ではない —— ただし `aiueos` 自身の checkout が
「pure `.kotoba` wasm」に到達しているのはまだ先（今日は Rust crate のまま）
であることも同時に正直に記す。

この org には既にこの種の断絶を埋める確立された規約がある: `kototama` 自身の
README が明言する **「新しい振る舞いはまず CLJC/EDN contract として着地させ、
native host は自分の repo で独立にその contract に適応する」**という方針、
および `kotoba-core-contracts`/`kotoba-adapter-contracts`（「launcher/adapter は
意味論を所有しない」）という既存の stub-contract 群。本 ADR はこの規約を
htmldom/cssom の出力データ形状（DOM tree / resolved style / draw-ops）に適用する。

## Decision

新規 repo `kotoba-lang/kotoba-document-contracts` を作る。**stub のみ**
（parser・cascade engine・layout algorithm は一切含まない）:

- `kotoba.document.dom` — `kotoba.wasm.dom` が既に生成し `htmldom` が既に
  パースする、trusted-subset DOM tree node 形状（`:node/id`/`:tag`/`:attrs`/
  `:children` の element node、`:node/id`/`:text/content` の text node）。
- `kotoba.document.style` — `cssom` の cascade が既に生成する resolved-style
  map 形状（フラットな CSS property keyword → 解決済み値のマップ）。
  `known-properties` allow-list は「CSS 全体」ではなく「この実装が今日
  実際にサポートする集合」を正本とする。
- `kotoba.document.draw-ops` — `cssom.layout` が既に emit し、`dom-gpu` の
  両 paint host（`webgl.cljs`/`webgpu.cljs`）が既に consume する draw-ops
  語彙（`:rect`/`:text`/`:clip`/`:node`）。将来の `aiueos` 側レンダラーに
  とって最も直接再利用価値が高い層 —— この形状さえ満たせば、どのランタイムが
  描画したかに関わらず既存の htmldom→cssom パイプラインの出力を消費できる。

`kotoba-core-contracts`/`kotoba-adapter-contracts` と同じ命名規約
（`kotoba-<domain>-contracts`）に揃えた。`aiueos-cljc-contract`（既存の
scaffold-only stub）は KAMI clj-wgsl migration Phase 4 用に既に割り当て
済みのため転用しなかった。

## Non-goals（= 「capability・secure OS・wasm browser として不要な機能」の
scoping 根拠）

このセッション中にオーナーから問われた問い ——
「aiueos, kotoba が目指す capability, secure os, wasm browser として
不要な機能はどういったものがあるか」—— への回答を、この contract 自体の
scope 境界として明文化する。`kotoba-lang/browser` 自身の ADR-0001
（「WHATWG HTML/JS compatibility is a non-goal」）と `aiueos` 自身の README
（「deny-by-default capabilities, a deliberately small TCB」）から直接導かれる:

- **完全な WHATWG HTML5 tree construction アルゴリズム**（foreign content、
  adoption agency algorithm、encoding sniffing、エラー回復の全網羅）—
  この document/UI ツリーは author が書いた **信頼済み** コンテンツであり、
  任意の敵対的 Web コンテンツを堅牢に受け止める必要がない。
- **ambient なネットワーク/ファイルシステム/DOM/clipboard アクセス** —
  `browser` 自身の ADR-0001 が既に核心的不変条件として明記：「document and
  OS UI behavior do not receive ambient... access. They receive only host
  capabilities visible at the ABI boundary」。`kotoba` の safe-capability
  言語設計（ADR: kotoba 言語の安全性設計、"ambient authority アンチパターン"
  廃止）と同じ思想。
- **汎用 Web Platform API 群**（IndexedDB, WebRTC, WebAuthn, Payment Request,
  Web Audio, Service Worker, WebUSB, ...）— system UI が必要とする capability
  は `aiueos` 自身の manifest/policy/broker 層を通す。ambient browser-style
  API は capability confinement の思想と正面から矛盾する。
- **JIT クラスの汎用 JS 実行要件** — この contract は script 実行に一切
  関知しない（tree/style/draw-ops の形状のみを規定）。`kotoba-lang/browser`
  自身も QuickJS（JIT 無しインタプリタ）で十分と判断済み。
- **マルチプロセス sandboxing / site isolation** — `aiueos` は既に
  Wasm-component 単位の隔離を OS 層で提供する。この contract がその隔離
  境界を重複して持つ必要はない。
- **DevTools・拡張機能プラットフォーム** — 埋め込み system UI には不要。
- **フォント shaping/kerning/bidi、画像/動画コーデック、GPU compositor** —
  `kotoba-lang/browser` 自身の maturity matrix が既に「実装未着手の real な
  gap」として記録済み。この contract が解決するものではなく、そのまま
  引き継がれる open follow-up。

これらは「今後埋めるべきロードマップ」ではなく、両プロジェクトが
**意図的に非目標としている**ことの確認であり、本 contract の stub scope を
狭く保つ根拠でもある。

## Consequences

- `htmldom`/`cssom` 自体は無変更（既に standalone-consumable だったため
  抽出/decoupling 作業は不要だった、と確認できたこと自体が本 ADR の副産物）。
- `aiueos` 側で実際に mainuiux レンダラーを実装する作業（`.kotoba`
  ソース + Wasm component としての contract 適合実装）は別の、将来の
  cycle に残す — 本 ADR は stub の設計・登録のみを完了条件とする。
- `known-properties`/draw-ops 語彙は `cssom`/`dom-gpu` 側の今後の機能追加
  （list-style-type、real per-side margin/padding 等、`browser` の
  maturity matrix に記録済みの open items）に伴い増分更新される前提 —
  contract の破壊的変更ではなく通常の追記として扱う。

## Links

- `orgs/kotoba-lang/kotoba-document-contracts` (new repo)
- `orgs/kotoba-lang/browser/docs/adr/0001-kotoba-only-browser.md`
- `orgs/kotoba-lang/aiueos/README.md`
- `orgs/kotoba-lang/kotoba-lang/docs/adr/ADR-safe-capability-language.md`
- `90-docs/adr/2607051300-cross-org-capability-map.md`
- `orgs/kotoba-lang/kotoba/docs/ADR-kotoba-shell-aiueos-safe-kotoba.md`（Rust
  bootstrap と `.kotoba` component の照合パターンの実例）
- `orgs/kotoba-lang/kotoba/CLAUDE.md`（旧 Rust `kotoba-clj` → JVM Clojure +
  Chicory 移行の実例）
