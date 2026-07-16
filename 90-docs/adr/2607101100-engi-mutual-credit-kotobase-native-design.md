# ADR-2607101100: ENGI/EN 相互信用通貨 — kotobase-native 再設計(v1、推奨)

**Status**: accepted(v1 実装・main着地・本番kotobase.netへのライブ検証済み)
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki

> **2026-07-10 追記(v1 実装完了 + 本番で判明した実制約)**: `kotoba-lang/engi`
> (新規リポジトリ、公開、west登録済み)として実装した。スキーマ・ハンドシェイク
> (propose/validate/counter-commit/finalize)・fold残高計算・fork検知は本ADRの
> 設計どおり。テスト: pure fnユニットテスト18本(JVM)+フェイクkotobaseクライアント
> でのハンドシェイク統合テスト29本(0失敗)+**本番 `kotobase.net` への実ライブ
> 検証**(使い捨て did:key の2エージェントで実際に propose→validate→
> counter-commit→finalize の全サイクルを実行し、サーバから読み直した残高が
> alice=-15・bob=+15・net-zero・違反なしであることを確認)。
>
> **本番で判明した重要な実制約(§3「検証」の前提を弱める)**: `validate-proposal!`
> は「受信者が送信者のグラフを独立に読んで検証する」設計だが、**本番
> kotobase.net の apex は他者のグラフへの未認証読取り(`:public-reads?`)に
> 対して 401 を返す**(2026-07-09/10実測。グラフ所有者本人の鍵でしか
> 満たせない CACAO を全呼出しに要求するため)。つまり **現時点では
> クロスエージェント検証が機能せず**、実装は「自分自身のグラフを自分の鍵で
> 読む自己検証」に後退している(フェイククライアントでは設計どおりの
> クロスエージェント検証を完全にテスト済み — 本番インフラのギャップであり
> ENGI設計自体の欠陥ではない)。
>
> **この制約の解消は ADR-2607022600 Wave 4(CACAO depth-2 delegation /
> multi-graph grants)に直接依存する** — kotobase.net 側に「他者のグラフを
> 読む権限を委任された CACAO」または「グラフの公開読取り登録」機能が
> 実装されるまで、ENGI の二重支払い防止は実質的に自己申告ベースに留まる。
> **Wave 4 が本ADRの実運用上の前提条件になったことを、ここに明記する。**
> それまでの間、ENGI を「価値のある決済」に使うのは推奨しない(内部の
> 実験的な貢献記録としての利用に限定する)。
>
> 副次的な発見(実装で対処済み): kotobase.net は tx_edn の裸の数値リテラルを
> JSON引用済み文字列として返す(genko の `:gh.genko/rev` と同じ既知の癖) —
> `engi.store/fold-entities` で数値強制変換パスを追加して対処。

## Context

ENGI(縁起)は kotoba の内部相互信用通貨 **EN(縁)** — Holochain の HoloFuel を
明示的にモデルとした、net-zero・非発行・エージェント中心の通貨。原設計
(`kotoba-lang/kotoba/docs/ADR-engi-mutual-credit-on-chain.md`, R0〜R9実装済み)
は `kotoba-dht`(per-DID SourceChain + Warrant + neighborhood validation)の
上に構築されていたが、2026-07-01 のRustワークスペース削除(ADR-2607101000参照)
で実装ごと失われ、依存先の kotoba-dht 基盤自体も CLJC 未移植(ADR-2607022600
Wave 5、投機的・保留)のままである。

本ADRは、**kotoba-dht基盤の復活を待たずに**、既に本番稼働している
`kotobase.net`(kotoba-lang/kotobase-server + kotobase-peer + kotobase-client、
datomic風 `{:q :transact! :db :pull :entid}` API)の上に、原設計の**本質的な
性質を保ったまま** ENGI を直接実装するv1設計を提案する。フル DHT 基盤の復活
(P2P gossip・warrant伝播・近隣validator)は姉妹ADR(2607101200)に委ねる。

### 本セッションで実測した、設計上重要な kotobase.net の制約

1. **書込権限は「自分自身のグラフにのみ」**(2026-07-09実測、genko kotobase
   永続化 ADR-2607091800 / kotobase-client apex CACAO対応で確認): kotobase.net
   apex の CACAO 検証は `kotoba://graph/` スコープが**発行者(issuer)DID と
   一致**することを要求する。つまり **A は B のグラフに書き込めない** —
   原設計(1つの transfer を双方のチェーンに追記)をそのまま持ち込めない。
2. **`q`(datalog)にはキーワード正規化のバグが今日修正されたが、`:find/:where`
   形式は依然として空 rows を返す**(ADR-2607091800 follow-up、実測)。読出しは
   `:datoms`(`:eavt` scan)+ 呼び出し側での fold に統一するのが確実(genko
   `genko_store`/`kami-genko` の `kotoba-load!` が既に採用している実績パターン)。
3. **cardinality-one/upsert が無い** — 再 assert は蓄積し、reader が log順
   last-wins で fold する(kotobase-server の既知の制約)。これは
   **append-onlyな相互信用台帳にとってはむしろ好都合**(Holochainの
   source chainも本来追記専用)。
4. **グローバル合意・CASが無い** — 複数の書込みが競合した場合、両方とも着地
   しうる(race)。原設計もそもそもグローバル順序を要求しない設計
   (`spender_prev` ピン留め + 事後の fork 検知)なので、この制約は**原設計の
   思想と整合する**(むしろ相性が良い)。

## Decision

### 1. スキーマ — エージェントごとのグラフが Source Chain の代替

各エージェント(did:key)は自分の EN グラフを持つ:
`kotobase/db/<did:key>/engi`(genko の `kotobase/db/<did:key>/genko` と同じ
命名規約)。CACAO の自己認可原則(CLAUDE.mdの kotoba 節: 鍵由来グラフの
所有者=自己mint権限)により、**このグラフへの書込みは本人のみ可能** — これが
Holochain の「自分の source chain にしか author できない」という制約と
**そのまま一致する**。

```
;; genesis(グラフ作成時に1回だけ書く。信用限度額の宣言)
{:db/id "engi/genesis"
 :engi/kind "genesis"
 :engi/credit-limit -1000   ; マイナス方向への許容量(信用限度)
 :engi/created-at <ms>}

;; 各 transfer は debit(自分が払う側)/credit(自分が受け取る側)の
;; どちらかとして「自分のグラフ」にのみ書く
{:db/id "engi/tx/<uuid>"
 :engi/kind "debit"          ; または "credit"
 :engi/seq <このグラフ内の単調増加番号>
 :engi/prev-hash "<直前エントリの正規化hashか \"genesis\">"
 :engi/counterparty "<相手の did:key>"
 :engi/amount <int, EN単位>
 :engi/memo "<任意、例: genko chatでのmanga生成クレジット>"
 :engi/transfer-id "<TransferBodyのCID — 双方のグラフで一致する相関キー>"
 :engi/self-sig "<自分の Ed25519 署名>"
 :engi/counter-sig "<相手の Ed25519 署名(下記ハンドシェイクで取得)>"
 :engi/ts <ms>}

;; fork検知時、監査者(誰でも良い)が発行者本人のグラフに書く警告
{:db/id "engi/warrant/<uuid>"
 :engi/kind "warrant"
 :engi/evidence-tx-a "<tx entity id 1>"
 :engi/evidence-tx-b "<同一 prev-hash/seq を持つ tx entity id 2>"
 :engi/detector "<監査者の did:key>"
 :engi/detector-sig "..."
 :engi/ts <ms>}
```

原設計との違い: **1つの transfer は「双方のグラフに同一エントリを追記」
ではなく、「双方が各自のグラフに、同じ `transfer-id`(TransferBodyのCID)で
相関する debit/credit エントリを独立に書く」**。書込み権限の制約
(#1)にそのまま従うほうが、原設計より書込みモデルとして正直かつシンプル。
双方の署名を含む同一の TransferBody は誰でも独立に検証できるため、
監査可能性は損なわれない。

### 2. Transfer プロトコル(双方向確定ハンドシェイク、原設計の countersign を踏襲)

物理的な「双方のチェーンへの原子的な同時書込み」は不可能(#1)なので、
**提案→検証→相手側確定→自分側確定** の非同期ハンドシェイクにする
(実際の Holochain hApp 実装 — HoloFuel reference hApp — も、DHT自体は
原子的ではなく、この種の counter-sign ハンドシェイクをアプリケーション層で
行っている。原設計からの逸脱ではなく、実務的な相互信用 dApp の標準形):

1. **提案(propose)**: 支払人 A が次の `{seq, prev-hash}` を計算し、
   `TransferBody{spender, receiver, amount, spender-prev, nonce, ts}` を
   自己署名した草案を作り、**kotobase 外の側路経路**(v1では既存の
   aozora メッセージング/DM機能に相乗り、または専用の小さな handshake
   エンドポイントを新設 — どちらでも可、本ADRは経路を規定しない)で
   B に送る。この時点では A のグラフにはまだ何も書かない。
2. **検証(validate)**: B は A の EN グラフを `:datoms`(`:eavt`)で読み、
   fold して現在残高+信用限度の余地を確認し、`:engi/kind "warrant"` が
   A 宛に存在しないことを確認し、提案された `prev-hash` が A の現在の
   実際のチェーン先端と一致することを確認する。
3. **相手側確定(counter-commit)**: 検証が通れば、B は **自分のグラフに**
   credit エントリ(`:engi/kind "credit"`、`:engi/transfer-id` = 提案の
   TransferBody CID)を書き、B 自身の署名を付けた `counter-sig` を A に返す。
4. **自分側確定(finalize)**: A は B の `counter-sig` を受け取ったら、
   **自分のグラフに** debit エントリ(同じ `transfer-id`、B の署名込み)を
   書く。
5. **未完了ハンドシェイクの扱い**: A が finalize せずに離脱した場合、
   B 側には「確定していないクレジット」が残る。残高計算(下記#3)では、
   **相手側のグラフに対応する debit エントリが実在して初めて「確定」と
   数える**(双方向の相関確認)。片側だけの credit は使用可能残高に
   算入しない — Holochain の countersigning session が両者コミットする
   までは無効、というルールをそのまま踏襲。

### 3. 残高計算・不正検知(監査ベース、原設計の replay/audit を踏襲)

- **残高はプロジェクション**(保存された値ではなく、都度チェーンを
  replay して導出)。`engi-store.cljc`(新規、kami-genko/genko_store と
  同じ構造)が `:datoms` を読み、`:engi/seq` 順に fold して現在残高を
  計算する。
- **fork検知**: 監査(誰でも実行可、または定期ジョブ)は対象エージェントの
  グラフ全体を fold し、(a) `:engi/seq` が欠番/重複なく単調増加している
  か、(b) `prev-hash` チェーンが連続しているか、(c) 各 transfer の
  相手側グラフに対応するエントリが実在するか(双方向確認)、(d) replay
  のどの時点でも残高が信用限度を割っていないか、を検査する。違反があれば
  `:engi/kind "warrant"` を該当エージェント自身のグラフに書く(本人の
  グラフに書けるのは監査者ではなく本人だけ、という制約#1に反するため、
  実際には **警告を第三者が読める公開グラフ
  `kotobase/db/shared/engi-warrants` に書く**、または該当エージェントが
  自ら受理して自分のグラフに反映する二段階にする — 実装時に確定)。
- **P2P gossip なし**: kotobase.net 自体が既に「誰でも既知の did:key /
  graph CID を指定して読める」共有・検索可能な基盤なので、原設計の
  gossip 伝播は不要。「取引前に相手の warrant 状態を pull で確認する」
  だけで同等の抑止効果が得られる(push型の即時伝播ではなく、
  取引時点でのpull検証という現実的な劣化— 姉妹ADR 2607101200 の
  gossip復活で解消しうる項目として明記)。

### 4. ファイナリティ・スコープ境界(原設計から不変)

双方確定(#2 step 3-4)で**双方向の合意**は得られるが、グローバルな
即時ファイナリティではない(原設計と同じ)。EN は内部の貢献会計に
とどまり、**実際に決済される希少資産は境界の外側(etzhayyim の
USDC on Base L2)** — この境界は変更しない。

**murakumo の推論クレジット(`credits.cljc`)、kotobase.net の Stripe課金
とは別の通貨圏**であることを明記する。manga chat 統合(本セッションの
並行タスク)で EN を使う場合は、「エージェント間の創作貢献への相互信用」
という用途に限定し、compute費用(murakumo)や storage費用(kotobase.net
Stripe)と混同しない。

## Consequences

- (+) kotoba-dht 基盤の復活を待たずに、数日規模で実装・検証可能。
- (+) kotobase.net の実測された制約(自己グラフのみ書込み・datoms経由の
  読出し・cardinality-oneなし)に正直に従うことで、原設計より実装が
  シンプルになり、genko の kotobase 永続化(ADR-2607091800)と同じ
  パターン(グラフ命名・datoms fold・rev/prev-hash ガード)を再利用できる。
- (−) push型 warrant 伝播が無いため、不正検知は「取引前に pull で確認」に
  留まり、原設計ほど即応的ではない。実害が出た場合、姉妹ADR
  (2607101200)の gossip 復活で強化する。
- (−) ハンドシェイク(#2)の送受信経路(側路)は本ADRでは規定しない —
  実装時に aozora の既存メッセージング機能に乗せるか、専用の小さな
  エンドポイントを新設するかを決める必要がある。
- (−) 本ADRは設計のみで、コードは一切変更しない。実装は個別ADRまたは
  このADRのステータス更新で記録する。

## References

- `orgs/kotoba-lang/kotoba/docs/ADR-engi-mutual-credit-on-chain.md` — 原設計
  (R0〜R9、Rust削除で失われた実装)
- ADR-2607101000(kotoba-orphaned-rust-crates-migration-roadmap-addendum)—
  本ADRの親文脈
- ADR-2607101200(engi-mutual-credit-dht-substrate-revival)— フルDHT基盤復活
  による強化パス(v2)
- ADR-2607091800(genko-kotobase-datomic-persistence)— 本ADRが踏襲する
  グラフ命名・datoms fold・rev競合ガードの実装前例
- ADR-2607022600(kotoba-database-crates-cljc-migration-roadmap)Wave 5 —
  kotoba-dht基盤の現状(未着手・投機的)
