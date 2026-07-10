# ADR-2607023200: Ghost Hacker ゲームポートフォリオ — FreeTEMPOのグルーヴ感を軸にした10ジャンル展開、および GHOST HACKER: FLOW の起票（proposed・FLOWのみ scaffold 実装）

**Status**: proposed（ポートフォリオ設計は proposed。GHOST HACKER: FLOW のみ pure .cljc core を scaffold 実装済み）
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

### Ghost Hacker（既存カノン）

`orgs/com-junkawasaki/ghosthacker` は「情報は物理だ」を哲学の核に据えた、実在の
サイバー犯罪対策（フィッシング・アカウント乗っ取り・闇バイト等、IPA/JPCERT監修）
を少年漫画の文法で描く作品。中学生時代からの幼馴染 **Ren（沼野蓮）** と
**Nei** が主人公で、Renは「ネットワーク監視をしない」（自分がアクセス権を持つ
範囲＝父の遺したサーバ・公開OSINTのみで動く）という明確なコンプライアンス制約の
下で事件を解決する。Neiは異変を最初に察知する観察役。事件が深刻化すると
**情報場**（クラスの3割にだけ薄く見える情報空間）が発現し、そこで
**Ghost Battle / Daemon Battle** が起きる。README の「2065年・水の都・東京」
という旧ビジョンと、現行エピソード（`260123-jump/resources`）の「現代日本の
中学〜高校」という舞台設定は、**情報場の中だけがネオ東京的ビジュアルを持つ**、
という解釈で統合できる（本ADR時点で会話ベースの解釈、正式なカノン変更ではない）。

### FreeTEMPO（訂正済みの実像）

当初「90年代クラブミュージック」という前提で検討したが、公式サイト
（freetempo.net/biography, freetempo.net/music）と Wikipedia を確認した結果、
実像は以下の通り:

- 本名 半沢武志。**2000年**にFreeTEMPOとして始動（イタリアIRMA RECORDSへの参加が
  デビューのきっかけ）。活動の中心は**2000年代**（2010年に一区切り、2021年に
  再始動）。
- ジャンルは「**ボサノバ・AOR・ジャズ・ハウス**の要素を持つクラブミュージック」
  （ラウンジ/ハウス）。攻撃的なブレイクビート/ジャングル/ビッグビートではなく、
  **温かく浮遊感のある四つ打ち＋メロディセンス**が核。
- `Sky High` は単独アルバムではなく、2003年『The World Is Echoed』収録曲で、
  渋谷HMV発で火が付き全国区の知名度を得た代表曲。`TENSE` は2010年発表の
  初ベストアルバムのタイトル。
- 代表曲: Tuning / Sky High / Vamos a Bailar / Duet / Twilight（2003）、
  MELODY / SYMMETRY / HARMONY / ASYMMETRY（2007 mini）、
  Tomorrow / Breezin' / Family / メモライズ / Time Machine（2010）、
  Lightning / Prelude（2005）等。

この訂正を受け、当初案（アシッドジャズDJスクラッチ／トリップホップ潜入／
ドラムンベース弾幕／ビッグビート乱闘、等）を破棄し、**ボサノバ/AOR/ジャズ/ハウス
の温かい四つ打ちグルーヴ**を軸にジャンルを再設計した。

## Decision

### ポートフォリオ: 10ジャンル×10タイトル

FreeTEMPOの実在曲/アルバム名から命名し、Ren（行動・Ghost Battle実行役）と
Nei（観察・察知役）の既存カノンの役割分担にジャンルを対応させる。

| # | ジャンル | タイトル | 出典曲（収録アルバム・年） | 主人公 | コンセプト |
|---|---|---|---|---|---|
| 1 | アクション | **GHOST HACKER: FLOW** | （造語。近縁曲: "Breezin'" 『Life』2010） | Ren単独 | 情報場を滑走してログの粒子を拾う疾走アクション。四つ打ちのグルーヴに乗り続けることが失敗条件を左右する |
| 2 | 音ゲー（旗艦） | **GHOST HACKER: HARMONY** | "HARMONY"/"ASYMMETRY"（『HARMONY』2007） | Ren単独 | Ghost Battle本編。四つ打ちに同期し続ける精度を競う。ズレる=ASYMMETRY、噛み合う=HARMONYが成否表現そのもの |
| 3 | アドベンチャー | **GHOST HACKER: ECHOES** | 『The World Is Echoed』（2003）由来 | Nei単独 | 関係修復ノベル。README原点の "Healing connections in a disconnected world" を主題化 |
| 4 | RPG | **GHOST HACKER: TIME MACHINE** | "Time Machine"（『Life』2010） | Ren & Nei共同 | 父の遺したサーバの過去ログを遡り、Ghostを仲間にして育てる時間軸探索型RPG |
| 5 | シミュレーション | **GHOST HACKER: FAMILY** | "Family"（『Life』2010） | Ren & Nei共同 | 事務所併設リスニングバー経営。見つけた家族=Familyがテーマと直結 |
| 6 | パズル | **GHOST HACKER: TUNING** | "Tuning"（『The World Is Echoed』2003） | Nei単独 | 周波数/ログを合わせて整合させる、Neiの観察眼が活きる整合パズル |
| 7 | カードゲーム | **GHOST HACKER: メモライズ** | "メモライズ"（『Life』2010） | Nei主導 | 事件の記憶=Ghostをカード化して集める記憶デッキビルダー |
| 8 | スポーツ | **GHOST HACKER: DUET** | "Duet"（『The World Is Echoed』2003） | Ren単独 | 他校/他事務所との1対1対抗戦。競技化されたGhost Battle |
| 9 | パーティゲーム | **GHOST HACKER: VAMOS A BAILAR** | "Vamos a Bailar"（『The World Is Echoed』2003） | Ren & Nei + 事務所メンバー総出演 | 大人数ミニゲーム集 |
| 10 | シューティング | **GHOST HACKER: LIGHTNING** | "Lightning"（『Oriental Quaint.』2005） | Ren単独 | Daemon大量発生に対抗する弾幕シューティング |

共通ルール: いずれのタイトルも Ren の「ネットワーク監視をしない」コンプライアンス
制約と、既存カノンの `:gh/educationalContent`（実在のセキュリティ教育）を
ゲームルール/UIとして継承する。新規オリジナルキャラクターは追加しない。

### 着手順序: GHOST HACKER: FLOW から

FLOWを最初の実装対象に選ぶ。理由:
- 10本のうち最もシンプルな判定モデル（単一の「グルーヴに乗るか外れるか」の連続量）
  で、他の9本（特に旗艦 HARMONY）が共有する核ロジック（ビート同期判定・
  TENSE⇄Sky Highのcrossfadeパラメータ）を最小コストで検証できる。
- ジャンルとして独立性が高く（RPG/シム/カードのような長期セーブ状態や大きい
  データモデルを持たない）、1リポジトリのscaffoldとして完結しやすい。

## GHOST HACKER: FLOW — 詳細設計

- **ジャンル**: アクション（疾走フロー）。**主人公**: Ren単独。
- **コアループ**: 情報場をボードで滑走し、四つ打ちのビートグリッドに合わせて
  入力する。入力タイミングのズレ(ms)を判定し、`:perfect` / `:good` / `:miss`
  に分類。判定は combo（連続成功数）と `:groove`（0.0=TENSE〜1.0=Sky High の
  楽曲crossfadeパラメータ）を更新する。`:groove` は成功が続くほど上がり、
  `:miss` で大きく下がる — 「情報は物理だ」を、演出ではなく操作の結果として
  楽曲の質感が変わる形で実装する。
- **スコープ（今回のscaffold）**: `ghosthacker-flow.core`（`.cljc`、pure）に
  ビート位相計算・判定・状態遷移のみを実装し、test を添える。レンダリング/
  入力/音声ホストアダプタ（Svelte/Canvas等、tech stack未確定）は対象外
  — `ghosthacker`本体の `ghosthacker.resources`(pure) /
  `ghosthacker.import`(host adapter) と同型のレイヤ分離方針を踏襲し、
  純ロジックを先に固める。
- **リポジトリ**: `com-junkawasaki/ghosthacker-flow`（private。
  `:orgs :com-junkawasaki :scope :private-foundation` に従う）。west
  `:extra-projects` 経由で登録。

## Open Questions

- 残り9本（HARMONY以下）の着手順序と、それぞれのtech stack最終決定
  （レンダリング層は kami-engine-sdk 流用が有力候補だが未決定）。
- ~~FLOW/HARMONYが共有する `groove`/判定ロジックを、別途 shared lib に切り出す
  か各リポジトリに複製するかは、2本目（HARMONY）着手時に判断する。~~
  → 下記Addendumで暫定決着（実装はHARMONY着手時）。
- ghosthacker本体（manga pipeline）とゲームポートフォリオの資産共有範囲
  （キャラクター画像・世界観設定EDNの参照方法）は未設計。

## Addendum（2026-07-03、FLOW 14イテレーション後の知見）

FLOWの `ghosthacker-flow.core` は、当初想定した「ビート位相計算・判定・
状態遷移のみ」の薄いscaffoldから、以下まで育った（103 assertions）:

- 判定窓パラメータ化（`judge-with-windows`）と難易度プリセット
  （`:easy`/`:normal`/`:hard`、`judge-input-difficulty` /
  `judge-detailed-difficulty`）
- score/combo倍率/`:groove`crossfadeの状態遷移一式（`apply-judgment`）
- 対マッシュガード（`judge-input-once`/`judge-sequence-once`、
  `beat-index`による同一拍の二重入力検出）
- 空振り検出（`judge-run`/`play-run`、期待される拍数と実入力の突き合わせ）
- `beat-schedule`（譜面/理想入力列の生成）、`accuracy`/`grade`/`summary`

これらは**FLOWの疾走アクションという表現に固有の要素をほぼ含まない**——
「四つ打ちのビートに同期し続ける」判定エンジンそのもの。一方、
HARMONY（旗艦・音ゲー本編）の設計は「Ghost Battle本編。四つ打ちに同期し
続ける精度を競い、乗れているほどTENSE→Sky Highへ曲が転調していく」と、
FLOWの核と**同一の判定・crossfadeモデル**を前提にしている。

**暫定決定**: HARMONY着手時、`ghosthacker-flow.core`（判定/score/crossfade/
対マッシュ/空振り検出）は複製せず、shared libへ切り出す方向で進める
（FLOW固有なのは `demo.clj` とREADMEのFLOW向け説明のみ）。切り出し方の
選択肢（a. `ghosthacker-flow`からcoreを剥がして新規`ghosthacker-groove-core`
のようなrepoに移し両者が依存する、b. HARMONY作成時に一旦複製して2本の
実装が揃ってから共通化する、のどちらを取るかはHARMONY着手PRで判断——
本Addendumの役割は「複製ではなく共通化する」という方向性だけを先に
固定しておくこと。実装（repo分割・manifest更新）は本ADRの対象外で、
HARMONY起票時の別作業とする。

## Addendum 2（2026-07-10、ADR-2607100900 follow-up (b) 実施 — host adapter決定 + 3本目着手）

ADR-2607100900（ghosthackerアニメ/ゲーム方針決定）follow-up (b)（game
portfolio host adapter選定）の結果、host adapterは **ClojureScript**
に決定した（`kami-engine-sdk`は実体がwasm-bindgen Rustエンジンを包む
Svelte/TS SDKで、cljsからも同じwasmモジュールを直接呼べるため新規host
ABI設計が不要と判明。FLOW/HARMONYが必要とする音声/タイミングは
host-importで、ADR-2607100030 addendum 2の制約によりkotoba wasm/
clojurewasmではなくClojureScriptが第一候補になる）。

この決定を受け、**FLOW・HARMONY・ECHOESの3タイトルに実プレイ可能な
ブラウザhostアダプタを実装**（reagent + shadow-cljs。FLOW/HARMONYは
Web Audioでビートクロック+合成メトロノーム音を追加、ECHOES/TUNINGは
リアルタイム判定が無いためWeb Audio不要）。いずれもheadless DOM上での
実クリック/キー入力による通しプレイで検証済み:
- `com-junkawasaki/ghosthacker-flow@d1e48c7`
- `com-junkawasaki/ghosthacker-harmony@22c3a0d`
- `com-junkawasaki/ghosthacker-echoes@b67f151`

続けて**GHOST HACKER: TUNING（#6、パズル、Nei単独）を2本目のフル
scaffold対象として着手**（`com-junkawasaki/ghosthacker-tuning`、private、
west登録済み）。ECHOESと同じ「pure core → terminal prototype → browser
host」の一気通貫パターンで、`core.cljc`（dial-vs-target整合判定、
`ghosthacker-groove-core`とは独立実装 — ビートグリッド前提ではないため）
+ `terminal.clj`（nudge&lock REPL、目標値は`static`近接度のみで隠す）+
`web.cljs`（reagent、ECHOES同様Web Audio不要）。11 tests/51 assertions、
clj-kondo 0 errors/warnings、headless DOM実クリック検証済み。

残り6本（TIME MACHINE/FAMILY/メモライズ/DUET/VAMOS A BAILAR/LIGHTNING）は
依然として設計のみ・未着手。

## Addendum 3（2026-07-10、オーナー指示で残り6本を並行scaffold — ポートフォリオ全10本が実装完了）

オーナー指示「残り6タイトルも進めて」を受け、6並列エージェントで残る
全タイトルを同時にscaffold（各タイトル: pure `.cljc` core →
terminal.clj → reagent web.cljs、headless DOM実クリック/キー検証済み、
GitHub private repoとしてpush、west登録済み）。全リポジトリ・commit
存在をAPI経由で個別に実在確認済み。

- **GHOST HACKER: TIME MACHINE**（#4、RPG、Ren&Nei共同、
  `com-junkawasaki/ghosthacker-timemachine@82cc725`、18 tests/78
  assertions）— 父の遺したサーバの過去ログを"log-depth"として順に遡る。
  パーティは単一集約値`:power`のみを持ち、各depthの`:difficulty`との差
  (margin)から`descend`が一つの明確な状態遷移を行う（TUNINGの`lock-in`
  と同型）。margin次第で`:recruited`（Ghost仲間化+power増）/
  `:cleared`（仲間化せず小幅経験値）/`:retreat`（power消耗、それでも
  次のdepthへ進むかはhost側の関心事）に分岐。
- **GHOST HACKER: FAMILY**（#5、シミュレーション、Ren&Nei共同、
  `com-junkawasaki/ghosthacker-family@0f470e8`、12 tests/57
  assertions）— 事務所併設リスニングバーを5営業日にわたって経営する。
  毎日、限られたattention point(10)を`:drinks`/`:music`/`:staff-care`
  へ配分し、見せない`:need`との差から`:thriving`/`:steady`/
  `:struggling`を判定、`:funds`と`:family-bond`（"Family"というテーマ
  そのものの数値化）に反映。黒字でなければ`:family-bond`が高くても
  最高gradeにはならない優先順位。
- **GHOST HACKER: メモライズ**（#7、カードゲーム、Nei主導、
  `com-junkawasaki/ghosthacker-memorize@c187ede`、16 tests/57
  assertions）— 事件の記憶=Ghostをカード化した8枚(4ペア)の固定グリッド
  で神経衰弱。`flip-pair`ひとつが核の状態遷移。同一index・collected済み
  index・完了後の呼び出しは無効(TUNINGの`lock-in`ガードと同型)。
  攻略効率(attempts対total-pairs)からgradeが決まる。
- **GHOST HACKER: DUET**（#8、スポーツ、Ren単独、
  `com-junkawasaki/ghosthacker-duet@e27ee4c`、12 tests/31
  assertions）— **`ghosthacker-groove-core`を複製せず再利用**（ADR-
  2607032600の踏襲）。プレイヤーはFLOW/HARMONYと同じ`chart-play-run`/
  `judge-chart-input`で判定され、rival（決定的なoffset pattern、新規
  キャラクターは追加せず「堅実型」「エース」の難易度プロファイルとして
  扱う）も同じ譜面・同じAPIで走行。両者の最終`:score`を比較し
  WIN/LOSE/DRAWを決める。
- **GHOST HACKER: VAMOS A BAILAR**（#9、パーティゲーム、Ren&Nei+
  事務所メンバー総出演、`com-junkawasaki/ghosthacker-vamos@852ded8`、
  22 tests/63 assertions）— Ren・Nei・Kota・Mei（既存カノンキャラクター
  のみ、新規オリジナルキャラクター追加なし）の4人rosterが、
  reaction-tap（数当て近似判定）・quick-pick（本物のURL選別、実在の
  フィッシング教育が題材）・sequence-recall（アカウント乗っ取り対応
  手順、これも実在の教育内容）の3ミニゲームを順にこなし、共有tallyで
  リーダーボード/勝者を決める。ブラウザhostはpass-the-device方式。
- **GHOST HACKER: LIGHTNING**（#10、シューティング、Ren単独、
  `com-junkawasaki/ghosthacker-lightning@5ce0ab4`、27 tests/111
  assertions）— 連続弾幕エンジンではなく5wave固定シーケンスの軽量抽象化。
  周期的なtarget flashに合わせて1発ずつ撃ち、flashからの経過時間が
  wave毎に狭まる窓以内ならhit。弾切れ時に残るDaemon数ぶん`:health`が
  減る。タイミング判定は`ghosthacker-groove-core`とは独立の自前
  ヘルパー（`flash-schedule`/`judge-shot-timing`）— TUNING同様、
  ビートグリッド前提ではない別ジャンルとして意図的に非依存。

**これでGhost Hackerゲームポートフォリオ10本全てが「pure core →
terminal prototype → 実プレイ可能なブラウザhost」まで到達し、
headless DOM実操作で検証済みの状態になった。** レンダリングは全タイトル
ともDOM/CSSのみ（`kami-engine-sdk`のようなキャンバス/wasm描画は依然
未着手、follow-up）。各新規repoの GitHub Actions CI は本セッション時点で
**GitHubアカウントの支払い問題により起動できていない**（"recent account
payments have failed"、コード側の問題ではない — オーナー対応待ち）。
ローカルでの`clojure -M:test`/`clj-kondo`は全リポジトリで green。

## Consequences

**Positive**
- FreeTEMPOの実像（2000年代ボサノバ/AOR/ジャズ/ハウス）に基づいた、誤情報を
  含まない音楽方向性がポートフォリオ全体に定まった。
- 10本のジャンル/主人公/出典曲がすべて確定し、以降の着手判断が速い。
- FLOWのcore判定ロジックはpure `.cljc` かつテスト付きで、JVM/CLJS/WASM
  いずれのホストにも移植可能な状態で着地している。
- （Addendum 2/3）**ポートフォリオ10本全てが実プレイ可能なブラウザhost
  アダプタまで到達**し、判定モデルの多様性（ビート同期crossfade、勝敗
  フレーミング、ダイアログ分岐、精度パズル、RPG探索、経営シム、神経衰弱、
  1v1対抗戦、パーティミニゲーム集、wave制シューティング）を実装レベルで
  検証できた。

**Negative / 制約（honest）**
- レンダリングは全タイトルともDOM/CSSのみ（`kami-engine-sdk`のような
  キャンバス/wasm描画は未着手）。FLOW/HARMONY/DUETの`:groove`crossfadeは
  作曲済み楽曲が無いため音声ではなく視覚的な表現に留まる。
- 各新規6リポジトリのGitHub Actions CIはアカウントの支払い問題で未起動
  （コード自体はローカル検証green、follow-up: オーナーのbilling対応後に
  再確認）。
- 「情報場が2065年ネオ東京ビジュアルを持つ」という統合解釈は、ghosthacker本体
  のカノンとして正式に確定していない（本ADRのゲームポートフォリオ文脈限定の
  暫定解釈）。
