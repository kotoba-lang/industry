---
id: adr-2607110200-kawaraban-r0-r1-cloud-itonami-isco-3521-media-broadcast
title: "ADR-2607110200: kawaraban R0→R1 (live RSS ingest + aozora publish) + cloud-itonami-isco-3521 media/broadcast actor"
status: accepted
doc_type: adr
topic: actor-registration
authoritative: true
last_verified: 2026-07-10
implemented: 2026-07-10
implementation:
  repo: etzhayyim/com-etzhayyim-kawaraban
  submodule: orgs/etzhayyim/com-etzhayyim-kawaraban
  repo_2: cloud-itonami/cloud-itonami-isco-3521
  submodule_2: orgs/cloud-itonami/cloud-itonami-isco-3521
  landed_via: "kawaraban: live-fetch + aozora publisher code landed on existing west-registered repo (single-entry pin advance). cloud-itonami-isco-3521: new standalone public repo (blueprint-only, not west-managed, matching sibling cloud-itonami-isco-* convention). RAD identity: kawaraban.identity.journal.edn already carried the aozora-pds/aozora-collection fields (tx 4) — this ADR fulfills that pre-recorded intent, no new RAD entry needed for kawaraban; isco-3521 gets none (blueprint repos are not RAD-registered actors)."
authoritative_for:
  - "「aozora.app に世界中の news 情報を収集整理する news actor / media actor を設計統合する」というオーナー要求は、ゼロから2つの新規actorを作るのではなく、既存の kawaraban（etzhayyim、非営利・公共財、G1-G11憲章済み、54 tests green）を news actor として R0→R1 に進め、その公開出力（見出し+リンク+≤280字抜粋）を消費する新規の商業 media actor（cloud-itonami-isco-3521）を別レイヤーとして追加する、という判断"
  - "kawaraban 自身の憲章（G2: 広告/エンゲージメント最適化は表現不可能）と『商業ブループリント』枠は構造的に矛盾するため、商業化は kawaraban の外側（cloud-itonami の新規ブループリント）に置き、kawaraban のlexicon/cellは一切変更しない"
  - "kawaraban の live RSS/Atom 取り込みは既存の G4/G1/G3 ゲート（ingest.cljc の normalize-record/normalize-batch）をそのまま再利用する形で実装し、新しいゲート回避経路を作らない。KAWARABAN_ALLOW_LIVE_INGEST の既定値は変更せず（未設定 = 拒否のまま）、実運用での有効化は本ADRの範囲外の別途明示的操作とする"
  - "cloud-itonami は kawaraban の governance 主体にはならない（etzhayyim の意思決定権は etzhayyim のみ、CLAUDE.md）。cloud-itonami の関与は、kawaraban の公開出力を消費する下流の商業事業者としての関与に限定する"
related:
  - orgs/etzhayyim/com-etzhayyim-kawaraban
  - orgs/etzhayyim/root/90-docs/adr/2606061900-kawaraban-news-medium.md
  - orgs/etzhayyim/root/90-docs/adr/2606281500-etzhayyim-autonomous-publication-seed-and-grow.md
  - 90-docs/adr/2607022200-com-etzhayyim-tashikame-factcheck-aozora-actor-r0.md
  - 90-docs/adr/2607032300-aozora-per-actor-authority-firehose-appview.md
  - orgs/etzhayyim/root/80-data/kotoba-rad/kawaraban.identity.journal.edn
  - orgs/cloud-itonami/cloud-itonami-isco-3521
  - orgs/kotoba-lang/occupation/resources/kotoba/occupation/registry.edn
---

# ADR-2607110200: kawaraban R0→R1 + cloud-itonami-isco-3521 media/broadcast actor

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki

## Context

オーナー指示（2026-07-10）：aozora.app に cloud-itonami として世界中の news 情報を収集整理し、
news actor / media actor を設計・統合せよ。

調査の結果、ゼロから作るべきではないことが判明した:

- **kawaraban（瓦版、`orgs/etzhayyim/com-etzhayyim-kawaraban`）が既に news actor そのもの**だった。
  世界のニュース媒体を `headline + canonical link + ≤280字 fair-use excerpt` のみで mirror し
  （全文は決して保存しない）、各 etzhayyim actor の Datom as-of event を対応する 面（政治/経済/
  国際/社会/文化/科学/スポーツ）に投影して actor 間を繋ぐ「wire」でもある——まさに
  「collect + organize」。G1–G11 の憲章ゲート（広告禁止・エンゲージメント最適化禁止・読者監視
  禁止・全文転載禁止・真偽判定しない・なりすまし禁止 等）が lexicon の `const`/`enum` レベルで
  固定され、54 tests green。現状 **R0**（設計 + オフライン合成のみ）— 実 RSS/firehose 取り込み
  および aozora への実配線は、cell 自身のコード注記どおり **「Council ADR による ratification」**
  待ちの状態だった（`cells/outlet_ingest/state_machine.cljc` 等の `solve` が明示的にそう書いている）。
- kawaraban の RAD identity journal
  （`orgs/etzhayyim/root/80-data/kotoba-rad/kawaraban.identity.journal.edn`）は、
  tx 4 で既に `:rad/aozora-pds "https://pds.aozora.app"` と
  `:rad/aozora-collection "com.etzhayyim.apps.kawaraban"` を記録済み——aozora 配線は既に
  意図されていたが未実装だった。
- aozora への実際の投稿経路は **tashikame の前例**（`com.atproto.server.createSession` →
  `com.atproto.repo.createRecord`、depth-1 self-mint CACAO、custom lexicon はコレクション名
  としてのみ使用）で確立済み。ゼロから設計する必要はなく移植すればよい。
- **cloud-itonami としての商業化**をオーナーに確認したところ、kawaraban 自身の非営利憲章
  （G2: 広告/スポンサード配置/エンゲージメント最適化は lexicon `const`/`enum` レベルで表現不可能）
  と ISCO ブループリントの「ビジネスモデル」枠は構造的に矛盾する。オーナーの回答
  （「cloud-itonami 側は商業的に成立させたい」）を踏まえ、**kawaraban 自体は非営利のまま
  拡張し、その公開出力（既に著作権・エンゲージメント安全な headline+link+excerpt）を消費する
  商業レイヤーを cloud-itonami 側に別途新設する**という二層構造で解く。
- occupation registry（`orgs/kotoba-lang/occupation/resources/kotoba/occupation/registry.edn`）
  には ISCO `2642 Journalists` と `3521 Broadcasting and Audiovisual Technicians` が
  未公開の `:spec` stub として既に載っていた。`2642` を新規商業ブループリントにすると
  kawaraban（既に journalism を非営利で担っている）と直接競合・重複するため見送り、
  `3521`（放送/AV制作＝配信・二次制作の職種）を商業 media actor の受け皿として採用する。

## Decision

**二層構造。kawaraban の憲章は一切変更しない。**

### 1. kawaraban（etzhayyim）— news actor、R0 → R1

- `methods/live_fetch.cljc`（新規）: RSS/Atom を取得・パースし、**既存の
  `ingest/normalize-record` にそのまま通す**（G1/G3/G4 ゲートを再実装せず継承）。
  fetch 関数は注入可能（テストはローカル fixture のみ、本セッションで実インターネットへは
  発火しない）。
- `data/outlets/allowlist.edn`（新規）: 国営/公共放送・非営利プレスのみを厳選
  （NHK World-Japan・BBC News World・Deutsche Welle・France 24・RTHK・CBC News・
  ABC News Australia・NPR・PBS NewsHour・Associated Press・Al Jazeera・UN News）、
  各エントリは `outlet.edn` lexicon の `:access :open` / 許可された `:kind` のみ。
- `KAWARABAN_ALLOW_LIVE_INGEST` ゲートの意味・既定値（未設定 = 拒否）は変更しない。
  コードは完成・テスト済みだが、**実運用で本当に有効化するかは本ADRの範囲外の、
  別途明示的な運用判断**とする（root CLAUDE.md の ADR-2606072802 は「read-only public
  fetch を operator gate で塞ぐな」と一般則を述べるが、kawaraban 自身のコード注記は
  より具体的に Council ADR ratification を要求しており、本ADRがそのratificationに
  あたる——ただし「ratify する」ことと「今すぐ実インターネットに向けて起動する」ことは
  別。後者は founder/Council（= オーナー本人、現状 1/1）の別途の実行判断に委ねる）。
- `src/kawaraban/cacao.clj`（新規、`tashikame.cacao` の移植）+
  `src/kawaraban/aozora.clj`（新規、`tashikame.aozora` の移植、collection は
  RAD既記録どおり `com.etzhayyim.apps.kawaraban`）+
  `src/kawaraban/publisher.cljc`（新規、`tashikame.publisher` と同型、既定は
  MockPublisher）。
- 投稿（publish）自体は ADR-2606281500（種をまく、autonomous-by-default）に従い
  post 単位の Council 事前承認は課さない——構造的ゲート（G1/G3/G4/G7/G9/G11、
  既存の `route/validate` + 各 cell state machine）のみで律する。
- `MATURITY.md` を更新し、R1 到達の内実（live-fetch code-complete かつ既定 off）を明記。

### 2. cloud-itonami-isco-3521（新規公開リポジトリ）— media actor、商業

- ISCO-08 `3521` Broadcasting and Audiovisual Technicians。既存の `cloud-itonami-isco-*`
  テンプレート（README/GOVERNANCE/CONTRIBUTING/SECURITY/CODE_OF_CONDUCT/LICENSE/
  docs/business-model.md/docs/operator-guide.md/docs/samples/operator-console.html/
  blueprint.edn、コードなしのドキュメント専用ブループリント）に厳密準拠。
- 事業内容: kawaraban の**既に公開・著作権安全な**出力（headline+link+excerpt）を入力に、
  日次動画/音声ダイジェスト制作、シンジケーションAPI（帰属表示必須の「世界ニュース
  ティッカー」ウィジェット）、ホワイトレーベル編集キュレーションを商業提供する。
  kawaraban の lexicon・cell には一切触れない。全文スクレイピング・誤帰属・kawaraban
  へのなりすましは Media Broadcast Governor が拒否する。
- **cloud-itonami は kawaraban の governance 主体にはならない。** etzhayyim の意思決定権は
  etzhayyim only（宗教法人としての自己主権、root CLAUDE.md）であり、gftdcojp 系の
  business-OS である cloud-itonami が別法人格の内部ガバナンスを持つのは cross-org の
  category error。cloud-itonami の関与は、公開済み出力を消費する下流商業事業者としての
  関与のみ。
- west 管理外（既存 isco-* 兄弟リポと同じ慣習——「blueprint repos are not
  west-managed」）。RAD identity 登録もしない（actor ではなく事業ブループリント）。

## Consequences

- kawaraban は非営利・公共財のまま——広告/エンゲージメント最適化がその内部に紛れ込む
  リスクを構造的に排除できる。
- cloud-itonami は kawaraban を fork/再実装せずに正当な商業事業を持てる。
- ISCO `2642 Journalists` の商業ブループリントは作らない——kawaraban が既にその役割を
  非営利で満たしており、重複構築を避ける。registry の `2642` は `:spec` のまま
  変更しない。
- **実運用の go-live（実インターネットへの live RSS 取得の実際の有効化、および
  実 aozora.app PDS への実投稿）は本ADRの範囲外**——本リポジトリの既存の慣習
  （ADR-2607110100 adnetwork の `:real-production-gate` と同型）に倣い、実運用配線が
  確認された後の別途明示確認ステップとする。

## Alternatives considered

- **ISCO `2642 Journalists` で新規商業ブループリントを作る** — 却下。kawaraban と
  直接重複し、kawaraban の非営利憲章と衝突する。
- **kawaraban 自体を cloud-itonami 傘下に取り込む/移管する** — 却下。etzhayyim の
  自己主権（意思決定権 = etzhayyim only）を侵害する cross-org の category error。
- **kawaraban を R0 のまま放置し cloud-itonami 側だけ作る** — 却下。isco-* ブループリントは
  ドキュメント専用の慣習であり、それだけでは「収集整理」という要求を満たす実体（実際に
  ニュースを集めて aozora に流す経路）が生まれない。

## Relationship

- **builds on** ADR-2606061900（kawaraban 設計）, ADR-2606281500（自律投稿ドクトリン）,
  2607022200（tashikame の aozora 投稿前例）。
- **fulfills** kawaraban RAD identity journal tx 4 の既記録意図
  （`:rad/aozora-pds` / `:rad/aozora-collection`）。
- **does not modify** kawaraban の lexicon（`lex/*.edn`）・憲章ゲート・G1–G11。

## Addendum (2026-07-10, 共有 operator graph の CPU 時間制限読み込み障害の解消)

本ADRに従い live RSS ingest + aozora publish を実際に有効化した結果（同日オーナー承認
「1, 2」「生成された news を https://aozora.app/ に actor ごとに投稿して」）、kawaraban の
per-outlet mirror actor 群と cloud-itonami の media actor が共有の operator graph
（`yoro-social-v2`、`YORO_OPERATOR_DID`＋`YORO_DB_NAME` から導出）へ数百件規模の
`com.atproto.repo.createRecord` を同日中に集中発行した。これにより novelty（未 fold の
tx-block）が急増し、`pds.aozora.app` の `listRecords`/`getRecord` が 15–40 秒超でハング、
直接 `kotobase.aozora.app` を叩くと Cloudflare error 1102（CPU time exceeded）で 503 する
状態になった——「今日の大量書き込みによって現在の実装の CPU 時間制限内では読み込めない
サイズ/状態になっている」というオーナー指摘のとおり。

3層にわたる根因と対処:

1. **`kotobase-peer/src/kotobase_peer/core.cljc`**（library、`kotobase-cljc-worker` の
   source-path 依存）: `pmap-async`（novelty tx-block の並行 R2 fetch ヘルパー）が
   無制限の単一 `js/Promise.all` で全件を同時発火していた（数百 fetch が同時に飛ぶ）。
   24件ずつのバッチに区切るよう変更。実装中に reader-conditional の構文ミス
   （`#?(:cljs form1 form2)` は `:cljs` を `form1` とだけ対にし `form2` を未知の
   feature key として無言で drop する）で一度 `pmap-async` が未定義になる回帰を作ったが
   `npm run test:cljs`（78 tests / 165 assertions）で検出・修正。
2. **`kotobase-cljc-worker/wrangler.jsonc`**: 実測すると真のボトルネックは
   R2 I/O 待ち（wall-clock）ではなく同期的な decode/merge の **CPU 時間**だった
   （batching だけでは直らず、engineから見て正しい graph CID への直叩きが
   Cloudflare error 1102 で 503）。`limits.cpu_ms` が未設定（プラン既定）だったのを
   Workers Standard usage model の上限 `300000`（5分）へ明示設定。
3. **fold cron**（`app-aozora-pds`、既存の `*/5 * * * *` Cron Trigger、
   `FOLD_CRON_ENABLED=1`・`OPERATOR_SECRET` 設定済み・変更なし）: 2 の CPU 予算拡張後、
   次回定期実行で novelty backlog を新しい cold snapshot に fold できることを確認
   （operator secret へのアクセスや手動 fold トリガーは不要——既存の自動運用がそのまま
   機能した）。

検証（fold cron 実行後）: `kotobase.aozora.app` への graph 直読みが 129秒（fold 前、
拡張後 CPU 予算の範囲内でギリギリ完走）→ **7.2秒**、`pds.aozora.app` の
`listRecords`（kawaraban bbc-world mirror）が ハング（15–40秒超）→ **4.4秒**まで復帰。
実データ（BBC-World の実記事）が失われていないことも確認済み。

- **does not modify**: kawaraban/cloud-itonami media actor のいずれの lexicon・
  governor・charter ゲートも変更なし——本addendumは純粋に基盤（kotobase-peer /
  kotobase-cljc-worker の性能・容量特性）の修正であり、G1–G11 や Media Governor の
  意味論には触れていない。
