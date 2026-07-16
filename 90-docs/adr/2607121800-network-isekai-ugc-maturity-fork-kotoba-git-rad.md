# ADR-2607121800: network-isekai の Fortnite/Roblox ギャップ成熟度評価と、fork 公開層を kotoba-git + kotoba-rad(git repo)前提へ再方向付け

**Status**: accepted（方向決定。実装マイルストーン M0〜M3 は未着手）
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki（指示: 「fortnight, roblox に対する gap, 成熟度としてまとめて adr を構築, fork などは git repo を kotoba-lang/git, rad or radicle につくる前提で」）

## Context

### 用語の整理（2つの前提訂正）

1. **「isekai-network」はプラットフォームを指す場合 `gftdcojp/network-isekai`
   （本番 isekai.network）が正**。旧 `gftdcojp/isekai-network` repo（Shiro &
   Pico ゲーム単体）は ADR-2607032400 で network-isekai に統合・archive 済み。
   本 ADR の対象は network-isekai プラットフォーム。
2. **「kotoba-lang/git」という repo は存在しない**（GitHub 404 確認済み、
   2026-07-12）。オーナー指示の「kotoba-lang/git, rad or radicle」の実体は
   **`kotoba-lang/kotoba-git`（git 相当層）+ `kotoba-lang/kotoba-rad`
   （Radicle 相当層）**（ADR-2607072200、両方 west manifest 登録済み）。

### network-isekai の現状（2026-07-12 実測。ローカル main は origin と同期、上流 push は 2026-07-10）

- **公開済みゲーム**: `public/games/` に gftd 32 + itonami 8 = **40 games**
  （ADR-2607091830 の 2026-07-09 実測 31 から増加）。isekai.network で本番
  配信中（Cloudflare Pages）、aozora.app への `?embed=1` iframe 再生 + game
  ごとの unkeyed Work Actor 化も着地済み（ADR-2607091830）。
- **3D world は実在する**: 例 `gftd/royale`（KAMI Royale）は 3rd-person
  カメラ・重力/ジャンプ・PBR マテリアル・空/フォグ・シード付き手続き配置
  （建物280 + 樹木の deterministic xorshift scatter）を全部 `scene.edn` の
  EDN データで持ち、kami-web WASM（WebGPU、WebGL2 fallback）で描画される。
- **ブラウザ内 edit→compile→run**: kototama Rust wrapper 削除で壊れた
  コンパイル経路（issue #49）は PR #72（kotoba.engine-clj CLJS コンパイラ
  fallback）が 2026-07-03 に merge され復旧済み。
- **AI 生成は asset 単位で実働**: `/generate.html` → `POST /api/gen/:stage`
  （Modal GPU）。photo→3D（TRELLIS）が real、text→image / TTS / music は
  skeleton（`src/isekai/gen.cljc` の stages 表に明記）。
- **fork の「公開」が未配線（本 ADR の核心 gap）**: `src/isekai/fork.cljc` は
  fork を content-addressed EDN bundle として設計済みだが、pin は
  `window.kotoba.pin` の存在が条件で、**kotoba クライアントはサイトの
  どこにも load されていない**。`src/isekai/web.cljc` に「forks aren't
  durably shareable yet」と明記され、`?fork=` リンクは base game に
  fallback する。つまり訪問者の fork は他人に届かない — 現状の公開経路は
  owner が git 経由で `public/games/` に置くことだけ。
- **fork 前提のメカニズムは既にコードとして存在する（配線待ち）**:
  - `feed_index.cljc` — fork-graph ROOT の discovery index（「local
    stand-in for the kotoba durable fork log: once `window.kotoba.pin` is
    wired…」と自己申告）。
  - `fork_stats.cljc` — BMC gate 用の viral-coefficient / fork-origin-ratio。
  - `economy.cljc` — gem 経済。**sybil gate**（`:verified true` clear のみ
    mint）と **royalty decay up the DAG**（`:ev/fork→:ev/parent` を遡って
    祖先に royalty）— fork DAG が実在することを前提に書かれている。
  - `moderation.cljc` — 状態機械（:pending→:approved/:flagged→:removed）の
    メカニズムのみ、policy 未接続（ADR-0009 Phase 0）。
  - リーダーボードはサーバ検証済み（`/api/scores`、D1、PR #113）。
- **本番ホーム**（isekai.network、2026-07-12 fetch）: Editor / Studio /
  Assets / Generate / Discover を掲示、「Everything, ranked by the fork
  graph」と謳う — が、上記の通り fork graph の永続層は未配線。

### kotoba-git / kotoba-rad の現状（ADR-2607072200 + 同日 addenda、2026-07-07 検証）

- `kotoba-git`: blob/tree/commit を **arrangement の quads として
  datom-native に保持**（subject = content CID）、N-parent commit DAG
  （merge 表現可）、refs、`ancestors`/`log`/`missing-since`（object
  negotiation）、ref-policy（`fast-forward?` / `set-ref-guarded!`）。
  JVM + **real ClojureScript CI**（shadow-cljs node-test）green。
- `kotoba-rad`: RID（genesis CID）、delegate 台帳（Ed25519 署名検証）、
  sigref（署名付き ref→commit-cid）、`authorize-push?`、CACAO delegation
  chain（`authorize-push-cacao?`）、p2p signed head-announce は
  `kotoba-lang/p2p` と **cross-repo E2E で実検証済み**（unsigned announce
  拒否まで確認）。
- どちらも**本番 surface には未配線**（ADR-2607072200 が明記）— 本 ADR が
  最初の実配線先を与える。

### Fortnite / Roblox 側の比較基準（一般知識。本セッションでの実測ではない）

- **Roblox**: Roblox Studio（フル 3D DCC 級エディタ）→ 即時 publish、
  組み込みマルチプレイ（レプリケーション標準装備）、Robux + DevEx の
  創作者経済、大規模モデレーション、生成 AI 制作支援。
- **Fortnite（Creative / UEFN）**: UEFN（Unreal Editor）で island を制作・
  公開、engagement payout の創作者経済、100人級 netcode、AAA 品質描画。

両者に共通する本質は「**訪問者が創作者になれて、その成果物が永続的に
公開・発見・収益化される UGC ループ**」であり、描画品質単体ではない。

## 成熟度評価（gap matrix）

レベル定義: **L0** 無し / **L1** 設計・コードは在るが未配線 / **L2**
プロトタイプ動作 / **L3** owner 運用で本番動作 / **L4** 訪問者にも本番動作 /
**L5** プラットフォーム規模（Fortnite/Roblox 級）。

| 軸 | network-isekai 現状 | Lv | Fortnite/Roblox |
|---|---|---|---|
| 3D world 描画 | WebGPU + PBR + 手続き配置。primitives（box/sphere/cylinder）中心、skinned mesh は placeholder | **L3** | L5（AAA / 大規模 UGC 描画） |
| 制作ツール | ブラウザ内 EDN + logic エディタ（CodePen 型）、AI pair。ビジュアル 3D エディタ無し | **L3** | L5（Studio / UEFN） |
| 生成 | 手続き配置（seed 付き EDN）実働 + photo→3D（TRELLIS）実働。prompt→world は無し | **L2–L3** | L3–L4（生成 AI 制作支援） |
| **公開/UGC ループ** | **M1 gate 8/8 通過（addendum 2）**: fork publish → 別ブラウザ・別 identity で `?fork=<CID>` 再現を実 Pages Functions + D1 相手に検証済み。本番反映は owner の deploy のみ | **L4**（コード検証済み） | L5（即時 publish） |
| マルチプレイ | bot + サーバ検証リーダーボード。realtime netcode 無し（dance presence は設計のみ） | **L1** | L5 |
| 識別/ソーシャル | **訪問者ごとの自己発行 did:key**（Ed25519 non-extractable、IndexedDB — addendum 2/6）が fork の署名主体に。game Work Actor（aozora 側）は従来どおり。keyed actor 昇格は未着手 | **L3** | L4–L5 |
| 経済 | **verified gem が Discover に表示・ランキング寄与（addendum 5）**: royalty decay up the REAL DAG、sybil gate 実働、/api/events ledger export。実通貨/payout は measurement のまま | **L3** | L5（Robux/DevEx・payout） |
| モデレーション | **通報 intake + distinct-reporter auto-flag + feed 可視性 enforcement + owner review 経路（addendum 8）**。policy 中身は owner+counsel | **L2–L3** | L5 |
| 発見/配信 | **live fork graph が rank を駆動（addendum 3）**: fork-of-fork の実系譜・gem シグナル・viral coefficient 実測。aozora embed は従来どおり | **L4** | L5 |

**総括(2026-07-12 loop 9 iterations 後の再評価。以下の初版総括は起票時点の記述として保存)**:
起票時に L1 で律速していた公開/UGC ループは **L4(コード検証済み)**まで
上がり、それを待って停止していた発見(L4)・経済(L3)・moderation
(L2–L3)・識別(L3)が連鎖的に実働した — 「fork 永続層を解けば L1 群が
連鎖的に配線可能」という起票時の見立てどおりの展開。**本番 isekai.network
での有効化に残るのは owner の deploy 手順のみ**(functions/api/README.md)。
Fortnite/Roblox との残差で大きいのは マルチプレイ(L1)と描画/制作ツールの
質(L3)で、どちらも本 ADR のスコープ外の別 ADR 級プロジェクト。

**初版総括**: 「1人の owner がデータ駆動で 3D game/world を量産し公開する
プラットフォーム」としては **L3**（40 games 本番稼働で成立済み）。
「Fortnite/Roblox のような UGC プラットフォーム」としては、**公開/UGC
ループが L1 で律速**しており、economy・discovery・moderation の実装済み
メカニズム群がすべて「fork の永続グラフ」の実在を待って停止している。
つまり**律速段は描画でもエディタでもなく fork 永続層**であり、そこを
解けば L1 群（経済・発見・モデレーション）が連鎖的に配線可能になる。

## Decision

### 1. fork の永続層は「git repo」— kotoba-git + kotoba-rad を正とする

当初設計の `window.kotoba.pin`（kotoba pin クライアント配線）は追わない。
fork・公開・fork graph は **kotoba-git（content-addressed git 相当の
object DAG + refs）+ kotoba-rad（RID / 署名 / push 認可）の上に作る**
（オーナー指示の「git repo を kotoba-lang/git, rad or radicle につくる
前提」の解決先）。

### 2. マッピング（fork 概念 → git 概念）

- **1 game（root）= 1 repo**: RID = kotoba-rad genesis（`{did, created}`
  の CID）。既存 40 games が初期 root 群。
- **fork = commit**: tree = `{game.edn, scene.edn, logic.cljc}`（fork
  bundle `{:fork/game :fork/scene :fork/logic}` と同内容）、
  `commit/parents` = [親 fork の head commit CID]。**fork graph は
  メタデータではなく実 commit DAG そのもの**になり、
  `kotoba-git.log/ancestors` がそのまま系譜クエリ、`feed_index` の
  stand-in と `economy.cljc` の royalty decay（`:ev/fork→:ev/parent`）が
  実グラフに載る。
- **公開 = 署名付き ref 更新**: creator の did:key による sigref
  （ref→commit-cid attestation）+ `authorize-push?`（または CACAO 委任
  チェーン `authorize-push-cacao?`）を通った push だけが公開される。
  fast-forward-only は `set-ref-guarded!`（identity + shape policy 合成）
  で強制。
- **共有 URL = content-addressed**: 既存 `?fork=<djb2-hash>` の意味を
  `?fork=<commit-CID>` に置換（immutable・改竄不能。djb2 は依存ゼロの
  暫定クライアント id と fork.cljc 自身が明記しており、置換は設計の
  完成であって破壊ではない）。
- **creator identity = did:key self-mint**（build-actor skill の CACAO
  自己発行規約と同系）。共同編集は kotoba-rad.delegate（台帳）または
  CACAO delegation chain（journal-free）のどちらでも表現できる。
- **訪問者の fork はまず local**（ブラウザ内 arrangement db）、「Publish」
  で初めてサーバへ push（署名 + 認可）。play/edit はオフラインで完結する。

### 3. Radicle 実物（radicle.xyz / heartwood）は採用しない

- heartwood は Rust node 常駐 + 実 git（SHA-1/packfile）wire 互換が前提。
  ADR-2607072200 は git-CLI wire 互換を明示的に scope 外とし、CLAUDE.md
  の方針（Rust を新規インフラとして書かない・runtime 優先順位）とも整合
  しない。
- ecosystem は既に同等 primitives（RID / delegate / sigref / push-gate）を
  **CLJC + real CLJS CI 付きで所有**しており、ブラウザ内 fork という
  network-isekai の中核要件には kotoba-git/rad の方が適合する。
- 実 Radicle との bridge（rad seed node への mirror 等）は将来の別 ADR。

### 4. 「1 fork = 1 GitHub repo」も採用しない

rate limit・アカウント要求・秒単位 fork UX への不適合・主権（sovereign
repo identity）の欠如。GitHub は owner 側の root game 開発フローに留める。

## Milestones（実装は本 ADR に含まれない。着手時に個別 PR / 必要なら個別 ADR）

- **M0 — fork write path**: fork bundle → kotoba-git
  `write-blob/tree/commit` → commit CID をブラウザ CLJS で round-trip。
  **要検証**: kotoba-git/arrangement のブラウザ実行（CLJS CI は
  node-test — browser 統合は未検証）と `persist!` の browser 永続先
  （IndexedDB 等）。Gate: 既存 `bb` gate 群と同型の data-level 検証 +
  実ブラウザ round-trip。
- **M1 — push/publish サービス**: 署名付き head-announce を受ける
  endpoint（Cloudflare Pages Functions が既に `/api/session`/`/api/scores`
  で先例。kotobase.net 案との選定は M1 冒頭で決める）+ サーバ側
  `authorize-push?`。`?fork=<CID>` が**別ブラウザ・別人で再現**したら
  gate 通過 — この時点で公開/UGC ループが L1→L4 に上がる。
- **M2 — 実 fork graph の discovery**: feed_index の stand-in を実 DAG に
  差し替え、Discover の「ranked by the fork graph」を実測化
  （fork_stats の viral-coefficient が実データになる）。RID を aozora.app
  Work Actor（ADR-2607091830）に紐付け。
- **M3 — 経済・keyed actor・moderation 接続**: economy.cljc の sybil gate
  を `/api/scores` の verified clear と接続し、royalty decay を実 DAG で
  駆動。keyed CACAO actor 昇格（ADR-2607031400 の温存案の再訪）。
  moderation の policy 接続（owner+counsel 事項、ADR-0009）。
- **明示的 out of scope**: realtime multiplayer（別 ADR。本 ADR の軸では
  L1 のまま）、prompt→world 生成（photo→3D の先、別 ADR）、実 Radicle
  bridge。

## Consequences

- kotoba-git / kotoba-rad が**初の本番 surface**を得る（ADR-2607072200 の
  「Neither repo is wired into any production surface」を解消する経路が
  確定）。逆に network-isekai が kotobase-peer スタックの churn（直近の
  3 rename 等）に依存することになる — deps は `:git/sha` pin を踏襲する。
- fork.cljc / web.cljc / feed_index.cljc の「kotoba pin 待ち」コメント群は
  本 ADR の方向（git repo 化）に読み替える。`window.kotoba.pin` 前提の
  配線作業は開始しない。
- 成熟度マトリクスが以後の優先順位判断の共通言語になる: 描画やエディタの
  磨き込みより **公開/UGC ループ（L1）** が先、はデータで示されている
  （economy/fork_stats/moderation がすべて fork graph 待ちで停止中）。
- 40 games の既存公開フロー（owner が git → Pages）は無傷 — 本 ADR は
  訪問者側の fork 公開を追加するだけで、既存導線に非影響。

## Alternatives considered

1. **当初設計どおり kotoba pin（`window.kotoba.pin`）を配線する**:
   却下 — pin は blob 単位の永続化で、fork の系譜（DAG）・署名・認可を
   別途作ることになる。kotoba-git/rad はその全部を既にテスト済みで持つ。
   オーナー指示も git repo 前提を明示。
2. **実 Radicle（heartwood）採用**: 却下（Decision §3）。
3. **1 fork = 1 GitHub repo**: 却下（Decision §4）。
4. **fork を D1（既存 leaderboard DB）に保存**: 却下 — 中央 DB 行は
   content-addressed でも署名可能でもなく、「Everything, ranked by the
   fork graph」の fork graph を再発明することになる。D1 は M1 の endpoint
   実装詳細としてなら選択肢に残る（保存形式は kotoba-git object）。

## Verification

**2026-07-12 に実測した事実**: network-isekai ローカル main（origin 同期、
上流 push 07-10）の `public/games/` 40 件、`fork.cljc`/`web.cljc`/
`feed_index.cljc`/`economy.cljc`/`fork_stats.cljc`/`moderation.cljc` の
記載、royale `scene.edn` の 3D world データ、issue #49 closed / PR #72
merged（2026-07-03）/ PR #113 の存在（gh API）、isekai.network 本番ホームの
掲示内容（fetch）、`gftdcojp/isekai-network` の archive 状態と
`kotoba-lang/git` の不存在（gh API 404）、kotoba-git/kotoba-rad の west
manifest 登録と直近コミット（CLJS CI、set-ref-guarded! 等）。

**未検証（M0 の検証項目）**: kotoba-git スタックのブラウザ実行・永続化、
Cloudflare 環境での arrangement 動作。Fortnite/Roblox 側の記述は一般知識
であり本セッションの実測ではない。

## Addendum (2026-07-12, same day): M0 landed — cross-runtime CID 決定性は実ブラウザで検証済み、しかも初回実行で本物のバグを捕獲

M0 は network-isekai PR #130（merged 2026-07-12, `902f890`）で着地した。

**実装**: `src/isekai/fork_git.cljc`（fork bundle → blob×3 / tree / commit、
`read-fork` round-trip、`lineage`/`ancestors` = 実 commit DAG クエリ）。
kotoba-git は root `:deps` に入れず（`:app` への blast radius ゼロ）、
`:shadow`/`:fork-git-e2e` alias + bb.edn `:deps`（git/sha pin `f7ecffb`）経由。

**Gate（どちらも repo 常設・CI 配線済み）**:
- `bb fork-git` — 11/11。round-trip / 決定性 / fixture CID pin / lineage /
  full-DAG ancestors（merge 含む）/ ff-only ref policy / `persist!`。
  kotoba-git スタックは **babashka でそのまま全面動作**（スパイクで確認して
  から gate を bb 直実行にした — CI gates job に追加 checkout ゼロ）。
- `bb fork-git-e2e` — 12/12。実 headless Chromium（`release fork-git-btest`
  build を file:// で自走）と JVM が同一 fixture から計算した commit CID の
  バイト一致を層別（blob / manifest / tree / commit fields）に assert。
  Promise 経路の `persist!`（in-memory `(put! cid bytes)`、5 blocks）も動作。

**初回実行で捕獲した本物のバグ**: JVM/bb とブラウザで commit CID が分岐した。
層別診断で `fork.edn` manifest blob に局所化 — **`pr-str` は canonical
serializer ではない**。JVM は `*print-namespace-maps*` が既定 true で
`#:fork{:format 1 :game …}` と短縮印字し、cljs は既定 false で
`{:fork/format 1 …}` と印字する。同じ値・違うバイト列・違う CID —
共有 `?fork=<CID>` URL を静かに壊す分岐そのもの。修正は
`isekai.fork-git/canonical-pr`（printer 設定を pin した pr-str）。fixture pin
は canonical 値 `bafyreign5kxw2dhrmvnuhvurnuvioexsdcnhwt3cg4fuxcpjsvv4j27qge`
に更新し、manifest 層の比較は gate に常設した。教訓の一般化: 構造データは
DAG-CBOR（構築的に canonical）を通るが、**可読 EDN テキスト自体がコンテンツに
なる箇所では ambient printer 状態を絶対に信用しない**。なお kotoba-git 側の
object encode（blob raw / tree / commit の DAG-CBOR、ts int、parents links）は
全層クロスランタイム一致 — 本 gate が kotoba-git にとっても初のクロス
ランタイム CID 等価性検証になった（upstream に fixture pin を持たせるのは
follow-up 候補）。

**M0 判定の更新**: 「kotoba-git/arrangement のブラウザ実行」は**検証済み・
動作確認**へ。残る open item は (1) `persist!` の耐久永続先
（IndexedDB adapter — M1 の server push と合わせて設計）、(2) 上記 upstream
fixture pin、(3) **gftdcojp/network-isekai は GitHub Actions が repo 設定で
無効**（`actions/permissions enabled:false` — bb-gates は定義済みでも走らない。
既存 merge 済み PR 群も同様の運用。CI を実効化するには owner による Actions
有効化が必要）。

**付随修正**: deps.edn/bb.edn の `kami-engine-clj` sibling path を
ADR-2607032100 の統合先（`kami-engine/kami-engine-clj`）に追従 — 旧 standalone
path は消滅済みで、clean checkout の classpath 解決が壊れていた（visual-
capture-e2e CI job も同因で壊れていた）。CI には gates job の `bb fork-git`
step と、visual-capture-e2e job への kotoba-git checkout + ブラウザ gate
（`:app` build より**前**に配置 — app 側の失敗に隠されないため）を追加した。

## Addendum 2 (2026-07-12, same day): M1 landed — 正式 gate 8/8 通過、公開/UGC ループは L1→L4(コード検証済み)

M1 は network-isekai PR #132(server 半分)/ #133(client 半分 + main の
:app build 修復)/ #134(正式 gate)で着地した(#135 は混入した
`.wrangler/` ローカル state の untrack)。

**正式 gate(`bb fork-publish-e2e`、PR #134)— ADR の M1 判定条件そのもの**:
分離された 2 つの Playwright browser context(別 IndexedDB = 別の自己発行
did:key)を、**実物の backend**(`wrangler pages dev` が実 Pages Functions を
実 local D1 + 実 schema で実行)に当てて 8/8 green:
A が自分の did:key で署名 push → A の block が content-addressed に配信 →
**B が `?fork=<CID>` URL だけから A の fork を再現**(bundle 等価)→ A≠B の
identity → B が A の fork をさらに fork し cross-identity な parent edge が
wire を越えて保存 → 改竄署名 403 / 未 push CID 404(live 否定系)。

**実装の要点**:
- server(#132): `/api/fork` = 検証付き content-addressed block store +
  owner 署名 ref head(`<did:key>/<org>/<slug>` の自己主権 namespace、
  署名済み単調 seq で rollback/replay 拒否 — trust model は
  `_lib/fork-store.mjs` に正直に明記。kotoba-rad delegate/CACAO chain は
  M2+ の昇格路)。`bb fork-push-guard` は kotoba-git 実測 CID との
  **cross-implementation conformance vector** を含む。
- client(#133): `isekai.fork-git-client` — did:key を WebCrypto Ed25519 で
  自己発行(秘密鍵 non-extractable のまま IndexedDB へ構造化クローン)、
  `publish!`/`hydrate`。`isekai.fork-git` に transport 層
  (`export-blocks`/`import-blocks`、全 object CID self-check)。
  `isekai.web` の ⑂ Fork は durable publish + hydrate した fork の CID が
  次 publish の parent(訪問者を跨ぐ実系譜)。kotoba-git は root :deps へ
  昇格(M0 の予告どおり)。

**e2e が捕獲した本物のバグ(unit test では到達不能の endpoint 糊層)**:
1. fork.js が `checkAndBumpRateLimit` の boolean 契約を `{ok}` と誤読 —
   **許可されるべき全 push を 429**。
2. `fork/[cid].js` が D1 の BLOB 列(素の JS Array)をそのまま `Response()`
   へ — バイト列が文字列化され B の hydrate が
   「cbor: unexpected end of input」で死亡。`new Uint8Array(row.bytes)` で修正。
また Playwright 同梱 Chromium 131 に WebCrypto Ed25519 が無い
(Chrome 137〜 default。現行 stable は搭載)ため、gate の Chromium は
`--enable-experimental-web-platform-features` で起動。unhandled rejection が
無言 hang になる問題も outer `.catch` で surface 化した。

**付随修復(main で既に壊れていたもの)**: physics-2d 追加による classpath
比較不能(`physics` の git/sha vs :local/root)→ top-level pin、physics-2d
自体の `(ns physics_2d …)`(JVM 可・shadow-cljs 拒否)→ **upstream fix
(kotoba-lang/physics-2d PR #2)** + pin bump。`:app` は
Build completed(150 files)に復帰。

**成熟度更新**: 公開/UGC ループ **L1 → L4(コード検証済み)**。isekai.network
本番での L4 化に残るのは owner の deploy 手順のみ:
`wrangler d1 execute isekai-scores --file=scripts/isekai/d1-schema.sql --remote`
→ `SCORE_HMAC_SECRET` 確認(scores 用に設定済みなら共用)→
`npx shadow-cljs release app && npx wrangler pages deploy public`。
これは外向きの本番変更なので本 ADR からは指示しない(owner 判断)。

**M2 に向けた残点**: 実 fork graph の discovery 接続(feed_index の
stand-in 差し替え)、`persist!` の IndexedDB adapter(local-first fork の
耐久化)、kotoba-rad sigref/push-gate の本採用(現 M1 は同型の最小実装)、
economy の sybil gate 接続。

## Addendum 3 (2026-07-12, same day): M2 discovery 着地 — 「ranked by the fork graph」が実データに

M2 の discovery 半分は network-isekai PR #136 で着地(gate 10/10)。
`GET /api/fork/feed`(公開 fork の live 一覧)+ `isekai.fork-feed`
(pure 変換: 親なし fork は `k1root-<slug>` の root に接木 — root game が
remix の fork-graph credit を得る/fork-of-fork は commit CID の親辺を保持)
+ `isekai.home` が build-time roots index と live graph を merge してから
`feed/rank` — **fork-graph シグナルと `fork_stats` viral coefficient が
実測になった**。`game`/`parent` 列は push 時の client 主張の表示メタデータ
と正直に位置づけ(真実は署名済み commit block。economy の royalty 等
ancestry に支払う消費者は block 検証必須 — schema/endpoint に明記)。
e2e は fresh local D1 化 + feed 検証 2 件を追加して 10/10。driver の
プロセス寿命バグ(`System/exit` は finally を走らせず orphan wrangler が
port を掴んで次 run を hang させる)も修正。既知の外部要因: 検証時点で
並行セッションが `kami.netsync` 提供元 sibling を改修中のため repo 全体の
`:app` target は一時 unbuildable(本 diff 経路とは無関係。`isekai.home`
単独 build で本 diff のコンパイルは証明済み)— 落ち着いたら再確認。

## Addendum 4 (2026-07-12, same day): M3 groundwork — economy ledger export 着地

network-isekai PR #137(node gate 21/21)。`GET /api/events` が economy の
ledger を実データで export: verified clear(sybil gate は上流の
score-verification-hardening が既に強制 — verified "1" 以外は datoms 表に
存在しない)+ 公開 fork 系譜(親なしは `k1root-<slug>` に接木、
isekai.fork-feed と同一規則)。shape は `isekai.events/from-server` /
`isekai.economy` の pure fold(parent-map / ancestors / gem-attribution /
royalties)がそのまま食う string-attr datoms — **royalty decay up the DAG に
実入力が通った**。`POST /api/scores` は optional `fork`(CID 検証付き)を
verified clear に紐づける。economy は economy.cljc 自身の宣言どおり
MEASUREMENT に留め、mint/payout の永続化は別途の意思決定として温存。
経済軸の成熟度: L1(コード実在・未配線)→ **L2–L3(実データで畳める。
UI 表示と payout が残り)**。README も実装済みの姿に現行化した。
:app build は並行セッションの kami.netsync 移設が未決のため未再確認
(本 PR は CLJS 経路に非接触)。

## Addendum 5 (2026-07-12, same day): gem economy が Discover に接続 — 経済軸 L3(表示・ランキング実働)

network-isekai PR #139(gate 27/27)。Discover は 3 系統(roots index /
live fork graph / economy ledger)を merge して rank し、**verified gem が
カードに 💎 表示され、`:gems` シグナルがランキングに効く**(mint-on-read の
`econ/derive-awards` — 同じ ledger → 同じ gems、永続化なし。payout の
永続化は引き続き別決定)。economy.cljc / events.cljc は
reader-conditional 化で**真に portable** になり、bb gate がブラウザと同一
コードで全 pipeline を畳む(+6 checks: base mint / 1-hop decay / 2-hop
消滅 / 決定性 / sybil ゼロ mint / rank-by-gems)。

**gate が捕獲した実バグ(累計 4 件目)**: `economy/parent-map` が
`:ev/fork` を持つ**全**エンティティを畳んでいたため、fork 上の clear/gem
イベント(親を持たない)が実系譜の辺を nil で上書き。/api/events が clear
に fork 辺を載せた途端に royalty check が落ちて発覚 — `:ev/kind` fork
限定に修正。経済軸の成熟度: L2–L3 → **L3(実データで表示・ランキング
実働。実通貨/payout は未着手のまま = L5 との残差)**。

## Addendum 6 (2026-07-12, same day): local-first drafts — M0 open item「persist! の browser 永続先」を closed

network-isekai PR #140(full-loop gate 11/11)。draft は arrangement db の
snapshot(rehydrate 半分が upstream 未公開、ADR-2607072200)ではなく、
**ネットワークと同一の検証付き wire blocks**(export-blocks → IndexedDB、
IndexedDB → import-blocks の CID 再導出)として保存 — 実証済み transport
層の第 3 のバックエンド(HTTP push / HTTP hydrate / IndexedDB)。壊れた・
古い storage は復元されず throw する。実 Chromium + 実 IndexedDB で
save → CID 検証付き load → drop → nil を gate 検証。identity DB は v2 に
bump(drafts store 追加、v1 訪問者と新規が同形に収束)。M0 open item の
うち「persist! の耐久永続先」はこれで closed(残: kotoba-git への
cross-runtime fixture pin 上申、repo Actions 有効化 = owner 判断)。

## Addendum 7 (2026-07-12, same day): upstream conformance pin 上申 — content addressing の破壊的変更は upstream で先に落ちる

kotoba-lang/kotoba-git PR #1(merged、JVM + CLJS 両スイート 40 tests /
86 assertions green): 本 ADR の gate 群が 4 実装(JVM / bb / 実 Chromium
CLJS / 独立 JS 再実装)でバイト一致を検証した golden CID vectors を、
**kotoba-git 自身のテストスイートに恒久 pin**。multiformats/cbor/ipld の
canonical encoding が変われば upstream の CI が先に落ち、共有済み
`?fork=<CID>` URL が野で迷子になる前に「移行イベント」として顕在化する。
network-isekai の bb pin を ed610906 に前進(PR #141、gate 27/27)、
superproject west.yml も single-entry API commit(`074f0b64`)で前進 —
ローカル全体再生成は無関係な sibling 群の stale checkout による pin 退行を
生成器が正しく拒否したため、repos.edn `:manifest-workflow` の正経路
(blob-SHA 一致 PUT)を使用。M0 open items はこれで「repo Actions 有効化
(owner 判断)」のみ。

## Addendum 8 (2026-07-12, same day): moderation 配線 — ADR-0009 のメカニズムが公開 fork に接続、L1→L2–L3

network-isekai PR #142(full-loop gate 14/14)。`fork_refs.status`
(isekai.moderation の statechart。既定 'approved' = Phase-0 楽観公開 —
review 人員のない現状で pending キューを作らない選択を明文化)+
`fork_reports`(PK (head, hashed reporter) — 重複通報は dedupe、auto-flag
閾値は **distinct reporter ≥3** で単独通報者は何も flag できない。生 IP は
DB に入らない)+ `POST /api/fork/report`(pure な `shouldFlag` が
statechart 合法辺のみ遷移: removed は蘇生しない/flagged は churn しない)。
flagged/removed は feed から消えるが block は CID で配信継続(ref が可視性
の面、immutable data は消さない)。owner review(flagged→approved|removed)
は wrangler d1 の status flip で、**e2e が実際にその ops 経路を実行して検証**
(dedupe 実証 + review flip → feed 消失・系譜親は生存・block は 200)。
初版 e2e の偽 IP spoof は miniflare(本番同様)の cf-connecting-ip 上書きに
正しく防がれた — hashing が守る対象そのものだった。policy の中身
(語彙リスト・review 体制・不服申立)は owner+counsel 事項のまま。
moderation 軸: L1 → **L2–L3**。

## Addendum 9 (2026-07-12, same day): マルチプレイ L1→L2 — StageRoom Durable Object が sketch から実装へ

network-isekai PR #143(bb stage-room-e2e 2/2 — 実 workerd + 実 DO)。
isekai.stage の `?net=ws` client は機能着地時からプロトコル(700ms JSON
presence heartbeat)ごと実在していたが、受け手が無く 501 に送り続けていた —
root wrangler.toml が「standalone worker で作るべき」と sketch していた
`StageRoom` DO を `workers/stage-rooms/` に実装(stage id ごとに 1 room、
hibernatable WebSockets、presence relay。durable/valuable な物は一切ここを
通らない trust model を明記)。e2e は本物の workerd 相手に roommate 到達・
self-echo 無し・room 分離・逆方向・不正 stage 拒否を検証。有効化は owner
2 手順(worker deploy + Pages binding のコメント解除 — タスク #9 に追記)。
テスト自体からの教訓 3 件も記録: node:test の hooks は named import
(`test.before` は無言で undefined)/ port プローブは自 worker の固有署名
(426)を要求(無関係のローカル HTTP サーバの 404 に初回騙された)/
dev サーバは process group ごと kill(npx への SIGKILL は workerd orphan を
残す — fork-publish driver と同じ教訓)。マルチプレイ軸: L1 → **L2**
(server 実装・relay 実証済み。デプロイ + game 側 netcode が残差)。

## Addendum 10 (2026-07-13): :app 復活 + 系譜 breadcrumb — 数日ぶりに全面 green

network-isekai PR #144(full-loop gate 15/15)。並行 session の
kami.*→kotoba.* namespace 移行に game.cljc の require を 1 行追従
(`kami.netsync`→`kotoba.netsync` — webgpu #18 が自身に適用したのと同型。
kotoba.netsync は 07-02 から同一 API を sibling に持っていた)。これで
数 iteration blocked だった repo 全体の `:app` build が復活(154 files、
warnings 25→15)。あわせて懸案の **provenance breadcrumb** を実装:
hydrate した `?fork=` ページが「⑂ remix by <did…> · parent」を表示し、
parent リンクは実 commit DAG を 1 hop ずつ辿る(各 hop も CID 検証付き
hydrate)。`hydrate-with-lineage` が署名済み commit の {author, ts,
parents} を返し、gate は「B の breadcrumb が A を author として名指す」
まで検証。公開/UGC ループの UX が「共有できる」から「**誰の remix かが
見える**」へ — Roblox 的な creator attribution の第一歩。

## Addendum 11 (2026-07-13): マルチプレイ full-loop 実証 — L2→L3(コード検証済み)

network-isekai PR #145(`bb stage-presence-e2e` 2/2)。**2 つの分離
Playwright context** が `wrangler pages dev`(STAGE_ROOMS `--do` binding =
`StageRoom@stage-rooms`)経由で `dance?net=ws` を開き、その binding が
別 `wrangler dev` 上の**実 StageRoom DO** に到達 — 各 context が相手の
presence を見た(peers が相互参照、identity は別)。context 分離が証明:
BroadcastChannel(既定 transport)は Playwright context を跨げないので、
presence が届いた事実が「WebSocket → Pages Function → Durable Object relay」
経路を通った証拠 = 携帯とラップトップが本番で辿るのと同じ経路。PR #143 が
DO relay を単体(node WS client)で実証したのに続き、本 PR は**実際の
isekai.stage client(`?net=ws`→connect-presence!)を DO 越しに end-to-end
駆動**。マルチプレイ軸: L2 → **L3(cross-device presence がコード検証済み。
本番は owner の worker deploy + binding、game 側 authoritative netcode が
残差)**。デバッグ知見も記録(dev binding 構文 `--do NAME=Class@script`、
pages dev の clean-URL は `/dance`、binding-live プローブは Function 越しの
426 を要求、peer 判定は id 補間でなく peers atom の keys ダンプ — 前者は
relay が動いていても false を返した=バグは assertion 側)。

- ADR-2607072200（kotoba-git + kotoba-rad — 本 ADR の基盤層。addenda:
  datom-native object model / ref-policy / signed head-announce E2E /
  CACAO delegation）
- ADR-2606280300（Rust 期の kotoba-rad 設計 — 2607072200 が supersede）
- ADR-2607032400（network-isekai が正本、isekai-network 統合）
- ADR-2607091830（game Work Actor + aozora embed — M2 の接続先）
- ADR-2607031400（keyed CACAO actor 温存案 — M3 で再訪）
- network-isekai: ADR-0001（web arch / fork=data）、ADR-0008（Asset Hub）、
  ADR-0009（moderation Phase 0）、issue #49、PR #72 / #110 / #113
- `orgs/gftdcojp/network-isekai/src/isekai/{fork,web,feed_index,economy,fork_stats,moderation}.cljc`
- `orgs/kotoba-lang/kotoba-git`、`orgs/kotoba-lang/kotoba-rad`（west 登録済み）

## Addendum 12 (2026-07-13): dance stage も durable fork に — 公開ループが両アーティファクトを網羅

network-isekai PR #146(fork-git 29/29)。isekai.stage は M1 の durable 化
から唯一漏れていた(isekai.web だけが対応、stage は legacy local-only の
まま)。今回 dance stage の ⑂ Fork も **game と同一の fork-git-client 経路**
で公開: did:key 署名 push → immutable `?fork=<commit-CID>` → hydrate 時に
provenance breadcrumb。stage の `:fork/logic` は ""(pure data animation、
logic.cljc 無し)だが content-addressed transport は特別扱い無しで処理する。
これで本 ADR の fork ループがサイトの公開する**両方の first-class artifact
(game と VRM dance stage)を網羅**した。gate に stage 特有の round-trip
検証 2 件追加(空 logic でも full 5-block set — 空文字列も実 blob)。
残る Fortnite/Roblox 差分は描画品質と game 側 authoritative netcode のみで、
いずれも別 ADR 級プロジェクト。**UGC ループの配線は本 ADR で完了**、本番化は
owner の deploy 手順(タスク #9 相当)を待つ状態。

## Addendum 13 (2026-07-13): Studio(/studio)も durable fork — 全 3 editor surface が単一ループに

network-isekai PR #147(:ui 199 files build 済み、full-loop e2e 15/15)。
isekai.ui(re-frame の multi-mode Studio)は 3 つの editor surface で唯一
legacy local-only fork のままだった。今回その ⑂ fork も game/stage と同一の
fork-git-client 経路(did:key 署名 push → `?fork=<commit-CID>` → hydrate)に。
re-frame の effect 境界のおかげで変更は 2 つの reg-fx(`:game/load`/
`:game/fork*`)に限局し、`:game/fork` が親 CID を運ぶので Studio fork も実
ancestry を成す。**これでサイト上で訪問者が edit-and-fork できる全箇所
(game player・dance stage・Studio)が、単一の content-addressed・署名付き・
cross-browser 検証済み publish ループを共有**する。fork の永続化配線として
やり残しは無く、以降の ADR スコープ内改善は細部のみ。
