# ADR-2607100900: ghosthacker アニメは shiropico 先行、旧 Web パイプラインは cljs へ、game/anime 生成 workflow は cljc kotoba 前提

- **Status**: accepted（オーナー指示 2026-07-10）
- **Related**: ADR-2607100100（app 第一 runtime 優先順位 kotoba wasm >
  clojurewasm > ClojureScript > nbb、JVM/nbb 降格 — 本 ADR の game/anime
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
   clojurewasm → ClojureScript → nbb、JVM/nbb は降格）に従う。** 対象:
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

## Addendum 1（2026-07-10 — 決定②の前提訂正、follow-up (a) の調査結果）

follow-up (a)（`ghosthacker/apps/web` の cljs 移行スコープ策定）を実施した
結果、**決定②の前提が誤りだったと判明した**。

- `apps/web`・`apps/server`・`260123-jump/` 全体を `wattpad`/`video`
  (gen|render)/`neo4j` で grep しても、ヒットは `README.md` のみ
  （node_modules 内のアイコン名ノイズを除く）。README 記載の機能
  チェックリスト（`[x] Wattpad automated publishing`, `[ ] Video
  generation (Sora)`）とアーキテクチャ図は **アスピレーショナルな
  ドキュメントであり、対応する実装コードは現チェックアウトに存在しない**
  （`git log --all` でも痕跡なし）。
- 唯一の物証は `.auth/wattpad.json`（`cookies`/`origins` キーのみ —
  Playwright の storage-state 形式）。過去にログインセッションを取得した
  痕跡はあるが、それを使う自動投稿コードは無い。
- 「Neo4j ストーリーグラフ」の実装も同様に実体が異なる: 実際にあるのは
  `apps/web/src/lib/jsonld-cypher/{graph-store.ts, cypher.ts}`（Neo4j
  不使用、JSON-LD から構築する in-memory プロパティグラフ + 自前
  Cypher サブセットパーサ）。これは **manga/storyboard 編集
  （`components/Storyboard/*.svelte`, `apps/server/internal/service/
  storyboard.go`）向け**であり、既に `kami-app-sip-clj` への移行対象
  （DEPRECATED 範囲）に含まれる — 対象外ではなく対応済み。

**訂正後の扱い**: 決定②（旧 Web パイプラインのアニメ固有機能を cljs へ
移行）は、**移行すべき実コードが存在しないため実質 no-op**。
`ghosthacker/README.md` の機能チェックリストは実態と乖離しているため
follow-up で訂正する（実装済み/未実装の区別を明確化）。

Ren/Nei 本編向けの Wattpad 自動投稿・動画生成を今後本当に作る場合は
（オーナー判断 2026-07-10）、**今は着手せず shiropico 先行に集中する**。
shiropico（`ai-gftd-ghosthacker-shiropico` / `ai-gftd-animeka`）の
ComfyUI/LLM 接続基盤が固まった後に、Ren/Nei トラックとの共通化を
改めて検討する（cljs 移行ではなく新規実装として、決定③の
cljc-kotoba-first 前提で設計する）。

follow-up (b)（game portfolio host adapter 選定）・(c)（`ai-gftd-animeka`
cljc kotoba 化 + ComfyUI/LLM 接続）は本 addendum の影響を受けず続行。
