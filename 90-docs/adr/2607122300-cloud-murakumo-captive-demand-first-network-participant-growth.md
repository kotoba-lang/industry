# ADR-2607122300: cloud-murakumo ネットワーク参加者拡大 — 自家需要（net-babiniku/club-shinshi の画像・動画生成）を先に固め、公開参加者拡大は後続

**Status**: accepted
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki
**Scope**: `orgs/gftdcojp/cloud-murakumo`, `orgs/jk-luxury/net-babiniku`, `orgs/jk-luxury/club-shinshi`

## Context

「cloud-murakumo でもっと広く network 参加ユーザーを集めるには」という問いに対し、
net-babiniku / club-shinshi での画像・動画消費を前提とする構想があった。オーナーの
決定は「まず需要を増やす（自家需要）、その上で参加者を増やす」という順序。

既存 ADR との関係を先に整理する:

- ADR-2607030030（murakumo 推論経済 GTM）は既に三面市場（企業/一般ユーザー/
  blockchain）とフライホイール（貢献者↑→モデルクラス↑→需要↑→credit流速↑→
  treasury↑→ハード増強）を確定済みで、「順序の規律: 公開マーケットはPhase 3。
  先に企業fleet連邦（Phase 2）で品質とSybil前提を固める」という規律も既にある。
  本ADRはこれを覆さず、「一般ユーザーの需要」を具体的にどう積み上げるかを
  net-babiniku/club-shinshiという2つの実在アプリに即して具体化するもの。
- ADR-2607051621（AGPLv3+ISIC/ISCOネットワークレジストリ）はAmendmentで
  **cloud-murakumo本体のOSS化は「行わない」に修正済み**（private repoのまま）。
  Layer 1（itonami.cloud登録）・Layer 2（treasury protocol fee）のインフラは
  実装済みで残っているが、外部SaaS運営者を勧誘するLayer 1-3の経路は今回の
  「参加者拡大」の主経路として採用しない。

現状の実装状況（2026-07-12 時点で確認）:

- **club-shinshi**: `companion.cljs`（`60-apps/.../shinshi/worker/`）が
  `agent-chat!`（`MURAKUMO_CHAT_URL` = `https://murakumo.cloud/api/v1/chat/completions`、
  モデル `qwen-agentworld-35b-a3b`）と `request-scene!`（`MURAKUMO_IMAGE_URL` =
  `https://api.murakumo.cloud/v1/images/generations`）の両方を実装済み。
  つまり **club-shinshi は既にチャットと画像生成の両方で murakumo の実需要を
  生んでいる**（PR #26, 2026-07-10）。x402 課金・engagement metrics も稼働中。
- **net-babiniku**: `functions/api/chat.js` が同じ `qwen-agentworld-35b-a3b` を
  murakumo 経由で呼ぶ（2026-07-07、club-shinshiより先行）。governor
  （`src/babiniku/governor.cljc`）・VRM描画（`src/babiniku/web.cljs` +
  `vrm_bridge.cljc`）・broadcast capture（`src/babiniku/broadcast.cljs`、
  `canvas.captureStream()`+`RTCPeerConnection`）は実装済みで「プレースホルダ」
  段階は既に過ぎている。ただし **画像/動画生成の murakumo 呼び出しは無い**
  （VRMはクライアント側の既存メッシュ描画であり生成イベントを生まない）
  ・**TTSは皆無**（`tts|speech|voice` grep 0件、チャットはテキストのみ）
  ・**broadcastはシグナリング/SFUが無くローカルプレビューに留まり視聴者に
  届かない**（意図的にドキュメント化済みのギャップ）。

## Decision

2フェーズに分ける。Phase A が完了する（実測が出る）までPhase Bの施策には
着手しない。

### Phase A（自家需要拡大 — 今ここに着手する）

1. **net-babiniku に画像/動画生成の murakumo 需要を追加する。**
   club-shinshi の `request-scene!` パターン（`companion.cljs` →
   `MURAKUMO_IMAGE_URL`）を横展開する。具体的には
   `src/babiniku/character.cljc` の kisekae compose（衣装合成、
   ADR-2607071600 M6）を実際に murakumo の `/v1/images/generations` 呼び出しに
   接続する。現状は静的メッシュのクライアント描画のみで課金イベントを生まない。
2. **TTS を追加する。** murakumo でホスト可能な音声合成モデルがあれば、
   text-only chat を音声付きに拡張する — 新しい消費カテゴリ（対象範囲・
   モデル選定は follow-up ADR とする、本ADRでは方向性のみ確定）。
3. **broadcast 到達性を直す。** `broadcast.cljs`/`web.cljs` は実際に
   `RTCPeerConnection` を張るがシグナリング/SFUが無く視聴者に届かない。
   これは「需要」そのものではなく「参加者到達性」のボトルネックだが、
   Phase B（観客拡大）の前提条件としてPhase Aのうちに解消する —
   コンテンツ/需要が実証されても届く経路が無ければ観客は増えない。
4. **計測をPhase切替のゲートにする。** club-shinshiには既にengagement
   metrics（`8d546971`）がある。net-babinikuにも同等の「murakumoリクエスト
   量・種別内訳（chat/image/video/tts）」の計測を入れ、これをPhase A→Bの
   判定材料にする（恣意的な期限でなく実測ゲート）。

### Phase B（参加者拡大 — Phase Aの実測が出てから）

新しい枠組みは作らず、ADR-2607030030の三面市場・フライホイールをそのまま使う。

1. **「一般ユーザー」参加者拡大 = net-babiniku/club-shinshiの観客・課金
   ユーザーを増やすこと。** ADR-2607030030の一般ユーザー定義
   （fiat→credits購入がネットワークの外部収益）と直結する。Phase Aで
   画像/動画/音声需要が実証されたら、2アプリの集客
   （club-shinshiには既存の shinshi-growth-actor、ADR-2607040900、が
   ある — growth-LLM ⊣ MarketingGovernor。NSFWは有料配信を買えないため
   体験の質＋AT-Proto/Bluesky federationが主チャネルという既存戦略）が
   そのままmurakumoの外部収益成長になる。net-babinikuにも同型の
   growth-actorを立てるかは、Phase A実測後に判断する。
2. **供給側（fleet参加者）拡大はADR-2607030030の「順序の規律」通り**、
   Phase 2（既知組織間のcredits相互運用・overlayのQUIC/WebRTC結線）を
   先に固めてからPhase 3（公開マーケット・browser参加）に進む。
   前倒ししない — 需要が伴わない供給拡大はSybil/品質前提が緩んだ状態での
   公開マーケット化と同義になる。
3. **ADR-2607051621 Amendmentの決定（cloud-murakumo本体はAGPL化しない・
   privateのまま）は本ADRでも覆さない。** Layer 1（itonami.cloud登録）・
   Layer 2（treasury protocol fee）は実装済みインフラとして将来
   （cloud-murakumo自身を外部運営者に開放する場合）使えるが、今回の
   Phase Bの主経路としては採用しない — 今回の主眼は自社2アプリの
   観客拡大であり、外部SaaS運営者の勧誘ではない。

## やらないこと

- cloud-murakumoのAGPL relicense再検討はしない（ADR-2607051621 Amendment
  の決定を維持）。
- witness-quorum/overlay結線などのネットワーク分散化ロードマップ
  （ADR-2607110300）はPhase Bの前提条件にしない — 別トラックのまま進める。
- Phase Aの実測（計測ゲート）が出る前にPhase Bの施策（fleet連邦勧誘・
  観客獲得への広告投資等）に着手しない。

## Consequences

- (+) Phase Aは大半が既存パターンの横展開（club-shinshiのimage-gen wiring、
  shinshi-growth-actorの配信戦略）で、真に新規実装が要るのはbroadcastの
  シグナリング/SFUとTTSの2点に絞られる。実装コストは相対的に低い。
- (+) Phase A→Bのゲートを実測ベースにすることで、需要が実証される前に
  参加者獲得コスト（マーケティング・fleet勧誘）を払うリスクを避ける。
- (-) broadcastのシグナリング/SFUは新規実装であり（既存コードのwiring
  だけでは閉じない唯一の項目）、着手順位を誤ると Phase A全体の遅延要因になる。
- (-) Phase Bを「観客拡大」中心に置くことで、fleet供給側の拡大
  （compute contributor増加）は引き続き後回しになる。需要が急伸し供給が
  追いつかない場合（demand >> supply）、credit経済のタイト化・料金上昇の
  リスクが残る — その場合はADR-2607030030 Phase 2（fleet連邦）着手判断を
  別途行う。

## Related

- `90-docs/adr/2607030030-murakumo-inference-economy-gtm.md`（三面市場・
  フライホイール・順序の規律。本ADRが継承する上位方針）
- `90-docs/adr/2607051621-cloud-murakumo-agpl-isic-network-registry-kotoba-treasury.md`
  （Amendmentでcloud-murakumo本体private維持を決定済み。本ADRはこれを覆さない）
- `90-docs/adr/2607070800-net-babiniku-llm-backend-claude-sonnet-5.md`
- `90-docs/adr/2607071600-net-babiniku-service-completion-design.md`
  （C1-C5完了基準。broadcast/TTSギャップの既存ドキュメント）
- `90-docs/adr/2607051900-network-isekai-net-babiniku-broadcasting-integration.md`
- `90-docs/adr/2607040900-shinshi-growth-actor-itonami-client-service.md`
- `90-docs/adr/2607110300-kotoba-lang-net-kotobase-cloud-murakumo-decentralization-roadmap.md`
  （別トラック。本ADRのPhase Bの前提条件にしない）
- `orgs/jk-luxury/club-shinshi/60-apps/ai-gftd-project-shinshi/appview/ai-gftd-wasm-shinshi-sh1n5h1x/cljs/src/shinshi/worker/companion.cljs`
- `orgs/jk-luxury/net-babiniku/functions/api/chat.js`,
  `orgs/jk-luxury/net-babiniku/src/babiniku/broadcast.cljs`,
  `orgs/jk-luxury/net-babiniku/src/babiniku/character.cljc`

## Addendum(2026-07-12、Phase A item 1 実装・着地)

Phase Aの1番目（net-babinikuへの画像/動画生成需要追加）を実装し、
`jk-luxury/net-babiniku` へ着地させた（PR
[#140](https://github.com/jk-luxury/net-babiniku/pull/140)、squash merge
`99b77455`）。

**Decisionの訂正**: 実装着手時に判明した誤り — 本文の「kisekae compose
（衣装合成）を murakumo の画像生成呼び出しに接続する」という記述は誤り。
`kisekae.spec`/`kisekae.edit` は既存VRMメッシュの決定論的な部位合成
（幾何演算）であり、AI画像生成とは無関係 — 接続する対象が無い。実際の
実装は club-shinshi の `request-scene!` パターンをそのまま横展開した
**新規エンドポイント** `functions/api/scene.js`（`POST /api/scene`）:
`chat.js` の `ROSTER`/`personaFromRequest`/`MURAKUMO_BASE_URL` を import
して再利用（第三の手動ミラーを作らず drift リスクをゼロにする）、
`api.murakumo.cloud/v1/images/generations` へ `MURAKUMO_PROXY_TOKEN` 付きで
POST。フロントは `:scene/request` → `:scene/receive`/`:scene/error` の
re-frame配線 + 各キャラクターカードの「Generate a scene」UI
（`scene-view`、`chat-input-view` と同型）。

**未実装のまま残った項目**（Phase Aの残り、本ADR時点のまま）: TTS
（音声合成、murakumo側のモデル選定を含め follow-up）、broadcast の
シグナリング/SFU（`broadcast.cljs`/`web.cljs` は実キャプチャ済みだが
視聴者に届かない、ADR-2607051900の既存ギャップのまま）、計測
（murakumoリクエスト量のPhase切替ゲート用トラッキング）。

**検証**: `bb` 全9ゲート（governor/monetization/settlement/embodiment/
character/persona/roster-sync/extract-turn-json/scene、110チェック）、
`clj-kondo`（0 error/0 warning）、`shadow-cljs compile app`（新規コードに
関する warning無し）、既存の re-frame event/sub/view 回帰スイート
（`babiniku_ui_events_test.cljs`、137チェック）、いずれも green。
CI（`jk-luxury/net-babiniku` の4ジョブ）も merge前にgreenを確認済み。
