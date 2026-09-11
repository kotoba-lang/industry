---
name: kotobase-planes
description: kotobase / kotoba の「面」を設計・実装・レビューするときの正本。identity（CID）/ naming（IPNS・DNSLink）/ location（bytes host・entry host）/ kind（document・service・placement）の app 4 面、privacy 面（Identity≠Authority、Integrity≠Confidentiality、resource budget≠privacy budget、read 可視性 4 段）、L2 graph CID と archive Location の分かれ方、live service の永続化境界 kotobase.net、join 到達範囲は合成の有無、分散型経路に D1 を前提にしない（消して再構築できるか）、base は datom 面であって Datalog ではない、Datalog / kotobase 方言 / Datomic は 3 つの名前、物理層 block → CARv2 pack → object と 5 つの canonical IR、agent loop の正本は Git + EDN + DataLad で DB は projection。「kotobase」「datom」「Datalog」「CID」「IPLD」「IPNS」「D1」「pack」「CAR」「privacy」「app manifest」「publish」「kotobase.net」「Durable Object」で発火。CLAUDE.md の 10 節から 2026-09-11 に切り出した正本（ADR-2609112300）。
---

# kotobase-planes — 面を混ぜない

**CLAUDE.md の次の 10 節はここへ委譲している。** CLAUDE.md 側には skill を読まなくても
効く不変条件だけが残っており、実測・実例・現在地・コマンドはこの文書が正本。
各節の ADR が最上位の正本で、機械可読は `manifest/repository-rules.edn`。

1. L2 graph CID と kotobase archive Location
2. app の面は 4 つで、混ぜない
3. privacy は 4 つ目の面
4. live service の永続化境界は `kotobase.net`
5. kotobase の join 到達範囲は合成の有無で決まる
6. blockchain / 分散型経路に D1 を前提にしない
7. kotobase の base は datom 面であって Datalog ではない
8. Datalog / kotobase 方言 / Datomic は 3 つの別の名前
9. 物理層は block → CARv2 pack → object（+ 5 つの canonical IR）
10. agent loop の正本は Git + EDN + DataLad、DB は projection

---

# CLAUDE.md に 2026-09-11 まで残っていた本文（逐語、ADR-2609112300）

以下は CLAUDE.md から**逐語で**移した本文である（2026-09-11、ADR-2609112300。AGENTS.md の
読み込み上限 31,457 字に合わせて CLAUDE.md を不変条件だけに絞った）。CLAUDE.md 側には
skill を読まなくても効く規則だけが残っている。ここが理由・実測・罠の正本。

## L2 graph CID と kotobase archive Location は同じ bytes でも CID 文字列が分かれうる（repo-wide mandatory、2026-08-14、ADR-2608148200）

**公開 identity は hasher が付けた CID（オブジェクト自身の codec）。kotobase `PUT /ipfs/:cid` は raw CIDv1 だけを受ける。** codec が raw でないオブジェクトを archive するときは、同じ bytes の raw CID を Location として PUT する。identity の CID 文字列を PUT しない（400 `not-raw-sha256`）。

- L2 graph CID は `chain.core/commit!`（ADR-2608145400）。protocol は hash しない。
- overlay（CreateLink）は親 CID を変えない。merkle put は親 CID を変えるが graph CID は動かない。
- `:kotoba.graph/cid` は identity。`:kotoba.graph/head` は naming（IPNS）。session kgraph の datoms は公開 resource ではない。
- lock の `:kotoba.*` に archive 専用の raw CID を載せない。Location は protocol 外の記録（例 `:graph {:raw-cid …}`）。
- document が raw なら identity と Location の文字列は一致してよい。dag-cbor commit では一致しない。それをバグにしない。


## app の面は 4 つで、混ぜない —— identity / naming / location / kind（repo-wide mandatory、2026-09-09、ADR-2609092600）

**正本は ADR-2609092600、機械可読は `manifest/repository-rules.edn` の
`:workspace-policies :app-plane`、検査は `scripts/verify-app-content-address.cljk`。**
ここに残すのは、それらを読まなくても効く不変条件だけ。

    identity  ipfs://{cid}                      不変。app が記録する唯一のアドレス
    naming    ipns://{k51} または DNSLink        可変な版
    bytes     https://{cid}.ipfs.kotobase.net   Location — 1 面
    entry     https://{name}.itonami.app/       Location — 1 hostname、DNSLink で解決

- **上 3 行が protocol、`:published` だけが protocol の外。** だから bytes host を
  差し替えても manifest は 1 文字も動かない。**Location は設計ではなく設定である。**
- **`:kotoba.app/kind` は 3 値で、既定を持たない** —— `:document`（content address 必須）/
  `:service`（動的 Worker、宣言で免除）/ `:placement`（actor の配置、宣言で免除）。
  **宣言の無い manifest は finding のまま残る。** 既定を `:service` にすれば 82 件が
  一晩で緑になり、1 本も publish されない。免除は書かれた判断であって書き忘れではない。
  legacy 表記 `"appview"`（文字列）は `:document` として正規化する（一括改名はしない）。
- **entry は path ではなく hostname にする。** DNSLink は hostname 単位なので
  `itonami.app/{name}/` では per-app の naming が原理的に解決せず、解決させるには
  path→IPNS の router が要る —— それは location-addressed な hop を naming plane の
  真ん中に戻すことである。下の層（`{cid}.ipfs.*` / `{k51}.ipns.*`）が既に subdomain
  gateway であることと、app ごとに origin が閉じる（localStorage / SW scope / CSP /
  cookie）ことも同じ側に効く。⚠ entry 名は **1 つの DNS ラベル**でなければならない。
- **`:document` は自己完結の 1 ファイルである。** 相対パスで runtime を取りに行く
  ページは HTTP で配るディレクトリとしては正しく、content address としては誤り ——
  **単体で取得して動かないアドレスは、アプリの半分のアドレスでしかない。**
  組むのは `cloud-itonami` の `scripts/gen-selfcontained-doc.cljs`、動くことの確認は
  `scripts/verify-selfcontained-doc.cljs`（実 Chrome、`file://`）。
- **CDN から取りに行く document に CID を付けない。** 自己完結ではないので、その CID は
  app が何を実行するかを覆っていない。逃げ道は `--vendor <url>=<path>=<sha256>` 1 つだけで、
  **digest が一致したときにだけ**ローカルの同一バイト列に差し替える。
- **publish は 2 面に書く。`scripts/publish-document.cljk` を使う。**
  `PUT /ipfs/{cid}` が書くのは **B2**（IPNI の広告が retrieval address として名指す面）、
  app を配る origin plane が読むのは **R2 の `ipld/{cid}`**。片方だけに置いた document は
  bytes plane で 200、web plane で 502 になる。**`content-address publish` を単体で
  使わない** —— それは archive にしか書かない。origin plane に archive への fallback を
  足す案は採らないと決めた（2026-09-09）。
- **公開 announce（IPNI）は drain worker からしか行えない。** chain を署名している鍵は
  `net-kotobase-private-ipni-drain` の write-only secret にしか存在せず、kagi にも
  keychain にも無い（`manifest/ipni-publisher.edn` の実測）。**別 worker を立てて
  署名しようとしない。**

⚠ **「ビルドできた」を「正しい」と読まない。** この面で 2026-09-09 に 2 件、
**ビルドが成功して出力が間違っている**欠陥が出た: ①`str/replace` の置換文字列で
JavaScript が `$&` / `$1` / `$'` を解釈し、minified bundle が 4 分の 1 に化けた
②`js->clj` が match の `.index` を落とし、元のタグを残したまま body を末尾に足した。
①は browser check が、②は byte 同一性の self-check が捕まえた —— **片方だけでは
両方は捕まらない**（①のとき browser check は 6 本中 4 本を通していた）。
**成果物を出す検査は、成果物を実行し、かつ入力が verbatim で入ったことを確かめるまで
pass にしない。**


## privacy は 4 つ目の面で、identity / authority / evidence と混ぜない（repo-wide mandatory、2026-09-10、ADR-2609108000）

**正本は ADR-2609108000、機械可読は `manifest/repository-rules.edn` の
`:workspace-policies :privacy-plane`。** ここに残すのは、それらを読まなくても効く
不変条件だけ。

    Identity  != Authority                 CID は「この bytes だった」だけを証明する
    Integrity != Confidentiality           hash で検証できることは秘匿ではない
    Auditability != Publicity              監査可能であることは公開であることではない
    Content addressing != Safe disclosure  アドレスが付いたことは出してよいことではない

**この 4 つは新しい機構の要求ではなく、既存の機構をどう読むかの規則である。**
実装側は既に分けている（`kotobase.execution-contract` / `output-attestation` /
`disclosure-grant` / `transparency-log`）。分けていなかったのは文書の側で、
**設計文書が 2 つを同じ段落で語る限り、次に読む agent はそこを 1 つの概念として学ぶ。**

- **resource budget を privacy budget と呼ばない。** `kotobase.query.bridge/default-max-datoms`
  と `:materialize-over-budget` は実在するが、これは**走査量の上限**であって推論の遮蔽ではない。
  件数上限をいくら下げても `(count (where ...))` が 0/1 を返す限り存在は漏れる。
  **同じ語で呼ぶと、走査上限が landed した日に推論防御も landed したと読まれる。**
- **inference channel は防ぐ前に測る。** differential privacy / 最小結果集合 /
  threshold aggregation を先に入れない。先にやるのは今の ayatori が何を漏らすかの
  再現手順（存在照会 0/1、adaptive probing による個体値復元、result identity の変化）。
  **測っていない防御は、測っていない攻撃に対する劇場。**
  ⚠ **2026-09-10 に測った。レビューが挙げた形（aggregate だけ許す surface）は
  この plane には存在しない** —— 可視性の seam は `visible?` 1 本で per-datom・
  required なので、aggregate だけを許すモードが無い。**在ったのは走査上限の側で、
  `materialize` はどちらの arity にも `visible?` を取らないため、ceiling は
  可視性の判断が 1 度も行われないうちに「止めた時点の datom 数」を `ex-data` で
  返す。** 値を 1 つも読めない呼び出し側が、正確な総数・名指しの個人の存在・
  その人の項目数を回収できた（`max-datoms` を名指さない 2-arity でも組み込み閾値で
  漏れる）。**resource budget は privacy budget でないだけでなく、それ自身が
  開示経路である。** 再現は `orgs/kotoba-lang/ayatori` の
  `bench/inference_channel.cljs`（記録は `bench/inference-channel-01.edn`）。
  **防御はまだ入れていない** —— 何を選ぶかは決定であって、これはその入力。
  ⚠ **deploy された面は別に測ってあり、答えが違う**（`kotobase-server` の
  `scripts/measure_wire_disclosure.cljs`）。refusal は確かに正確な件数を
  wire に載せる（`:details` と `:refusals` の両方、可視性判断より前に数えた値）。
  **ただし cap は caller が動かせない** —— policy は source の literal、call site は
  全て 1-arity、2 つの policy key は server の他のどこにも無い。したがって
  **library seam は「任意の collection の正確な総数を約 12 probes で」、
  deploy 面は「cap を超えた range について 1 つの数、cap 未満は探れない」。**
  **「漏れる」と「固定閾値の上で 1 つ漏れる」は別の決定を要求する。**
  片方の測定でもう片方を語らない。
- **agent が触れてよいのは propose まで。** `generate → parse → schema validate →
  static effects → authority check → risk classify → admission → execute` のうち、
  agent は左 3 つ。`ayatori.agent/validate` は実在するが、**validate が通ることは
  authorize ではない。**
- **消去は CID 削除ではなく crypto erasure**（`disclosure-grant` + `authority-window` +
  `crypto-policy` の epoch）。**新しく設計しない** —— 実装側は既にこの道を採っている。
- **監査ログ自体が個人データである。** public に出るのは commitment、principal /
  purpose / resource / timestamp は selective disclosure。
  ⚠ **2026-09-10 に測ったら、境界は既に正しかった** —— receipt は identity を
  運べず（`receipt-keys` はちょうど 9、`exact-keys!` が `:principal` を
  `:invalid-keys` で拒否、`forbidden-keys` は入れ子も歩く）、`:principal` は
  request envelope の側にあって receipt は digest でしか指さない。**代わりに
  reach の gap が出た** —— kotobase の 6 namespace が `.clj` で、**public audit
  plane はこの service が deploy される runtime では走らない**。
  「機構が在る」と「service の走る場所で走る」は 2 つの主張。

- **read の可視性は 4 段である**（2026-09-10、ADR-2607280100 Step 2）。
  `:kotobase.policy/prefix-levels` で prefix ごとに level を宣言し、
  `(>= viewer-rank level-rank)`。**prefix の LIST が「何が保護対象か」、level が
  「どれだけか」** —— 2 つ目の真実の源を作らない。**level を持たない prefix は
  `:restricted`** なので、**旧 binary は新規則の特殊ケースであって隣の分岐ではない**
  （客体側の丸めを壊すと既存テストが落ちる、という形で確かめてある）。
  - **格子を複製しない。** `kotoba.security.information-flow/ranks` が唯一の定義で、
    使う側は依存を足して**借りる**。4 エントリの map をコピーするのが drift の始まり。
  - ⚠ **未知ラベルの丸めは主体と客体で逆。客体は上へ、主体は下へ。**
    共通規則にすると必ず片側で誤る —— **主体側を上へ丸めるのは誤字による権限昇格**。
  - ⚠ **宣言できて強制されることは、配れることではない。**
    `read-classified/<label>` を発行する経路はまだ無い。
  - **read でも格子を評価する**（Step 3）。egress は write の問いなので write 限定で
    正しく、足りていなかったのは read 側。`authorize-xrpc` は全 method で
    `classification-decision` を出す。⚠ **abac は「申告が無い」を violation にしない**
    （`required-rank` が nil になり nil は何も生まない）ので、**分類の無い resource は
    正しく clearance を持つ resource と同じ値で許可される** —— `classification-required?`
    がこれを拒否に変える。**判定は複製しない**（比較は abac のまま。この層が記録するのは
    「abac に判定させるだけの申告が在ったか」だけ）。
  - ⚠ **未申告の拒否は policy 拒否より上に置く。** 両方が拒否する場合、
    行動可能なのは「申告していない」で、「policy に拒否された」は
    **到達していない検査の報告**である。

⚠ **依存を足すことは、その層が在ることではない。** 実測 2026-09-10:
`kotobase-server` に security 依存が無いという gap を閉じようとして、
まず「Step 2 が transitively 届いているのでは」と考えたが**測ると偽**だった ——
server は `kotobase-peer` を古い sha に pin しており、その pin は**実測を根拠に
凍結されている**（外すと 78/332 green が 16 failures）。**読む側の無い依存を
先に足さない** —— deps.edn に書いても src が 1 行も参照しなければ gap は動かない。

⚠ **この面に gate は無く、それは決定である**（ADR-2609108000 D8）。上の 4 つは
「文書が 2 つの概念を混ぜていないか」を問うもので、機械が判定できる述語ではない。
**落ちることを確かめていない gate は劇場**なので landing させていない。

⚠ **2026-09-10 に 4 件を測り、3 件が動いた。** 不在のまま残ったのは
**query の推論防御 1 つだけ**である:

- **correlation** —— 不在ではなかった。envelope の nonce が全ての下流識別子に
  届くので、同じ principal が同じ query を 2 回走らせても公開識別子は 1 つも
  一致しない。
- **traversal 上限** —— 在る。`ipld.graph` と `prolly-tree.diff` は
  **部分集合すら拒否する fail-closed**。ただし **`scan-prefix` の 3-arity は
  `limit = nil = 無制限` で、deploy される read path の 2 つの call site は
  どちらもそちら**（`scan-range` には limit 引数が無い）。**在ることと、
  通る扉に在ることは別。**
- **LINDDUN matrix** —— 敷いた。ただし **78 セル中 10 セルだけ**が記録されており、
  **68 セルは未測定であって clean ではない。**

**「もう塞がっている」と読まない。** 表・現在地・再現手順は ADR と
`90-docs/security/` が持つ。**ここに数値を書き写さない。**


## live service の永続化境界は `kotobase.net`（repo-wide mandatory、2026-08-15、ADR-2608159100）

**live service が生成・収集する proof、actor、wiki、graph、event、index の durable source は
Kotobase とする。authority と既定 API origin は `https://kotobase.net`。protocol 固有の
wire contract は capability subdomain を使える。** provider の実装（R2 / B2 / IPFS）を
application の前提にしない。

- immutable bytes は `PUT/GET https://kotobase.net/ipld/:cid`。書く前と読む時の両方で
  CID を検証する。application 自身の R2 binding を production path に直書きしない。
- stable な capability origin は `datomic.kotobase.net`、`sparql.kotobase.net`、
  `cypher.kotobase.net`、`gremlin.kotobase.net`、`graphql.kotobase.net`、
  `s3.kotobase.net`、`git.kotobase.net`、`atproto.kotobase.net`、
  `pinning.kotobase.net`、`search.kotobase.net`。apex path facade と同じ
  authority/policy に属する。`search.kotobase.net` は Datalog dialect ではなく
  inverted-postings serving plane（ADR-2608170600）。query-dialects に足さない。
- edge 内部の datom/CID execution capability は `datoms.kotobase.net`。
  `graph-database.kotobase.net` / `backend.kotobase.net` / `graphdb.kotobase.net` は
  2026-08-15 に Custom Domain と DNS から除去済みの retired hostname。rollback alias を含め
  production config / SDK / docs に再導入しない。
- RDF4J は別 database product ではなく `sparql.kotobase.net/repositories/default` の path
  compatibility。GraphQL は `graphql.kotobase.net/graphql` の独立した read-only document
  query protocol で、RDF4J/SPARQL の別名ではない。SQL は独立 origin ではなく query dialect。
  implementation/product 名を capability 名として増やさない。
- logical metadata、provenance、actor、proof 評価、CID index は
  `https://kotobase.net/api/*` の datom 面に置く。bytes 本体を datom に埋めない。
- Durable Object / D1 / KV は alarm、lease、single-writer、cursor、session、cache、projection
  にだけ使える。消しても Kotobase の block + datom から durable state を復元できなければ違反。
- write は fresh nonce の CACAO capability を route ごとに使う。credential は既知の識別子を
  credential 専用ツールから1件だけ取得し、repo・ログ・datom・block に保存しない。
- 8 MiB を超える object は datom や `/ipld` に押し込まず、`kotobase.net` から取得した
  presigned transfer capability を使う。入口の authority は同じく `kotobase.net`。
- localhost / mock / testnet は明示した環境でのみ可。production の接続失敗時に direct R2、
  provider host、DO SQL へ黙って fallback しない。
- Git 管理の policy / source / artifact は引き続き Git + EDN + DataLad が正本
  （ADR-2608039700）。この規則が対象にするのは **live service の runtime durable plane**。

機械可読な正本は `manifest/repository-rules.edn` の
`:workspace-policies :live-service-durable-data`。検査は
`kbb --backend sci scripts/verify-kotobase-persistence-policy.cljk`、CI/CD は murakumo fleet の
`root-kotobase-persistence-policy` gate。新しい service は README / ADR / config で
Kotobase の database/ref と block codec を宣言する。


## kotobase の join 到達範囲は ref の本数ではなく合成の有無で決まる（repo-wide mandatory、2026-09-04 訂正、ADR-2809040800）

> **join が届く範囲は、query 時に 1 つの `IPatternSource` へ合成されている範囲である。**
> ref を分けたこと自体は join を壊さない。合成を忘れたことが壊す。

⚠ **この節は 2026-09-04 に反転した。** それまでは「join の到達範囲はちょうど ref
1 本で、別 ref に分けたものは二度と join できない。これは実装の都合ではなく
kotobase のデータモデルそのもの」と書いていた。**後段は実測で偽**
（ADR-2809040800）。`kotoba-lang/datom-source` の `merged` に、答えがどちらの
partition 単独にも存在しない 2 ホップの join を通すと届く:

```
partition A = [alice works-at acme,  bob works-at globex]
partition B = [acme located-in kyoto, globex located-in osaka]

partition A 単独 -> #{}      partition B 単独 -> #{}      merged A+B -> #{"alice"}
```

旧文は書かれた時点では正しかった —— `kotobase.core/open` が `:ref-name` を 1 つしか
取らず、`q` が materialize 済み db を前提にしていた頃の記述である。その後
`datom-source` の `IPatternSource` seam が入って天井が動いた。**ある日の実装の
天井をデータモデルの性質として書くと、天井が動いた後も設計を縛り続ける**（下記
`rule-kaizen` 節が名指ししている形そのもの）。

- **分割してよい。ただし query 面で `merged` に合成することを設計に書く。**
  問われるのは分割の可否ではなく、合成の有無。
- **合成されていない分割を黙って作らない。** 「この境界を跨ぐ分析は N クエリ +
  マージになる」と代償を名指しする義務は残る。変わったのは、その代償を払わずに
  済む道（合成）が実在するという点だけ。
- **書き込み負荷を理由に分けるのは、いまは正当な選択肢。** 合成する前提なら、
  単一 writer + バッチングに寄せる必要はない。CCU が増えて増えるのはイベント数
  であってトランザクション数ではない、という観察は変わらない。
- **Durable Object のストレージ（`ctx.storage.sql`）に kotobase の durable plane を
  置かない。** 各 DO の SQLite は private で他から引けないので、object の数だけ独立した
  データベースができ、datom 面が孤島に割れる。**DO は直列化器・realtime room として
  使い、ストレージは共有バックエンド**に置く。DO はグローバル一意 +
  シングルスレッドなので、「書き手はちょうど1人」を*実装せずに*得られる — 自前の
  write lease や fencing epoch を書かない。
  ⚠ **この項は 2026-08-03 に「ストレージは D1」から書き換えた**（下記「D1 を前提に
  しない」節、ADR-2608039000）。要件は「**共有**バックエンドであること」（＝クエリ面を
  割らないこと）であって D1 であることではない。分散型経路では D1 を前提にしない。
- **クエリ到達範囲と書き込み並列度はもう対立しない。** 合成すれば両立する。
  設計文書に書くべきなのは「どちらを採ったか」ではなく「どこで合成するか」。
- **本当の制約はコスト側にある。** query 名前空間は materialize 済み db（4 つの
  in-memory index）を取るため、コストが O(result) ではなく **O(database)** に固定
  される。実測（2026-08-01, arrangement）: 2k facts で 57ms / **50 block-read**、
  32k で 678ms / **640 block-read** —— 返る行数によらず database のサイズに線形。
  IPLD 越しでは block-read がそのまま network round trip になるので、ここが支配的に
  なる。**到達範囲を心配する前にこれを測る。**

実例（2026-07-26、この規則が生まれた事故 —— 分割そのものではなく **合成しなかったこと**が事故だった）: sekaiju MMO の設計で D1 の書き込み
スループットを心配し `/char` を 64 データベース・`/guild` 4・`/market` 16・`/ledger`
日次に分割した。容量と CAS レーンとしては妥当だったが、**ランキング・ギルド名簿・
「この item を誰が持っているか」・経済監査・モデレーション、横断クエリしたいものが
全部書けなくなっていた**。同じ設計内で DO ストレージを「per-object private だから
datom 面を割る」と退けておきながら、その論拠が自分のシャード案にも当たることに
気づいていなかった。

**隣接する規則: プラットフォームの制限が設計を縛ると書く前に、その制限に一番近い
自分の層のコードを読む。** 同日、D1 の 2 MB row 上限を「fold の責任」と書いたが実際は
`kotobase_peer.block_sizing` が 16–128 KB でブロックを刻んでおり1桁以上の余裕があった
（実装者に既存の仕組みを作り直させるところだった）。bound parameter 100 も「33 ブロックで
chunk が要る」と書いたが既存 provider は 1 ブロック 1 INSERT（4 パラメータ）だった。
**制限の数値を正しく引用できていても、誰の責任かを間違えると設計が嘘になる。**


## blockchain / 分散型経路に D1 を前提にしない（repo-wide mandatory、2026-08-03、ADR-2608039000）

**オーナー指示（2026-08-03）「基本的に blockchain, 分散型経路に d1 は前提にしないで」。**
blockchain・合意・chain 状態・ref/head・台帳・DID/identity・IPFS/IPNS など、
**分散性を主張する経路では Cloudflare D1（および他の単一ベンダの条件付き書き込み /
単一リージョン SQL）を前提（premise）にしてはならない。**

### 判定基準 — 「消して再構築できるか」

禁止と許可の線はここ1本で引く:

> **その D1 データベースを今すぐ削除したとき、データが失われるか、正しさが壊れるか。**
> - **壊れる → premise。分散型経路では禁止。**
> - **遅くなるだけで、content-addressed 面から再構築できる → cache / projection。許可。**

具体的に、分散型経路で D1 が担ってはいけない役割:

- **順序 / CAS / 合意の裁定者**（`UPDATE … WHERE sequence = ?` で勝者を決める）
- **head・ref・chain 状態・台帳の source of truth**
- **recovery・sequencing・正しさが依存する対象**（＝これが無いと復旧できない）

許可される役割（消して再構築できるもの）: 読み取り高速化 cache、materialized view /
projection、index、local read accelerator、運用メトリクス。

### 何を代わりに使うか

- **block 面**: content-addressed な immutable object store（B2 / R2 / S3 / IPFS /
  DataLad-annex）。必要な capability は `#{:immutable-blocks :cid-addressed-read}` だけで、
  **条件付き書き込みは要らない**。
- **ref 面**: **inga**（ADR-2608038000）。**2f+1 の quorum 証明書それ自体が条件付き
  書き込み**なので、ホスト側の `UPDATE … WHERE sequence = ?` / `onlyIf.etagMatches` /
  `If-Match` は経路から消える。配備は
  `(storage/compose {:blocks <object store> :refs <inga>})` —— `compose` は ref profile を
  `refs` 側からのみ採るので、この分離は型で守られる。

### 適用範囲 — これは全面禁止ではない

**普通のアプリで D1 を使うのは従来どおり問題ない。** appview・セッション・管理画面・
社内ツールなど、分散性を主張していない経路は対象外（実測 2026-08-03 時点で
`d1_databases` binding を持つ Worker の大半がこれ）。**縛るのは「分散」「decentralized」
「blockchain」を名乗る経路だけ**であり、そこに単一ベンダの primitive が premise として
入ると**主張そのものが嘘になる**からである。

### 現在地（2026-08-03 実測、正直に）

| 経路 | D1 の役割 | 判定 |
|---|---|---|
| `net-kotobase/kotobase-cf-wasm` head plane | ref の CAS 裁定（testnet=authoritative / production=shadow） | **premise。inga 着地まで暫定 shim として稼働継続、その後撤去** |
| `kotoba-lang/kotobase-storage-d1` | block+ref backend adapter | **provider としては可。分散型経路の既定にしない** |
| `gftdcojp/engi`（settlement） | transfer ID の一意記録（replay 防止） | **要再設計。transfer ID は既に CIDv1 —— 正本は content-addressed 面、D1 は index** |
| appview 各種（mangaka / dougaka / kakure 等 ~25 Worker） | アプリのデータ | **対象外。従来どおり** |

**注意**: D1 が選ばれた経緯は「能力の優劣」ではない —— ADR-2607299900 が自分で書いている
とおり、**Cloudflare OAuth token に `r2` scope が無く R2 の `onlyIf.etagMatches` が
使えなかった**ための暫定選択である。恒久的な答えとして選ばれたことは一度もない。


## kotobase の base は datom 面であって Datalog ではない（repo-wide mandatory、2026-08-03、ADR-2608039970）

同じ「消して再構築できるか」テストを **query 層**に当てた結果。kotobase の層と premise 境界:

| 層 | 実体 | premise か |
|---|---|---|
| **L0** block / **pack** / ref / large-object | `kotobase-storage` の `IBlockStore`(CID) + `IRefStore`(CAS) + `IObjectStore`(transfer profile)、**block を束ねる CARv2 pack**（`io-ipld-car`）。S3/R2・B2・IPFS/IPNS・Postgres・D1・inga は**この境界の provider** | **premise**（消すと全部壊れる） |
| **L1** datom（triple / EAV）+ immutable value + content-addressed history | `arrangement` / `datalog` の spo・pso・pos・ocp | **premise**（全 query surface の論理モデル） |
| **L2** query language（Datalog / SQL / Cypher / SPARQL / GraphQL / Gremlin） | `kotobase.core/q`、`kotobase-query` bridge、各 protocol repo | **premise ではない** |

- **Datalog を全 query protocol の必須 IR にしない。** 新しい query surface を足すとき、その言語の
  algebra が L1 の index access path（`datalog.index` の `entity-attrs`/`by-predicate`/
  `by-predicate-value`/`refs-to`）に直接束縛できるなら**そちらが正しい**。`bridge/q` 経由は既定では
  なく選択肢。`org-w3-sparql-protocol` が取った **materialize-only 経路が正規経路**であって例外では
  ない（SPARQL algebra を Datalog に翻訳して戻すのは no benefit、と実装が自ら書いている）。
- **逆に L1 を迂回して surface ごとに独自の物理表現を持つのは禁止。** 共有するのは datom（L1）、
  共有しないと決めたのは Datalog（L2）。この2つを混同しない。
- **byte を datom 面に載せない。** `s3` の object body・`git` の loose object・`ipfs` の block は
  block 面（小）/ large-object 面（大、`:presigned-transfer`）に直行する。`PUT /ipfs/:cid` の
  4 MiB 天井は、この迂回の代償として実測済み。**CID 検証は store の仕事**
  （`kotobase.storage.verify/verifying-block-store`）であって各 surface の仕事ではない。
  メタデータ（bucket 一覧・ref→sha・pin request・audit）は datom 面でよい —— 分けるのは bytes。
- **kotobase 方言（`kotobase.core` の Datalog API / `kotobase.datomic` の EDN grammar）は残すが、
  位置づけは surface の1つ。** 「kotoba : kotobase = Clojure : Datomic」（ADR-2607032500）は repo 名と
  用語の由来であって、**設計の前提に昇格させない** —— 全 surface を Datalog 経由にする設計はここから来た。
  この方言を `Datomic` と呼ばない理由は次節。


## Datalog / kotobase 方言 / Datomic は 3 つの別の名前（repo-wide mandatory、2026-08-18、ADR-2608189300）

**私たちが日常「Datalog」と呼んで書いているものは Datalog 標準ではない。** 学術 Datalog の
標準記法は `path(X,Y) :- edge(X,Y).` の Prolog 風であって、`:find` / `:where` の EDN 形ではない。
EDN 形は Datomic が作った方言であり、私たちが書いているのはその系譜の**別の方言**である。

| 語 | 何を指すか | 所有 | 実体 |
|---|---|---|---|
| **Datalog** | クエリ言語の**形式**。range-restricted なら停止する | 誰のものでもない | `kotoba-lang/datalog`（storage-free エンジン） |
| **kotobase 方言** | 実際に書く **EDN 記法** `[:find ?e :in $ :where [?e :attr ?v]]` + `:rules` | **ここ** | `datalog.core` が実装、`kotobase.core/q` が露出 |
| **Datomic** | Cognitect → Nubank の**製品**。方言の系譜上の祖先 | 他社 | この workspace には無い |

- **`Datomic` と名乗ってよいのは `kotoba-lang/datomic-client-shim` だけ**で、そこでも
  **shape 互換であって wire 互換ではない**と同時に書く（現 README がそうなっている。
  stock の `com.datomic/client-cloud` は接続できない）。文書・ADR・README で
  「Datomic 方言」「Datomic 互換」と書かない —— **`kotobase 方言`** と書く。
- **名乗らない理由のうち決定的なのは拡張の自由。** この方言は既に Datomic に無いものを
  2 つ持つ: `ref?` の既定が **`ipld.core/link?`**（参照とは IPLD Link のこと）と、
  **`visible?` が required argument**（missing / non-callable なら読む前に refuse）。
  **Datomic を名乗った瞬間この 2 つは「非互換」になる。自分の名前なら「方言の仕様」になる。**
- ADR-2608039970（共有しているのは datom 面であって Datalog ではない）と同型の、
  名前の側の決定。**一括改名はしない** —— 縛るのはこれから書くもの。

### agent の query 入口は kotobase 方言。routine は GraphQL。Cypher / SPARQL / Gremlin は interop

LLM / agent に query を書かせる面の既定は **kotobase 方言（EDN データ形）**。定型・高頻度の
読みは **GraphQL**（`org-graphql-http` は query-only、resolver 全経路に `visible?`）。
Cypher / SPARQL / Gremlin は外部データ受け入れ・外部ツール接続に留め、**agent の第一言語に
しない**。

- **security が決定打**: ①query が EDN 値なので**文字列連結の段が無く injection クラスが
  構造的に消える** ②redaction seam（`kotobase-query/bridge.cljc` の required な `visible?`）が
  `q` 側にあり、`materialize` + `datoms` を使う surface は**redaction を各自で再実装する**
  ことになる ③SPARQL の property path（`*` `+`）と Cypher の可変長パスは LLM が無自覚に書ける
  unbounded traversal、`SERVICE` は素の SSRF 経路。kotobase 方言は
  `datalog.query/cardinality` で materialize せず件数を数え、事前予算がかけられる。
- **IPLD 相性**: `ocp`（≡ VAET）が CID リンクの逆引きそのもの。`materialize-memo` の key が
  chain CID（content address なので invalidation 経路が存在しない）。
- **素の LLM 精度は Cypher > SPARQL > Datalog 系**（学習データ量の差。動かない）。それでも
  採らないのは**穴の埋め方が非対称**だから —— 方言側は schema 注入 + few-shot + validator +
  repair loop で埋まる（EDN なので実行前に構造検証でき、外れたら**構造化エラーで返せる**。
  文字列 surface は『構文は通るが意味が違う query』を検出できない）が、Cypher の
  injection / unbounded path / redaction 再実装を後から塞ぐのは高い。
- **prompt では形を示す。** 「Datalog」とだけ言うと LLM は Prolog 風記法を出す。
  prompt に `kotobase dialect (Datomic-shaped EDN Datalog):` と**例を 1 行**書く。
  系譜に触れるのは精度のための実務であって、名乗りではない。
- **⚠ これは deploy の決定ではない。** ADR-2608039975 のとおり 6 surface はどれも live で
  なく、live なのは `kotobase-server` の手書き SPARQL subset（Datalog に翻訳する形＝
  ADR-2608039970 が「やめる」と決めた形）。**2 実装問題を再燃させない。**
  **LLM 精度の実測もまだ無い** —— 次の一手は 20〜30 問の query セットで
  kotobase 方言 / GraphQL / Cypher の pass 率を測ること。


## kotobase の物理層は block → CARv2 pack → object。1 CID = 1 object を既定にしない（repo-wide mandatory、2026-08-16、ADR-2608160100）

**block の identity（CID）と location（どこにあるか）を分ける。** 上の L0 の中身は
3 段で、混ぜると設計が黙って壊れる:

```text
L0a  block    IPLD dag-cbor / raw   identity = その block 自身の CID
L0b  pack     CARv2                 location = (pack CID, file-offset, frame-length)
L0c  object   S3 / R2 / B2 / IPFS   transport = object key + HTTP Range
```

- **新しい backend は `:block-per-object` か `:packed-blocks` のどちらかを宣言する。**
  既定値は無い（`ref-profiles` と同じ理由 —— 推測は黙って通って壊れる）。
  `:packed-blocks` は object 面の **`:range-read` を併せて宣言しないと拒否**する。
  Range の無い store で packed を名乗ると、pack 全体を GET して 1 block を取り出す
  実装が動き、**round trip は減るが転送量が爆発する**（成功に見える失敗）。
- **packing policy は「read-locality を write 側で作る」。既定は 1 commit 1 pack ではなく
  novelty window（幅 W）1 pack。** 効かせたい場所は変わらない —— hydration の逐次項の
  97% は novelty の cons chain で、幅 1・prefetch 不能（ADR-2608021000）。
  **同じ pack に入っていれば 1 回の Range GET で全部取れる**ので、chain は論理的に
  逐次のまま network の逐次性が消える。
  ⚠ **その「同じ pack」を 1 commit 1 pack は作れない**（2026-09-09 実測で反転。
  ADR-2608160100 / ayatori iteration 05・06）。chain の link は**構成上 commit を跨ぐ**
  ので、commit ごとに封じると 1 pack につき link がちょうど 1 本 = **N/P 1.00**、
  iteration 02 の crossover（cold で N/P > 3）を下回り **0.50x = 2 倍の損**になる。
  window ごとに封じると N/P = W になり、深さ 64 の合成 chain では **0.50x → 1.91x**。
  **上限は 2x** —— per-object が 1 block あたり 2（discover + fetch）払い packed が 1 なので
  `2W/(W+3) → 2`。「N/P が crossover の 1 桁上」は N/P の話で速度の話ではない。
  **代償も measured**: window は**閉じてから**しか封じられないので、最新 W-1 commit は
  pack を持たず、live head の読みはそこを per-object で歩く（W=8 で 1.45x → 1.26–1.33x）。

  ⚠ **ただし、その chain は本番では 64 本ではなく 4 本である**（2026-09-09 実測、
  kotobase-peer `novelty_chain_depth_test`）。`novelty-segment-size` は **16** なので
  **depth = ceil(unfolded-tx / 16)**、fold 閾値 64 なら **4**。ADR-2608021000 の
  「depth = unfolded-tx」は**この repo が既に離れた形**（segment 化前）の記述で、
  segment 化はまさにその実測に対する修正として入った。**この深さの違いは 16 倍あるので、
  比ではなく round trip の実数で判断する** —— packed 側は window ごとに固定 3
  （open 2 + catalog 1）を払い、これは 64 本では薄まり 4 本では薄まらない:

  | unfolded | link | per-object | commit 単位 pack | window pack |
  |---|---|---|---|---|
  | 16 | 1 | 2 | 4 | 4（**2 trip 損**） |
  | 64（fold 閾値） | 4 | 8 | 16 | 7（**1 trip 得**） |
  | 1024（fold 遅延） | 64 | 128 | 256 | 67（61 trip 得） |

  したがって **novelty window sealer は作らない**（ayatori iteration 07）。本番深さでの
  取り分は 1 round trip で、isolate を跨いだ buffer と「window が閉じるまで封じられない」
  代償に見合わない。**効く lever は grouping ではなく fold 閾値**であり、これは
  ADR-2608160100 が compaction を書かないと決めた論拠（fold は分母を割るのではなく
  分子を消す）と同じ。**write 側の 9x（144 puts → 16）は無条件で、window を要さない。**
  cutover の取引は比ではなく整数 2 つ: **PUT が 9 分の 1 になり、fold 閾値での cold
  chain read が +8 round trip**。再現は ayatori の `bench/novelty_window.cljs`
  （section G は深さ 64、**section K が本番深さの絶対値**）。
- **成功の指標は round trip 数**。bytes でも wall-clock でもない（この workstation は
  load 100 超で並行 agent が走る。count を測る）。
- **pack は封じたら不変。in-place で追記しない** —— offset が動き、catalog と
  embedded index の両方を静かに嘘にする。compaction は新しい pack を書いて
  catalog を差し替える。
- **pack catalog（CID → どの pack）は datom 面に置く。** 別の store に置くと
  pack と commit と tenant を跨ぐ query が書けなくなる（合成されていない分割は
  孤島になる、の実例。上記 ADR-2809040800）。
  catalog は **projection** であって premise ではない —— 消しても pack を走査して
  再構築できる形にする（D1 規則と同じ削除・再構築テスト）。
- **columnar は pack に入れない。** Parquet / Arrow は large object のまま
  （`:presigned-transfer` + footer の range 読み）。pack は小 block 領域のもの。
- **圧縮の seam は動かない**: `bytes → codec frame → CID → pack → object`。
  pack を丸ごと圧縮しない（中身は ciphertext、実測 ratio 1.003 で*増える*）。
- **CARv2 codec の正本は `kotoba-lang/io-ipld-car`**（`ipld.car` / `ipld.car.v2` /
  `ipld.car.index`）。自分で CAR を書かない。index cost は実測 **40 byte/block**
  （+ pack あたり固定 81 byte）なので、**block を小さくするほど相対コストが上がる**。
- 既存の `:block-per-object` deployment は**そのまま正しい**。一斉移行の計画は
  持たない —— 書き換えるなら round trip の実測が先。

### 5 つの canonical IR を共有する（ADR-2608160200）

**State / Transaction / Capability / CausalLink / Effect**、および 6 つ目の
**Execution**（`{program, input, state, runtime, policy, effects} → CID`）。
5 つとも IPLD 値なので、**同じ物理層に載る —— artifact 用の第二の store を作らない**
（amu の `:kotoba.output-set/v1`、kototama の receipt、kotobase の state は同じ
object 面の同じ pack に入る）。

- **Execution CID を memo key にしてよいのは、effect set が空か、effect log が
  完全に記録されていて replay できるときだけ。** それ以外の CID は receipt であって
  cache key ではない（外界が変わったことを見ない cache ができる）。
- **capability の core IR は `kotoba-lang/kotoba-lang` の `lang/capability-semantics.edn`**
  （`:cap/kind` `:cap/resource` `:cap/holder`）。**UCAN / CACAO / OCapN は adapter**
  であって core semantics にしない。**VC（claim）と capability（authority）を混ぜない。**
- **causality は principal ごとの署名付き DAG**（複数親 + logical clock）。単一 chain に
  畳まない。**合意が要る経路だけ inga に繋ぐ**（それ以外に consensus を置かない）。
- **綾（`kotoba-wasm` / `kotoba-native` / `kotoba-script` / `kotoba-component`）は
  権限を持たない** —— backend ごとに違うのは lowering だけで、5 つの IR の形は同一。
  「その backend でまだ動かない」ことは、別の IR を持つ理由にならない。
- この 2 つの ADR を根拠に **Pregel / Substrait repo を起こさない**（query / compute
  backend は別の、証拠付きの決定）。**改名も再開しない**（ADR-2608139980 のまま）。


## agent loop の正本は Git + EDN + DataLad、Datomic/kotobase は query projection（repo-wide mandatory、2026-08-03、ADR-2608039700）

agent loop は database transaction loop ではなく、`checkout → observe → edit/generate →
diff → verify/query → commit → review/merge → handoff/restart` という artifact loop である。
したがって **agent-facing な durable source of truth は Git + canonical EDN** とし、Git が
直接持つべきでない large immutable object だけを **DataLad/git-annex** に分離する。

- **小さい semantic EDN、schema、query、policy、manifest は通常の Git blob** に置く。
  EDN だからという理由だけで annex 化しない。diff/review と、content 未取得 agent の
  inspection を失うためである。large EDN shard、raw corpus、weights、Wasm、画像、音声、
  動画、生成 artifact は annex に置く。
- **DataScript / Datomic / D1 index / kotobase / arrangement は query・serving projection**
  として使う。pin された Git commit + annex objects + schema + loader から logical datom set を
  再構築できなければならない。DB への直接書込みだけで source plane に戻らない mutation は
  禁止。live input も先に replay 可能な EDN event/shard または content-addressed receipt にする。
- **共有 Datomic/kotobase は中央集権的でもよいが、安定した read/query service に限定する。**
  消失時に query が遅くなるのはよい。正本、custody、recovery、正しさが失われるなら
  projection ではなく premise なので不可（直前の D1 規則と同じ削除・再構築テスト）。
- **一緒に join するものは同じ logical dataset / kotobase ref に materialize する。** ref を
  分ける場合は、失われる横断 query を名指しする。projection は `:source/dataset`、source
  Git commit、dataset version、schema/loader version、annex key/CID を追跡する。
- **文書・設定は現在値を更新し、履歴を Git に任せる。測定・イベント列は append-only
  shard とする。** 両方を一律 append-only または一律上書きにしない。
- **公開 repo + 暗号化 annex は本文の秘匿であって metadata の秘匿ではない。** path、size、
  更新頻度、author、dataset topology は見える。git-annex の `encryption=shared` は GPG 系で
  age ではない。age を使う場合は ciphertext を annex 管理し、annex key/CID は plaintext
  identity でなく ciphertext identity とする。plaintext↔ciphertext 対応表が必要ならそれも
  暗号化し、decrypt 先は Git 管理外、age identity は明示 capability とする。
- **Git/DataLad/CID は availability guarantee ではない。** 重要 dataset は `numcopies`、
  独立した複数 remote、定期 `fsck` / custody verification、recovery drill を持つ。
- agent/materialization receipt は input commit、annex manifest/key/CID、schema/loader/query/
  compiler contract、effective policy/capability、output commit/artifact CID、検証結果を結ぶ。
- protected Git ref / merge queue / single writer を当面の安定した publication として使ってよい。
  ただし分散合意とは呼ばない。分散 agreement が必要な経路は inga ref へ接続する。
- projection を追加・変更したら
  `kbb --backend sci --classpath ".:scripts/nbb_compat" manifest/projection-verify.cljk verify <projection.edn>`
  をgateにする。contractはsource commit、input Git hash / annex key / CID、schema/loader hash、
  allowlist済みloader ID（contract由来のargvは禁止）、logical datom hash、任意の
  physical hash、entity countを固定する。loaderはdataset固有schemaとstable identity属性を
  実際に検証し、出力先は`.projection-cache/<projection-id>.edn`だけを宣言できる。custody確認は別途
  `scripts/annex-custody-verify.cljk` が担い、identity検証とavailability検証を混ぜない。

