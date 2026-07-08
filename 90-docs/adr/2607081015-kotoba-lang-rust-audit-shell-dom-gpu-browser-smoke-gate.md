---
id: adr-2607081015-kotoba-lang-rust-audit-shell-dom-gpu-browser-smoke-gate
title: "ADR-2607081015: kotoba-lang org の Rust 監査 → kotoba-shell の dom-gpu 参照修正 + kotoba-lang/browser の visual/webgpu smoke gate 実装"
status: closed
doc_type: adr
topic: kotoba-lang-rust-audit-shell-dom-gpu-browser-smoke-gate
authoritative: true
last_verified: 2026-07-08
authoritative_for:
  - "kotoba-lang org 全体(600+ repo)の Rust 監査結果: 能動的な Rust コードは kami-script-runtime-rs(WASM ホスト substrate、ADR-2607010930 で :stay-Rust-substrate と分類済みの意図的な最後の手段)と murakumo-studio/tauri/src-tauri(89行の薄い Tauri シェル、業務ロジック無し)の2箇所のみ。kotoba-v2025/_archive/251006 配下の273 .rsファイルは repo 自身の README が明示的に「歴史的資料のみ、新規 Cargo workspace member を追加するな」と宣言する非現行コード。kami-engine 本体および kami-app-*/kami-engine-* ファミリーは CI の \"no-rust guard\" により Rust 完全排除済み"
  - "kotoba-shell(orgs/kotoba-lang/shell)の macOS ネイティブホストは 2026-07-08 時点でまだ本番投入不可: `app scaffold --target macos` が生成する AppDelegate.swift は `final class AppDelegate {}` のみの空スタブ、Info.plist も実際は EDN テキストが書き出されるだけで Xcode ビルド不可能。kotoba-lang/browser + dom-gpu(旧 wasm-ui、ADR-2607051200)は設計思想としてはフォーム系アプリ UI 向けとして正しい方向(<input>/<textarea>/IME/スクロール等を本気で実装している)が、全82コミットが2026-07-05〜07-08の3日以内・\"R0\"段階・kami-app-*/murakumo-studio 含め実アプリでの採用実績ゼロ。オーナー判断により、murakumo-studio の Tauri/Rust シェル撤去と kotoba-shell 側のネイティブホスト本実装は本ADRのスコープ外として保留(将来 WKWebView 実装または dom-gpu/browser 成熟を待つかは未決定のまま)"
  - "kotoba-lang/shell の launcher.clj/README/test が古い `wasm-ui` 参照(ADR-2607051200 で `dom-gpu` へリネーム済みのはずが、後続の `restore: keep higher-quality original implementation for shell` commit がリネーム前のアーカイブ実装を巻き戻して再導入していた)を含んでいたため修正したが、push 直前に別セッションが同じ問題を独立に `main` へ着地させていた(commit b78655e)ことが判明。ただし設計判断が異なり、向こうは内部 map key `:wasm-ui` と `--substrate wasm-ui` CLI 値を意図的に不変のまま残す判断(shell の stable-api-spec はコマンド存在のみを凍結し、オプション値は凍結しないため)をしていた。本セッションはこの先行判断を尊重し、`:dom-gpu` へのキー名リネームまで行う自分のブランチは main へ merge せず削除した(コンフリクト解消ではなく設計判断の一致による取り下げ)"
  - "kotoba-lang/browser に、kotoba-shell の ui-substrate-specs が要求する :browser 側ゲート(package.json script: compile:smoke/compile:webgpu-smoke/smoke:visual/smoke:webgpu、および src/browser/visual_smoke_check.cljc)を新規実装し main へ merge した(commit 1c65fc8)。既存の browser.visual-smoke-model(テスト済みだが未使用だったモデル)を実 browser.session + 実 kotoba.wasm.host.webgl/webgpu に配線する2つの最小デモ(src/browser/smoke.cljs、src/browser/smoke_webgpu.cljs)と、dom-gpu の scripts/wasm_ui_smoke.cljc と同じビルド成果物検証パターンの src/browser/visual_smoke_check.cljc を追加。両ビルドとも shadow-cljs compile が実際に通り、`clojure -M:smoke <target>` が実成果物に対して green、`kotoba-shell ui check --strict`/`ui smoke --execute --strict` の :browser 行が ready かつ実行 exit 0 であることを確認済み"
  - "manifest/west.yml の shell/browser pin 前進は repos.edn の manifest-workflow に従い GitHub API 単一エントリ・SHA ロック PUT で行った。実行時点で fleet の複数セッションが同一ファイルを並行更新しており(cssom x2, net-kotobase, kotoba の pin 前進コミットが作業中に連続着地)、409 を複数回踏んで fetch→ff-merge→再生成→再PUT のリトライで解決した。最終的に shell@b78655e36b4f/browser@1c65fc886f6e で着地(commit 282db62)、child repo checkout・superproject 双方とも fast-forward で同期済み"
related:
  - 90-docs/adr/2607012115-kotoba-shell-cljc-restoration.md
  - 90-docs/adr/2607051200-kotoba-lang-ui-family-rename.md
  - 90-docs/adr/2607010930-clj-wgsl-migration.edn
supersedes: []
superseded_by: []
---

# ADR-2607081015: kotoba-lang org の Rust 監査 → kotoba-shell の dom-gpu 参照修正 + kotoba-lang/browser の visual/webgpu smoke gate 実装

**Status**: closed（2026-07-08、Closing addendum でclose）
**Date**: 2026-07-08
**Deciders**: Jun Kawasaki（オーナー指示「今の kotoba-lang で kami engine などで rust をまだ使ってしまっている repo がないか調査」から着手、以降の対応方針も都度オーナー承認）

## Context

kotoba-lang org 全体で Rust が残っている repo がないか調査してほしい、という依頼から着手した。kami engine 系は clj-wgsl 移行(ADR-2607010930)で Rust ワークスペースが全削除されているはずだが、削除漏れや復元漏れがないか確認する必要があった。

## 調査結果(Rust 監査)

`Cargo.toml`/`*.rs` の org 全体走査で見つかったのは3箇所のみ:

1. **kami-script-runtime-rs**(2026-07-06 新設) — kami-engine の WASM ホスト(`wasmtime` バインディング)。ADR-2607010930 の移行が `kotoba-runtime + kami-script-runtime(WASM host)` を `:stay-Rust-substrate` と分類していたにもかかわらず置き換えなしで削除されてしまった欠落分の復元。CLJC 版 `kotoba-lang/kami-script-runtime` は該当機能を明示的に「移植しない」としており、`kami-engine` 本体は CI に \"no-rust guard\" があるため独立 Rust crate として存在するしかない意図的な substrate。
2. **kotoba-v2025/_archive/251006/**(273 .rsファイル) — repo 自身の README が「歴史的資料のみ、新規 Cargo workspace member を追加するな」と明記する非現行コード。現行の権威は `kotoba-lang/kotoba`/`kotoba-lang/kotoba-lang` 等に移行済み。
3. **murakumo-studio/tauri/src-tauri**(89行) — Tauri v2 デスクトップシェル。`main.rs` は `clojure` サブプロセス(推論エンジン本体)の起動・監視のみで業務ロジックは一切持たない薄いシェル。

kami-engine 本体・kami-app-*/kami-engine-* ファミリーには Rust の取り残しなし(CI "no-rust guard" 確認済み)。

## murakumo-studio の Tauri/Rust シェル撤去の検討 → 保留

「唯一残る能動的な Rust コード(kami-script-runtime-rs 除く)である murakumo-studio の Tauri シェルも撤去し、kotoba-shell(orgs/kotoba-lang/shell、ADR-2607012115 で CLJC 復元スキャフォールドとして起票済み)に置き換えられないか」を検討した。

実装可否を確認するため `bin/kotoba-shell app scaffold --target macos` を実際に実行したところ、生成される `Sources/AppDelegate.swift` は `final class AppDelegate {}` のみの空スタブ、`Info.plist` も実際には EDN テキストがそのまま書き出されるだけで Xcode ビルド不可能と判明。kotoba-shell の README が謳う「システム WebView 不要、`kotoba-lang/browser` + `kotoba-lang/dom-gpu` の `kotoba:dom` レンダリングで代替」という設計も、`dom-gpu`/`browser` の実装状況を調べた結果、方向性自体は正しい(フォーム系アプリ UI を本気で狙った `<input>`/IME/スクロール実装)ものの全82コミットが3日以内・\"R0\"段階・実アプリでの採用実績ゼロと判明した。

murakumo-studio の実チャット/モデル管理 UI(reagent ベース)をこの生まれたての基盤に移植するのは時期尚早と判断し、オーナー判断で以下を決定:
- kotoba-shell 側の macOS ネイティブホスト本実装(WKWebView 実装 or dom-gpu/browser 成熟待ち)と murakumo-studio の Tauri/Rust 撤去は、本ADRのスコープ外として保留。
- 今回は kotoba-shell の README の事実不整合(`wasm-ui` という古い repo 名を参照したまま)の是正と、kotoba-lang/browser 側の pre-existing な未実装の解消のみ進める。

## 実施内容

### kotoba-shell の dom-gpu 参照修正 → 他セッションとの設計判断の一致確認

`kotoba-lang/shell` の launcher.clj/README/test に残っていた古い `wasm-ui` 参照(ADR-2607051200 のリネーム後、`restore: keep higher-quality original implementation for shell` commit がリネーム前のアーカイブ実装を巻き戻して再導入していたもの)を修正し、ローカルでは24テスト全green(修正前は8件失敗)まで確認した。

push しようとした時点で、別の並行セッションが同じ問題を独立に発見し `main`(commit `b78655e`)へ既に着地させていたことが判明。ただし内部 map key を `:wasm-ui` のまま残す(`--substrate wasm-ui` という CLI 値の後方互換性を守るため、shell の stable-api-spec はコマンド存在のみ凍結しオプション値は凍結しない、という理由付き)という、自分の変更(`:dom-gpu` へキー名までリネーム)とは異なる設計判断が下されていた。

先行して landed した判断を尊重し、自分のブランチ(`fix-dom-gpu-rename-refs`)は merge せず、ローカル・リモート双方から削除した。`main` の `b78655e` はテスト green(24 tests, 0 failures)を確認済み。

### kotoba-lang/browser の visual/webgpu smoke gate 実装

kotoba-shell の `ui-substrate-specs` が :browser 側に要求していた以下が欠落していたため実装:

- `package.json` script: `compile:smoke`/`compile:webgpu-smoke`/`smoke:visual`/`smoke:webgpu`
- `src/browser/visual_smoke_check.cljc`

実装は、既にテスト済みだが未使用だった `browser.visual-smoke-model`(録画ホストに対してのみテストされていた)を実 `browser.session` + 実 `kotoba.wasm.host.webgl`/`kotoba.wasm.host.webgpu` に配線する2つの最小デモ(`src/browser/smoke.cljs`、`src/browser/smoke_webgpu.cljs`)と、dom-gpu の `scripts/wasm_ui_smoke.cljc` と同じビルド成果物検証パターンの `src/browser/visual_smoke_check.cljc`。`public/webgpu.html`(それまで canvas はあるが script タグが無く何も描画しない死んだページだった)にもスクリプトタグを追加し、新規 `public/visual-smoke.html` を追加。

`implement-visual-webgpu-smoke` ブランチを push、コンフリクトなく `main`(commit `1c65fc8`)へ merge。

### manifest pin 前進

`manifest/repos.edn` の manifest-workflow に従い、shell/browser の west pin を GitHub API 単一エントリ・SHA ロック PUT で前進させた。作業中、fleet の複数セッションが同一 `manifest/west.yml` を並行更新しており(cssom を2回、net-kotobase、kotoba の pin 前進コミットが連続着地)、409 を複数回踏んだ。都度 fetch→stash→ff-merge→stash pop→該当2エントリのみ再生成→SHA 再取得→再PUT のリトライで解決し、最終的に `282db62` で着地。child repo checkout・superproject 双方を fast-forward で同期し、`git stash list`/`git status` が完全にクリーンであることを確認して完了。

## Consequences

- kotoba-lang org の Rust 残存状況が文書化された(kami-script-runtime-rs と murakumo-studio/tauri のみ、いずれも意図的)。
- kotoba-shell の README の事実不整合(`wasm-ui`→`dom-gpu`)が解消され、`ui check`/`ui smoke` が :browser/:dom-gpu 両方について green になった。
- kotoba-lang/browser に実働する visual/webgpu smoke gate が追加され、kotoba-shell の CI 相当ゲートが実行可能になった。
- murakumo-studio の Tauri/Rust シェルは撤去されていない(意図的な保留、下記 not-decided 参照)。

## Not decided(follow-up として残るもの)

- kotoba-shell の macOS ネイティブホスト本実装(WKWebView によるプラグマティックな実装 or dom-gpu/browser 成熟待ちのどちらを採るか、オーナー判断待ち)。
- murakumo-studio の Tauri/Rust シェル撤去そのもの(上記が前提)。
- kotoba-lang/dom-gpu・kotoba-lang/browser の実アプリ採用実績を積む作業全般。
- dom-gpu 自身の `smoke:wasm-ui` を単独実行すると事前 compile が無く fail する script 順序の問題(browser 側とは無関係、dom-gpu 自身のスコープ)。

## Verification

- `orgs/kotoba-lang/browser`: `clojure -M:test` 617 tests / 3012 assertions / 0 failures。`npx shadow-cljs compile visual-smoke`/`webgpu-smoke` とも 0 warnings。`clojure -M:smoke visual`/`webgpu` とも実成果物に対して green。
- `orgs/kotoba-lang/shell`: `clojure -M:test` 24 tests / 246 assertions / 0 failures(main の b78655e に対して)。`bin/kotoba-shell ui check --strict --json` → `ui-ready`、`ready-count: 2`。`ui smoke --execute --strict --json` → :browser の2 script とも `exit 0`。
- `manifest/west.yml`: shell/browser エントリの revision が child repo の実 HEAD(`b78655e36b4f.../1c65fc886f6e...`)と一致することを確認。superproject の `git status`/`git stash list` がクリーン。

## Closing addendum(2026-07-08)

オーナー指示「update adr, closing」を受けて本ADRを起票と同時にcloseする。当初スコープ(Rust監査 → kotoba-shellのdom-gpu参照修正 → kotoba-lang/browserのvisual/webgpu smoke gate実装 → manifest pin前進)は全て完了・検証済み。murakumo-studioのTauri/Rust撤去とkotoba-shellのネイティブホスト本実装は、オーナー判断により明示的に別スコープのfollow-upとして残し、本ADR自体はここでclose。

## Reverify + closing addendum(2026-07-08、二度目)

オーナー指示「closing」を再度受け、着地内容が変化していないか再検証した。

- `orgs/kotoba-lang/browser`: HEAD は依然 `1c65fc8`。`clojure -M:test` 617 tests / 3012 assertions / 0 failures を再確認。
- `orgs/kotoba-lang/shell`: HEAD は依然 `b78655e`。`clojure -M:test` 24 tests / 246 assertions / 0 failures を再確認。`bin/kotoba-shell ui check --strict --json` → `ui-ready`、`ready-count: 2` を再確認。
- `manifest/west.yml`: shell/browser エントリの revision が上記2 HEADと一致することを再確認。
- superproject: `git status`(追跡ファイルの差分無し)、直近 commit は `b2ce7a8`(本ADR自体の追加)であることを確認。

状態変化なし。本ADRは close のまま維持する。
