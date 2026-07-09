# ADR-0011: 個人データの公開ネットワーク pin 廃止 — B2 + DataLad のみに永続化 (耐量子脅威モデル)

- Status: Accepted (2026-06-11)
- 変更対象: ADR-0003 (storage 構成の一部を改訂), ADR-0007 (B2 永続化は維持)

## 課題

ADR-0003 では「gpg-hybrid 暗号文のみを IPFS に pin(平文は出さない)」としていた。
しかし IPFS は**公開コンテンツアドレス網**であり、CID を知る誰もが暗号文を取得・保存できる。

脅威: **Harvest Now, Decrypt Later (HNDL)。**
現行の annex 暗号化 (gpg hybrid) の鍵ラップは古典公開鍵暗号であり、将来の
暗号学的に意味のある量子計算機 (CRQC) で Shor により破られうる。
本 warehouse のデータ寿命は generation-τ(のどか・孫世代に及ぶ家族・法務・医療・資産記録)であり、
**データの要秘匿期間 ≫ CRQC 出現可能性の地平**。よって「暗号文だから公開網に置いてよい」は成立しない。

## 決定

1. **永続化先は 2 系統のみ**: ① private Backblaze B2 (`com-junkawasaki-annex`, gpg-hybrid + chunk 50MiB, アクセス制御あり) ② ローカル DataLad/git-annex クローン。
2. **IPFS special remote を全廃**: 全キー (29,371) を `git annex drop --from=ipfs --all` で unpin → `ipfs repo gc` でブロック削除 → `git annex dead ipfs` + remote 設定削除。
3. **新規データは公開ネットワーク(IPFS/BitTorrent/web)へ複製しない**。暗号化の有無を問わない。
4. **PQC 移行を obligation 化** (`ob/pqc-reencrypt`): ML-KEM(-768)+X25519 ハイブリッドが
   ツールチェーン (GnuPG / age plugin / rage) で実用化され次第、annex 暗号鍵を再ラップし
   B2 バケットを再暗号化ローテーションする。

## 露出評価 (正直な記録)

- remote pinning サービス (Pinata 等) は**未設定**だった。pin はローカル kubo ノードのみ (18,473 pins)。
- 公開到達性はローカルデーモン稼働中に限られ、CID を知る相手が能動的に取得した場合のみ流出。
- 結論: **収集された確率は低いが非ゼロ**。当該暗号文は「将来解読されうる前提」で扱い、
  PQC ローテーション時に優先的に鍵更新する。CID リストは git-annex branch の履歴に残るため監査可能。

## 帰結

- 良: HNDL 攻撃面の除去。運用が B2 一本に単純化 (コスト・監視も一元化)。
- 悪: 地理冗長が B2 + ローカルの 2 copy に減る。**対策候補**(未決定): 暗号化済み外付け SSD への
  第3クローン、または B2 の別リージョン複製。numcopies=2 の維持を fsck で監視。
- ADR-0003 の「IPFS に平文なしで pin」記述は本 ADR により廃止。DataLad/git-annex/gpg 構成は不変。
