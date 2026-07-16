# ADR-2607101000: kotoba Rust削除(2026-07-01)の孤児機能 — CLJC移行ロードマップへの追加項目

**Status**: proposed
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki

## Context

ADR-2607022600(kotoba database crates CLJC移行ロードマップ)は、2026-07-01の
Rustワークスペース一括削除(PR #259, commit `604896171b`, crates/* 約38万行)の
うち **DB本体**(CID/Prolly Tree/5-index Arrangement/CommitDag/BlockStore/
SPARQL/DHT/Pregel)だけを棚卸しし、Wave 0〜5のロードマップを敷いた。

本セッション(2026-07-09〜10、genko/kotobase/murakumo統合作業の一環でEN
相互信用通貨の調査を行った際)に、**同じ削除コミットで消えた他の crate 群**を
網羅的に監査した結果、ADR-2607022600 の棚卸し表に**一度も登場していない**
孤児機能が複数見つかった。ADR-2607022600 自身が「本 ADR は DB 本体のみの
部分的な回答である」と明記しているとおり、これは意図的な除外ではなく
**単に対象外だった**領域であり、本 ADR で初めて棚卸しする。

なお ENGI/EN(相互信用通貨、`kotoba-dht::engi_chain.rs` + `kotoba-server::
engi.rs`)も同じ削除コミットの孤児だが、固有の設計判断(kotobase-native再設計
vs kotoba-dht基盤フル復活)を要するため、本 ADR には一覧のみ記載し、詳細設計は
姉妹 ADR(2607101100 / 2607101200)に譲る。

## Decision

### 1. 追加棚卸し(ADR-2607022600 に未記載だった孤児)

| 領域 | 旧 crate(行数) | 用途 | CLJC側の現状 | 分類 |
|---|---|---|---|---|
| **EN/ENGI 相互信用通貨** | `kotoba-dht::engi_chain.rs`(1469)+ `kotoba-server::engi.rs`(1488) | Holochain/HoloFuel型 mutual-credit | 無し | **姉妹ADR参照**(2607101100/2607101200) |
| **kotoba-vault** | 13ファイル、6987行 | LiveBus/Vault/Shelf/Topic/SyncWindow、暗号化blobチャンキング、鍵カストディ起動、選択的sync window | 無し。ADR-2607022600 の棚卸し表にも一切登場しない、真の記載漏れ | 要判断・Wave割当(下記) |
| **kotoba-server 補助面**(email_xrpc/mcp/social_economy/attestation/access_receipt) | 5ファイル、約22K行 | E2E暗号化Gmail取込+検索、MCPツールサーバ、ENGI隣接の社会経済台帳、ステーキング/監査レシート経済 | 無し(`com-gmail` は汎用APIクライアントのみで取込パイプライン無し。MCPサーバ・attestation・access-receipt の後継も無し) | 要判断・Wave割当(下記) |
| **kotoba-datomic::distributed** | 1ファイル、9738行 | ノード間分散Datomicトランザクション合意 | 無し(`kotobase-peer` は単一ノード) | 要判断(下記) |
| **kotoba-word** | 11ファイル、1746行 | エージェント呼び出し可能な能力レジストリ(closure/process/http/wasm executor) | 無し | 要判断(下記) |
| **kotoba-lattice** | 18ファイル、3979行 | mesh制御プレーン(gossipsub + EDNマニフェスト + leaderless調停/オークション、ワークロード配置) | 無し(`kekkai` はネットワークオーバーレイのみで配置は別関心事) | 要判断(下記) |

### 2. 既存 ADR-2607022600 で「対象外・別ADR要」とされていた項目の再確認

| 領域 | ADR-2607022600 の判定 | 本ADRでの再確認 |
|---|---|---|
| kotoba-custody(Shamir t-of-N鍵復旧、931行) | 対象外、設計はADR-2606271600 §3に温存 | 需要が具体化するまで保留を維持。EN/ENGIの鍵カストディが必要になった時点で再検討 |
| kotoba-llm(3489行) | 対象外 | `webgpu`/`inference`/`torch` 系リポジトリが受け皿候補として既に生育中(要オーナー確認、本ADR対象外のまま) |
| kotoba-ingest(5964行) | 対象外 | 変更なし。対象外を維持 |
| kotoba-evm(1734行) | (未記載) | CLAUDE.md「tx署名/実行はetzhayyim専用境界」の設計と一致する**意図的除外**と判断。CLJC移行対象に含めない |
| kotoba-didcomm(593行)・kotoba-media(253行) | (未記載) | 低価値ニッチ・後継無し。優先度最低、需要が出るまで着手しない |

### 3. Wave 割当(ADR-2607022600 の既存 Wave 0〜5 を拡張)

| Wave | 内容 | 前提 | 優先度 |
|---|---|---|---|
| **Wave 6 — kotoba-vault(暗号化blob+鍵カストディ)** | `orgs/kotoba-lang/vault`(新規)として cljc 切り出し。LiveBus/Shelf/Topic の同期窓口はEN/ENGIの秘密鍵管理・genko doc の暗号化保存(将来のPDS/B2永続化 follow-up)双方から必要になる可能性が高い | Wave 1(BlockStore) | 中(ENGI kotobase-native設計 v1 が進めば秘密鍵管理の実需が先に立つ) |
| **Wave 7 — kotoba-word(能力レジストリ)** | エージェントが呼べるツール/能力を宣言的に登録する薄いレジストリ。CLAUDE.md Actors パターンの `:db-api` 注入境界と重複しないか要精査 — 重複するなら新規構築せず既存パターンへ統合 | Wave 0 | 低(現行の genko/kotobase 統合作業に直接必要ない) |
| **Wave 8 — kotoba-lattice(mesh配置オーケストレーション)** | ワークロード配置・オークション。murakumo の fleet 運用(Tailscale直接管理)と重複する可能性が高く、**新規構築より murakumo 側の既存運用への統合を優先検討**すべき | Wave 5 相当(投機的) | 低・保留(需要が具体化してから) |
| **Wave 9 — kotoba-server 補助面の個別評価** | email_xrpc(Gmail取込)・mcp(MCPサーバ)・social_economy/attestation/access_receipt(ENGI隣接の別経済圏)は**用途が大きく異なるため一括りにしない**。各々が実需要を持つ時点で個別ADRを起票する。social_economy/attestation はENGI設計(2607101100)と概念が近接するため、ENGI着手時に統合可否を再検討 | 個別 | 低・保留(各機能ごとに需要確認後) |
| **Wave 10 — kotoba-datomic::distributed(分散合意)** | ENGI/EN のクロスノード決済一貫性が必要になった場合の前提条件になりうる。Wave 5(kotoba-dht基盤)と重複する検討事項が多く、**独立着手せず ENGI v2(2607101200, kotoba-dht基盤フル復活)の一部として再評価**する | Wave 5 | 低・保留 |

## Consequences

- (+) ADR-2607022600 が「DB本体のみの部分的回答」と自認していたギャップを埋め、
  2026-07-01 削除の全体像(kotoba-vault・kotoba-server補助面・distributed・
  kotoba-word・kotoba-lattice)を初めて棚卸しした。
- (+) いずれも**即時着手を推奨しない**(Wave番号を振ったのは優先順位付けの
  ためであり、着手宣言ではない)。ADR-2607022600 と同じ精神(先回りして
  作らない、需要が具体化してから)を踏襲する。
- (+) EN/ENGI(社会経済台帳・attestation・distributed合意と概念的に隣接する
  領域が複数存在する)は、本ADRの一覧に位置づけつつ、詳細設計は姉妹ADR
  (2607101100 kotobase-native v1 / 2607101200 kotoba-dht基盤フル復活 v2)に
  委ねる。
- (−) 本ADRは計画のみで、コードは一切変更しない。各Wave着手時に個別ADR
  (実装内容・テスト結果を記録する形式)を積むことを前提とする。
- (−) kotoba-word と CLAUDE.md Actors パターンの重複可能性、kotoba-lattice と
  murakumo fleet運用の重複可能性は、着手前に必ず精査すること(重複実装を
  避けるための明示的な宿題として残す)。

## References

- ADR-2607022600(kotoba-database-crates-cljc-migration-roadmap)— 本ADRが
  拡張する親ロードマップ
- ADR-2607101100(engi-mutual-credit-kotobase-native-design)— ENGI/EN v1
- ADR-2607101200(engi-mutual-credit-dht-substrate-revival)— ENGI/EN v2
- `orgs/kotoba-lang/kotoba/docs/ADR-engi-mutual-credit-on-chain.md` — 削除前の
  ENGI原設計(R0〜R9実装済み、Rust削除で失われた)
- ADR-2606271600(kotoba-stack-equivalences)— kotoba-custody の目標設計参照元
- CLAUDE.md「Actors」節 — kotoba-word との重複精査で参照する注入境界パターン
