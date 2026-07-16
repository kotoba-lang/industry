# ADR-2607993000: ENGI を L1（Byzantine 耐性コンセンサス）に、EN をその上のネイティブ通貨単位に — L1/独自コンセンサス禁止方針の一部撤回

**Status**: accepted（設計 + 最小プロトタイプ実装。本番マルチノード運用は伴わない）
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

`kotoba-lang/engi`(ADR-2607101100、v1 実装・本番 kotobase.net へのライブ検証済み)は
HoloFuel 型の双方確定ハンドシェイクによる相互信用通貨だが、グローバルな順序も
合意形成も持たない — 残高はエージェントごとのグラフを個別に pull して fold する
projection であり、fork 検知も監査ベース(push gossip なし)。ADR-2607110300 は
これを含む3リポジトリ横断の成熟度調査の結果、「自己主権IDは実装済みだが合意形成・
経済的強制力は依然として設計/ADR段階」と総括し、以下を明示的に禁止していた:

> - 新しいL1・独自コンセンサスチェーンを作らない(ADR-2607030030を継承)。
> - witness全員が単一運営者配下である間のfork-choice/view-change設計はしない
>   (Byzantine前提を持ち込まない — crash fault tolerance相当と呼ぶ)。

この禁止の根拠自体は誠実だった(独立した第三者運営者が実在しない段階で Byzantine
耐性を謳うのは過大主張になりうる)。しかし、オーナーの 2026-07-15 の判断は
「単一運営主体のままでもよいが、コンセンサス**アルゴリズム自体**は今から Byzantine
耐性ありで設計し、実際に第三者 validator が参加する時にプロトコルを書き直さずに
済むようにする」というもの。本ADRはこの判断に基づき、上記2点の禁止を**ENGI/EN
というスコープに限定して**撤回し、`kotoba-lang/engi` を L1(ブロック生成+BFT
コンセンサス)に、新規リポジトリ `kotoba-lang/en` をその上で動く最初のネイティブ
通貨単位(EN)実装に位置づける。

**撤回の範囲を明示する**: 本ADRは「新しいL1をいくつでも作ってよい」への転換では
ない。ENGI/EN という一つの具体的ドメインに対して一つの L1 substrate を作ることを
決定するものであり、将来 BFT 順序付けを必要とする別ドメイン(例: kotoba-word の
分散インデックス、murakumo の economy台帳)が現れた場合は、**別の新しい L1 を
増やすのではなく本ADRが作る engi/L1 の substrate を再利用する**ことを原則とする
(ADR-2607030030 の「新L1を作らない」の精神 — チェーンを増殖させない — は
ここに引き継がれる)。

## 撤回しないもの(ADR-2607110300 / ADR-2607030030 のうち温存する部分)

- **運営主体は引き続き単一(`com-junkawasaki`)。** 本ADR実装後も外部に対して
  「分散型経済/ブロックチェーン」と名乗らない — ADR-2607110300 の Phase 4
  (独立第三者運営者 + 経済的 stake)に到達するまでは、という命名規律は不変。
  今回変わるのは「**アルゴリズムが** BFT 前提で書かれているか」だけであり、
  「**運用が** 実際に複数独立運営者か」ではない。
- **witness-quorum の本番化を Rust 側(kotoba-lattice)で実装しない。** cljc/clj
  (murakumo control plane)で閉じる方針(ADR-2607071900)は継続。
- **murakumo 推論経済の chain gateway は mint/burn のみ、新しい汎用 L1 にしない
  (ADR-2607030030)。** この決定は不変 — 本ADRが作る L1 は ENGI/EN 専用であり、
  murakumo の経済台帳を巻き込まない。
- ENGI Wave 4(CACAO depth-2 delegation)の一般解は本ADRの範囲外(ただし下記
  Decision #3 で、witness に限定した近道を用意する)。

## Decision

### 1. コンセンサスアルゴリズム — chained HotStuff 型 BFT(PBFT ではなく)

**選択**: leader-rotation + Quorum Certificate(QC) + 3-chain commit rule の
HotStuff 系アルゴリズム。n = 3f+1 の witness(validator)のうち f が Byzantine
でも安全性を保つ。

**選定理由(Alternatives 参照)**:
- **通信量が O(n)(leader中心の linear)** — PBFT の all-to-all O(n²) と比べ、
  murakumo/overlay の QUIC(既存・実働・point-to-point)にそのまま乗る。
- **安全性の証明が単純** — 2つの QC(いずれもサイズ 2f+1、n=3f+1 中)は必ず
  最低 f+1 ノード(=最低1つの正直ノード)で重なる、という quorum intersection
  の一点で安全性が言える。3-chain commit rule(ある block が連続する2つの
  子孫 QC を得て初めて確定)は実装・検証コストが小さい。
- **既存資産をそのまま流用できる** — `kotoba-lang/witness-quorum` の
  閾値署名/attestation プリミティブ(`signer.cljs`/`selector.cljs`/
  `attestation.cljs`、JVM+nbb 両対応・クロスプラットフォームでバイト一致
  検証済み、ADR-2607110300 Addendum 3)を QC の署名収集にそのまま使う。
  `murakumo/overlay` の QUIC トランスポート(実働・177 tests/811 assertions)
  に propose/vote/new-view のメッセージ種別を追加するだけで済み、
  ADR-2607110300 Phase 2 が既に決めていたトランスポート再利用方針は変えない
  — **変わるのはトランスポートの上で走るアルゴリズムだけ**(post-hoc
  cosign → 本物の BFT state machine)。

**validator 集合**: 「witness」という既存語彙をそのまま使う。n=3f+1 の各
witness は独立した鍵ペアを持つ独立した principal として扱う — 今日は全 witness
process が単一運営者(`com-junkawasaki`)配下で動いていても、プロトコルコード上に
「どうせ全員身内だから」という近道(共有シークレットでの一括署名、投票の省略等)
を一切入れない。将来 f 個の witness が実際の第三者運営者に置き換わっても
**プロトコルコードは無変更、validator 集合のメンバーシップが変わるだけ**にする
のが本アルゴリズム選択の目的そのもの。

**耐える攻撃**: 二重投票(同じ高さで矛盾する block への投票 = equivocation)、
沈黙(クラッシュ/応答保留)、無効な投票のスパム — n=3f+1 中 f まで。
**v1 で意図的に対応しないもの**: 適応的でタイミングを操作する敵対者に対する
leader 選出のランダム化(v1 は round-robin — 予測可能な leader は実際の
Byzantine 運営者が現れる段階で VRF 化する将来の強化課題として明記する)。

### 2. スキーマ — engi の finalize 対象を「per-agent グラフ」から「グローバル順序ブロック」へ

ADR-2607101100 のスキーマ(genesis/debit/credit/warrant、各エージェントが
`kotobase/db/<did:key>/engi` に自分のグラフのみ書く)は**そのまま変更しない**
— これは物理的な書込み先(kotobase.net の CACAO 制約)の話であり、L1 が
変えるのは「どの transfer proposal が確定として数えられるか」の判定基準。

```clojure
;; L1 block(witness が propose する単位。1つ以上の transfer proposal をまとめる)
{:engi.block/height <int, 単調増加>
 :engi.block/parent-hash "<親blockのcanonical hash、または \"genesis\">"
 :engi.block/proposals [<TransferBody CIDのvec, ADR-2607101100の既存proposal形式>]
 :engi.block/proposer "<did:key of witness who proposed this height>"
 :engi.block/ts <ms>}

;; Quorum Certificate(2f+1 witness の署名投票をまとめたもの)
{:engi.qc/block-hash "<engi.blockのcanonical hash>"
 :engi.qc/height <int>
 :engi.qc/votes [{:witness "<did:key>" :sig "<Ed25519>"} ...]  ; >= 2f+1 件
 :engi.qc/view <int>}

;; 3-chain commit: block B が確定するのは、B <- B' <- B'' という連続する3つの
;; block それぞれに QC が付き(B''のQCがB'を、B'のQCがBを直接の親として参照)、
;; かつ B'' 自体がまだ確定していなくても、この時点で B は安全に確定する
;; (HotStuff の chained 3-phase commit rule、そのまま踏襲)。
```

**残高の権威**: `engi.core/fold-balance`(既存、無変更)が per-agent の
tx entry 列を replay する関数であることは変わらないが、**「どの transfer が
確定として数えられるか」の判定は、finalize された block 列に含まれる
proposal のみ**とする。ADR-2607101100 の「片側だけの credit は使用可能残高に
算入しない」という bilateral confirmation ルールは、L1 finality の下では
**block finality が bilateral confirmation を包含する形で置き換わる**(block
に入った transfer は双方の debit/credit エントリが同時に有効化されたとみなす
— witness が validate 時点で両エントリの整合性を検証済みだから)。

### 3. ENGI 最大の穴(自己検証への後退)を witness 権限で近道解消する

ADR-2607101100 の既知の制約: kotobase.net apex は他者グラフへの未認証読取りに
401 を返すため、`validate-proposal!` の「受信者が送信者グラフを独立に読んで
検証する」設計が機能せず自己検証に後退していた。解消は ADR-2607022600 Wave 4
(CACAO depth-2 delegation、一般的な任意テナント間委任)が前提とされていたが、
これは今も未着手。

**本ADRが可能にする近道**: witness は「取引の相手方」ではなく「運営者が
own するインフラ上で動く、運営者が信任した監査 role」である。今日 witness
process は全て `com-junkawasaki` 配下で動くので、**運営者は自分自身の
kotobase.net インスタンス上で、witness 専用の CACAO grant(`kotoba://graph/*`
の read-only、任意 engi グラフを対象)を発行できる** — これは Wave 4 が
解決しようとしている「任意テナント A が任意テナント B に委任する」一般解では
なく、「運営者が自分の運営するインフラの読み取り権限を、自分が信任した
service account に与える」だけの、単一運営者の枠内で閉じる設定変更にすぎない。
これにより **witness による cross-agent validate は Wave 4 を待たずに本番で
機能する**ようになる — ENGI v1 最大のギャップに対する、本ADRの副産物としての
具体的な前進。ただし本当の意味での「任意の第三者が任意の他者データを検証できる」
汎用委任は依然として Wave 4 待ちであり、本ADRはそれを解決したと主張しない。

### 4. EN — engi/L1 の上で動くネイティブ通貨単位(新規リポジトリ `kotoba-lang/en`)

`kotoba-lang/en` を新規 west 登録する。役割は「engi/L1 が確定させた block 列を
読み、EN 通貨としての意味論(残高照会・送金の申請・net-zero/非発行の不変条件)を
提供する消費者レイヤ」— Ethereum で言えば L1(engi)の上に乗る token contract に
相当するが、VM/opcode は無く、確定済み block を `engi.core/fold-balance` と
同じ不変条件チェッカーで決定論的に replay するだけ、という意味で遥かに単純。

- `en.core`(新規、`engi.core` と同型の pure `.cljc`): `finalized-balance`
  ― witness が確定させた block 列のうち、指定エージェントが関与する
  transfer だけを抽出して `engi.core/fold-balance` に通す。
- `en` は `engi` に依存する(`engi.core/fold-balance`・スキーマ述語をそのまま
  再利用 — 独自の残高計算ロジックを重複させない)。
- **不変条件は変わらない**: net-zero・非発行・信用限度。L1 finality は
  「誰にどれだけの権威ある残高があるか」を決めるだけで、EN 自体の発行ルール
  (labor-backed のみ、pre-mine 無し)は ADR-2607101100 から継承・不変。

## Consequences

- (+) 「単一運営主体だから Byzantine を考えない」という近道をコンセンサス
  アルゴリズムの設計そのものからは排除できる — 将来 witness の一部が実際の
  第三者運営者になっても、プロトコル書き換えではなく validator 集合の
  メンバーシップ変更で済む。
- (+) ENGI v1 最大の既知の穴(cross-agent 検証が401で機能しない)を、Wave 4
  を待たずに witness 権限の近道で実務的に解消できる。
- (+) `murakumo/overlay` の QUIC トランスポート・`witness-quorum` の署名
  プリミティブという既存の実働資産をそのまま再利用でき、新規インフラの
  増殖を避けられる(ADR-2607110300 Phase 2 の判断を継承)。
- (−) 本ADRが実装するのは**単一プロセス内でのシミュレーション**(複数
  witness を同一マシン上の独立した状態機械としてモデル化したもの)であり、
  実ネットワーク越しの複数物理ノードでの合意形成は別タスク(follow-up)。
  ADR-2607110300 Addendum 1-4 が確立した「本物の複数物理マシン間での witness
  通信」の実績はまだこの新アルゴリズムに対して再検証されていない。
- (−) leader 選出が round-robin(予測可能)であることは、実際に敵対的な
  第三者 witness が現れる段階までの意図的な簡略化として残る。
- (−) 依然として「分散型/ブロックチェーン」と対外的に名乗ってはならない
  (運営主体は単一のまま — ADR-2607110300 Phase 4 の到達条件は不変)。

## Alternatives

- **PBFT(Castro-Liskov 型)**: 却下。all-to-all の O(n²) 通信は witness 数が
  増えたときのオーバーヘッドが大きく、view-change の実装も HotStuff より複雑。
  witness-quorum の閾値署名プリミティブとの相性は同等だが、通信パターンの
  単純さで HotStuff を選んだ。
- **既存の Cosmos-SDK/Tendermint やその他外部チェーン実装をそのまま採用**:
  却下。CLAUDE.md のランタイム優先順位(kotoba wasm > clojurewasm > cljs >
  nbb、JVM/bb は最後の手段)に反する新規 Go 依存を持ち込むことになり、
  既存の witness-quorum/murakumo overlay という cljc/clj 資産をそのまま
  再利用できるメリットを捨てることになる。
- **ADR-2607110300 Phase 2 の crash-fault-tolerant post-hoc cosign のまま
  据え置き、BFT化は本当に第三者 witness が現れてから**: 検討したが、
  オーナーの明示判断(2026-07-15)により却下 — プロトコル書き換えコストを
  将来に先送りしない、アルゴリズム自体を今から BFT 前提で書く。
- **「新しいL1をドメインごとに複数作ってよい」への全面転換**: 却下。
  ADR-2607030030 の「チェーンを増殖させない」という規律は本ADRでも維持し、
  スコープを ENGI/EN 一つに限定した。

## References

- ADR-2607101100(engi-mutual-credit-kotobase-native-design)— v1、本ADRが
  拡張するベースライン。スキーマ・ハンドシェイクは無変更で継承。
- ADR-2607101200(engi-mutual-credit-dht-substrate-revival)— v1 が明示的に
  諦めた push gossip/neighborhood validator の復活ロードマップ。本ADRの
  witness-quorum ベース BFT は、このADRが着手条件としていた「複数の消費者が
  同じ基盤を要求する」を満たしうるが、本ADRは source-chain/warrant/
  neighborhood の CLJC 移植そのものは対象にしない(独立した follow-up)。
- ADR-2607110300(kotoba-lang-net-kotobase-cloud-murakumo-decentralization-roadmap)
  — 本ADRが「やらないこと」の2項目(新L1禁止、Byzantine前提の排除)を
  ENGI/ENスコープに限定して撤回する対象。Phase 1〜4 の命名規律・witness-quorum
  /murakumo overlay 再利用方針は継続。
- ADR-2607030030(murakumo-inference-economy-gtm)— 「新L1は作らない」の
  originの決定。murakumo 推論経済のchain gatewayをmint/burn限定にする方針は
  本ADR後も不変(本ADRのL1はENGI/EN専用でmurakumoの経済台帳を巻き込まない)。
- `kotoba-lang/witness-quorum` — 閾値署名/attestation プリミティブ(QCの
  署名収集にそのまま再利用)。
- `kotoba-lang/murakumo` `src/murakumo/overlay/` — QUICトランスポート
  (propose/vote/new-viewメッセージの輸送にそのまま再利用)。

## Addendum (2026-07-15): 経済台帳の除外条項を時限化(ADR-2607995000)

ADR-2607995000(三圏経済)により、本ADRの「murakumo の経済台帳をこの L1 に
巻き込まない」は**時限条項に再スコープ**された: Phase 3(公開マーケット)が
credits 台帳にグローバル順序を要求するまでは巻き込まない。その時点が来たら、
本ADR自身の「新 L1 を増やさず engi/L1 を再利用せよ」に従い、credits 台帳は
engi/L1 の2番目のドメインとして乗る(ADR-2607101200 の「複数の消費者」着手
条件の充足でもある)。それ以外の決定(アルゴリズム・スキーマ・命名規律)は
不変。
