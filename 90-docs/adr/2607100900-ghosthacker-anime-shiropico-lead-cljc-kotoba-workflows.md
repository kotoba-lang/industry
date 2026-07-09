# ADR-2607100900: ghosthacker アニメは shiropico 先行、旧 Web パイプラインは cljs へ、game/anime 生成 workflow は cljc kotoba 前提

- **Status**: accepted（オーナー指示 2026-07-10）
- **Related**: ADR-2607100100（app 第一 runtime 優先順位 kotoba wasm >
  clojurewasm > ClojureScript > nbb、JVM/bb 降格 — 本 ADR の game/anime
  生成 workflow はこの順序に従う）、ADR-2607011816（shiropico standalone
  repo 化）、ADR-2607012000（shiropico publish actor）、ADR-2607023200
  （game portfolio flow）、ADR-2607021700（manga as social post — 旧
  ghosthacker web パイプラインの DEPRECATED 経緯）。

## Context

- 本セッションの棚卸しで判明した現状: ghosthacker IP の3トラックは
  進捗にばらつきがある。漫画は `aozora.app/manga/ghosthacker` で公開済み
  （Clj/Datomic パイプライン `kami-app-sip-clj` へ移行済み）。ゲームは
  10 タイトル中 FLOW/HARMONY/ECHOES の3本のみ実装、いずれも JVM
  `terminal.clj` CLI プロトタイプ止まり。アニメは Ren/Nei 本編・
  SHIRO & PICO（shiropico）の2系統があるが、どちらもレンダリング/公開に
  未到達。
- `orgs/com-junkawasaki/ghosthacker/README.md` 冒頭に明記の通り、
  `apps/web` + `apps/server` の Web パイプラインは 2026-06 に
  **DEPRECATED**（漫画出力は `kami-app-sip-clj` の `sip.render` /
  `sip.page` / `sip.storyboard` が後継）。ただし **実体は SvelteKit
  （vite）+ Go**（`apps/web/package.json`: `@sveltejs/kit`,
  `svelte`）であり、オーナー指示の「nextjs 版」呼称は技術的には
  不正確 — 本 ADR では従来この Web パイプラインを指す口語表現として
  扱う。動画生成・Wattpad 自動投稿・Neo4j ストーリーグラフ閲覧/編集など
  「アニメ固有」の残存機能は、この SvelteKit+Go スタックにまだ残っている
  （漫画側の移行はこれらをカバーしない）。
- SHIRO & PICO（`gftdcojp/ai-gftd-ghosthacker-shiropico`）は台本・多言語
  ローカライズ資産が ep1-12 全話分完成、公開 actor（tsumugu 型、CACAO/
  StateGraph/Governor）も実装済みだが、担当エンジン `ai-gftd-animeka` の
  生成グラフは ComfyUI/LLM 未接続でオフライン検証のみ。

## Decision

1. **アニメの先行トラックは shiropico とする。** Ren/Nei 本編アニメ
   （旧 SvelteKit+Go パイプライン由来の動画生成/配信）は shiropico の
   後追いとし、レンダリング接続（ComfyUI ゲートウェイ）・kotobase 実投稿
   の優先実装/検証は `ai-gftd-ghosthacker-shiropico` / `ai-gftd-animeka`
   側から着手する。
2. **旧 ghosthacker Web パイプライン（`orgs/com-junkawasaki/ghosthacker/
   apps/web`(SvelteKit) + `apps/server`(Go)）を ClojureScript へ
   リファクタする。** 対象は ghosthacker 本体、および関連フロントエンド
   全般（`mangaka-ghosthacker-assets` 等、周辺リポに残る Next.js/React/
   SvelteKit 実装を含む）。漫画出力は既に `kami-app-sip-clj` 移行済みで
   対象外 — リファクタ対象は残存する「アニメ固有」機能（動画生成
   トリガー、Wattpad 自動投稿、Neo4j ストーリーグラフ閲覧/編集 UI）。
3. **ゲームおよびアニメの生成 workflow は `.cljc` で書き、
   ADR-2607100100 の app 第一 runtime 優先順位（kotoba wasm runtime →
   clojurewasm → ClojureScript → nbb、JVM/bb は降格）に従う。** 対象:
   game portfolio（`ghosthacker-flow` / `ghosthacker-harmony` /
   `ghosthacker-echoes` および未着手7タイトルの生成/ビルド workflow）、
   アニメ生成 workflow（`ai-gftd-animeka` の cut 単位パイプライン）。
   既存の JVM CLI プロトタイプ（FLOW/HARMONY/ECHOES の `terminal.clj`）は
   互換 (compat) 層として残し、新規ホスト実装は kotoba wasm を第一候補に
   検討する。host-import を要する形（clojurewasm の現行制約、
   ADR-2607100030 addendum 2）の場合は ClojureScript に落とす。

## Consequences

- 本 ADR は方針決定のみ。実装（SvelteKit/Go → cljs の実コード移行、
  game/anime 生成 workflow の cljc kotoba 実装）は follow-up tick で行う。
- 次アクション候補: (a) `ghosthacker/apps/web` の cljs 移行スコープ策定
  （Wattpad 投稿・Neo4j グラフ閲覧の cljs 実装調査）、(b) game portfolio
  未着手7本の着手時に host adapter を kotoba wasm 前提で選定
  （`kami-engine-sdk` 流用可否の検証）、(c) `ai-gftd-animeka` の生成
  グラフを cljc kotoba 化する際の ComfyUI/LLM 接続経路の確定。
- CLAUDE.md の runtime 優先順位節・repo 構成に対する追加変更は無し
  （既存 ADR-2607100100 の適用範囲を game/anime 生成 workflow に対して
  明示しただけ）。
