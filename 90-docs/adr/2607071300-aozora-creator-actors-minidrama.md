---
id: adr-2607071300-aozora-creator-actors-minidrama
title: "ADR-2607071300: aozora — creator actors (animeka/dougaka/minidrama) 登録 + 縦型ミニドラマ制作 actor の設計"
status: accepted
doc_type: adr
topic: aozora-creator-actors-minidrama
authoritative: true
last_verified: 2026-07-07
authoritative_for:
  - creator actor registry (aozora.appview.creator-actors) — animeka.aozora.app / dougaka.aozora.app / minidrama.aozora.app の projected identity と SPA 配線
  - creator 系 actor identity は *.gftd.ai に作らないという適用（wave2/wave3 の帰結。ai-gftd-{mangaka,animeka} は生成エンジン、identity は aozora）
  - minidrama actor（縦型ミニドラマ制作、DramaLLM ⊣ DramaGovernor）の設計 — pipeline、governor 検閲項目、台帳 schema、公開経路
related:
  - 90-docs/adr/2607070400-app-aozora-manga-work-actor-profiles.md
  - 90-docs/adr/2607062200-gftdcojp-legacy-gftd-ai-hosts-retirement-wave2.md
  - 90-docs/adr/2607071100-gftdcojp-mangaka-retirement-wave3-aozora-studio.md
  - 90-docs/adr/2607011900-genapp-clj-mangaka-animeka-commons.md
  - 90-docs/adr/2607062100-app-aozora-vertical-video-feed.md
  - 90-docs/adr/2607071000-app-aozora-video-upload-r2-blob.md
  - 90-docs/adr/2607071100-app-aozora-live-media-plane-v0.md
supersedes: []
superseded_by: []
---

# ADR-2607071300: aozora creator actors + ミニドラマ制作 actor

**Status**: accepted（登録は実装・デプロイ済み。minidrama actor 本体は設計 —
実装 scaffold は follow-up）
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki（指示:「animeka.gftd.ai, dougaka.gftd.ai などの actor も
登録して。また dougaka 関係で mini drama を作る actor を設計して」。命名は確認の上
**aozora.app 配下**で承認 — wave2/wave3 の「\*.gftd.ai ホストは増やさない」に整合）

## Part 1 — creator actor 登録（実装済み）

### Decision

- **registry**: `aozora.appview.manga-actors`（ADR-2607070400、作品単位）と同型の
  **`aozora.appview.creator-actors`**（職能単位）を新設。projected identity
  （handle `<name>.aozora.app` / did:web 同名 / `:keyed? false`、resolve-handle
  fail-open で追加配線なし）+ intro post 1 本（`app.bsky.embed.external` →
  /videos）。`actors->tx` で kotobase 後追い transact 可能、`actor-feed-view`
  で SPA が即日描画。
- **entries**:
  | slug | handle | 役割 |
  |---|---|---|
  | animeka | animeka.aozora.app | アニメ家 — AI アニメーション制作（エンジン: genapp-clj / ai-gftd-animeka、ADR-2607011900） |
  | dougaka | dougaka.aozora.app | 動画家 — 縦型ショート動画制作。/videos 公開（ADR-2607071000 uploadBlob 経路、live は ADR-2607071100） |
  | minidrama | minidrama.aozora.app | ミニドラマ座 — dougaka 系列の縦型ミニドラマ制作（Part 2 で設計） |
- **SPA 配線**: profile dispatcher に creator 分岐（etzhayyim → manga work →
  creator → AT の順）、`creator-actor-profile` page（"creator actor" チップ、
  agent actorType）、検索ページに "creators" セクション（discover + query match）。
- **やらないこと**: `animeka.gftd.ai` / `dougaka.gftd.ai` という did:web は
  発行しない。gftd.ai ホスト新設は wave2（ADR-2607062200）・wave3
  （ADR-2607071100 mangaka 退役）で禁じた方針への逆行。

### Verification (2026-07-07)

app-aozora main `879bd02a` として着地・SPA デプロイ済み。本番
`aozora.app/profile/minidrama.aozora.app` の実描画（displayName / handle /
creator actor チップ / intro post）を headless Chrome で確認。tests:
registry 5 件含む 272/1187 + SPA 52/151 green。

## Part 2 — minidrama actor 設計（本 ADR の主文）

60〜90 秒の縦型（720x1280）ミニドラマを、企画→脚本→生成→合成→公開まで
一貫制作する autonomous actor。**robotaxi-actor / gftd-talent-actor /
cloud-itonami と同型**（CLAUDE.md「Actors」節）: 知能ノードを 1 ノードに封じ込め、
別系統の Governor が検閲、全 commit/hold を append-only 台帳に積む。

### 封じ込め: DramaLLM ⊣ DramaGovernor

- **DramaLLM**（Advisor、注入境界: mock ‖ `langchain.model` 実 LLM）は
  *proposal のみ*返す: 企画案・脚本・shot list・公開文面。書込・生成 job 発火・
  公開はすべて Governor 可決後に actor 本体が行う。
- **不変条件**: *governor が拒否する 書込/開示/作動/認証 を actor は決して行わない*。
- **DramaGovernor の検閲項目**（可決 / 拒否 / 人間承認へ escalate）:
  1. **フォーマット**: 総尺 ≤ 120s・縦 720x1280・shot 数 ≤ 24・1 shot ≤ 10s。
  2. **コンテンツポリシー**: 暴力/性的表現/実在人物 likeness/商標を拒否。
     生成素材のみ（外部素材の取り込みは provenance 必須 → 無ければ拒否）。
  3. **予算**: `:agent.budget/*` — episode あたり生成 job 数 × 単価の上限。
     超過見込みは拒否（縮小再提案を要求）。
  4. **公開**: publish は常に **human 承認**（interrupt-before）。unlisted
     preview までは自動可。
  5. **連投制御**: 公開頻度 ≤ N 本/日（feed spam 防止）。

### langgraph-clj StateGraph（1 run = 1 操作、無限内部ループ無し）

```
plan ──▶ script ──▶ [interrupt: script 承認] ──▶ storyboard
  ──▶ generate(shot ごと・有界) ──▶ assemble ──▶ preview
  ──▶ [interrupt: publish 承認] ──▶ publish
```

- **plan**: theme/logline/対象尺。DramaLLM 提案 → Governor 検閲。
- **script**: scene / dialogue / ト書き。kotoba EDN（`:drama/*` datom、下記）。
- **storyboard**: shot list（shot ごとに prompt / duration / 台詞字幕 / SFX）。
- **generate**: shot 単位で生成 job を発火。**エンジンは genapp-clj 系**
  （ADR-2607011900 の mangaka/animeka commons と同じ scaffold の
  video-generation app = dougaka エンジン。フリート ComfyUI → 将来
  murakumo.cloud edge、wave3 の役割分割どおり）。1 run = 1 shot、失敗 shot は
  retry 有界。
- **assemble**: ffmpeg concat + 字幕焼き込み + audio → 縦 mp4（H.264/AAC）。
  ライブ収録派生は live-relay（ADR-2607071100）経由の HLS でも可。
- **publish**: PDS uploadBlob（R2、ADR-2607071000）→
  `app.aozora.embed.video {src, aspectRatio 720x1280}` を record embed に持つ
  post を **minidrama.aozora.app** として createRecord → aozora `/videos` に
  載る（ADR-2607062100 の再生判定そのまま）。予告/同時上映は
  `app.aozora.live.*` playlist + `embed.live=true`（LIVE バッジ）。
- **durable outer loop**: 長期運転は StateGraph 内で回さず lease/tick/budget/
  governor/crash-recovery の外側ループ（`:agent.loop/*` `:agent.tick/*`
  `:agent.lease/*` `:agent.budget/*` `:agent.event/*` datom）で有界 run を反復。
- **checkpoint**: `interrupt-before` を script 承認・publish 承認の 2 箇所に。

### 台帳（append-only、:db-api 駆動）

Store は langchain.db `{:q :transact! :db :pull :entid}` マップ越しのみ
（`MemStore ≡ DatomicStore ≡ kotoba-api`、contract test で保証）。

```
:drama.episode/{id title logline duration-target status createdAt}
:drama.scene/{id episode seq setting}
:drama.shot/{id scene seq prompt duration subtitle status render-job render-cid}
:drama.decision/{id subject verdict reason governor ts}     ; 可決/拒否/hold 全件
:drama.publish/{id episode post-uri blob-cid publishedAt approved-by}
```

### 注入境界 / deps（3 actor 同形）

- Store（MemStore ‖ DatomicStore ‖ kotoba pod）/ Advisor（mock ‖ 実 LLM）/
  Phase（0: mock 全通し → 1: 実 LLM + mock 生成 → 2: 実生成 unlisted →
  3: 公開運転）を注入で差し替え、コアは不変。
- `io.github.com-junkawasaki/langgraph-clj {:local/root …}` + `:dev` で
  langchain-clj override。`clojure -M:lint` / `clojure -M:dev:test`。
  `.cljc` は `#?(:clj/:cljs)` 条件化（`#?(:kototama …)` は書かない —
  2607060000 節の repowide ルールどおり機構が無い間は分岐を作らない）。

### identity / 完了条件（follow-up）

- 実装時の完了条件は CLAUDE.md「Actors」節どおり: `20-actors/minidrama`
  実装 → **actor 単位 repo `etzhayyim/com-etzhayyim-minidrama`** 作成 →
  west 登録 → **RAD identity 台帳**登録。
- 鍵付き化: `minidrama.aozora.app` は現状 projected identity
  （`:keyed? false`）。CACAO 自己発行（`load-or-create-identity!`、秘密鍵は
  `.minidrama/identity.edn` gitignore）で自分の鍵での record mint に昇格 —
  ADR-2607070400 の work actor 鍵付き化と同じ follow-up 系列。

### Rejected

- **dougaka actor に mini drama も担わせる（別 actor を立てない）**:
  dougaka は汎用 video creator の brand。minidrama は governor 検閲項目
  （脚本承認・尺・連投）が固有で、台帳も `:drama/*` として独立させた方が
  監査可能。系列関係は registry の description/tags で表現。
- **識別子を minidrama.gftd.ai にする**: wave2/wave3 方針への逆行。
- **video 生成をこの actor 内に実装**: 生成エンジンは genapp-clj 系
  （mangaka/animeka と共通 scaffold）に分離。actor は proposal + 台帳 +
  governor + 公開 orchestration に徹する。

## Follow-ups

- minidrama 実装 scaffold（child repo + west + RAD、常時許可フロー）
- dougaka エンジン = genapp-clj video instance の起票
- creator actors の kotobase 後追い transact（transact 健全化後、actors->tx）
- 鍵付き actor 化（CACAO、ADR-2607070400 系列）

## 追記 (2026-07-07): Part 2 実装 scaffold 完了 (R0)

follow-up 先頭の実装 scaffold を同日完了:

- **repo**: `etzhayyim/com-etzhayyim-minidrama`（public、`52e7eb25`）。
  tashikame 同型の R0 — operation/governor/advisor/store/publisher/phase/sim
  (.cljc) + governor-contract / store-contract / operation テスト
  （lint 0/0、15 tests / 51 assertions green、`clojure -M:dev:run` 実走確認）。
- **west 登録**: repos.edn + west.yml `--entry` 最小 diff（pin = 子 main HEAD、
  server-side 検証 OK）。
- **設計からの R0 確定差分**（repo `docs/adr/0001-architecture.md`）:
  publish 承認は interrupt-before ではなく **run context の
  `:approvals #{:publish}`** で gate（1 run = 1 操作を保つ。この family に
  interrupt-before の前例が無いため。checkpointer resume ベース化は
  follow-up）。phase 既定は 0 (draft)。
- **RAD identity journal は defer**（sng / kyoninka と同じ前例 —
  実 `:rad/head` 署名 CID は kotoba/IPFS signing tooling 待ち。did:web は
  `did:web:etzhayyim.github.io:com-etzhayyim-minidrama` を assert 済み）。
- 残 follow-up: 実 aozora Publisher + CACAO 鍵、genapp-clj video エンジン
  （dougaka エンジン）統合、durable outer loop、RAD journal 本登録。
