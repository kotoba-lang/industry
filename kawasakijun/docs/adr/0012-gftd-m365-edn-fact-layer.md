# ADR-0012: gftdcojp M365 アーカイブの EDN 事実層 + Datomic ビュー

- **Status**: Accepted / Implemented(全レイヤ稼働・B2 push 済み)
- **Date**: 2026-06-12
- **Deciders**: 河崎純真 (j.kawasaki@gftd.co.jp)
- **Context tags**: gftdcojp, m365, edn, datomic-local, datalog, cid, provenance, crm, curation-loop
- **Related**: ADR-0010(life graph — EDN 事実層パターンの原型)、ADR-0011(M365 全量アーカイブ = 本 ADR の L0)
- **Implementation**: `orgs/gftdcojp/m365-archive/{facts,prov,curation,datomic}/`, `bin/{extract-facts,extract-attachments,write-prov}.py`

## Context

ADR-0011 で M365 全量(メール 103k / OneDrive 131GiB)の暗号化アーカイブは成立したが、
「契約・人員・CRM・手続き・対応・プロジェクト状況を**データとして引ける**」状態では
なかった。ADR-0010 の 4 層パターン(L0 raw / L1 facts / L2 prov / L3 views)を
org スコープに適用する。

## Decision

1. **CID = git-annex key**(`MD5E-s<size>--<md5>`)。sha256 の再計算をせず、
   ローカル annex と B2 暗号化実体へ直接解決できる内容アドレスとして採用。
2. **L1 = facts/ の JSONL/EDN が SSoT**。`bin/extract-facts.py`(ローカル読みのみ・冪等)
   が毎回全再生成。Datomic Local は `:storage-dir :mem` で毎回再構築(平文 DB を
   ディスクに残さない、ADR-0010 と同じ custody)。
3. **内容同一性で正規化**: 同一 CID の複数パス → 多値 `:blob/path`、同一 Message-ID の
   複数フォルダ → 多値 `:gftd.m365/folder`。添付は md5 で drive CID と突合し
   mail↔drive の依存エッジになる。
4. **エンティティ**: blob / message / thread(needs-reply 判定)/ attachment / event /
   person(`:person/email` 同一性, personal warehouse と共有)/ org(`:org/domain`)/
   crm(組織 + フリーメールは人単位)/ doc(契約・請求・手続き・人事・財務の分類 +
   日付・月度・取引先・プロジェクト)/ project(status 推定)。
5. **キュレーション・ループ**: 機械抽出の残課題は `facts/curation-queue.edn` に
   毎回再生成(未解決取引先・無名組織)。人間は `curation/party-aliases.edn`(plain git)
   にルールを足すだけで次回抽出から反映。
6. **L2 = PROV-O サイドカー**(`prov/*.prov.jsonld`): 生成スクリプト・ソース・
   dataset commit を W3C PROV-O で記録。

## Outcome(2026-06-12)

blobs 112,915 / messages 102,158 / threads 92,635(needs-reply 1,686)/
attachments 25,577(mail↔drive join 80)/ events 6,491 / persons 5,470 /
orgs 2,673(名前学習 85)/ docs 6,866(取引先解決 240)/ crm 2,652 org + 148 person /
projects 51。Datalog ビュー 11 本(要対応・契約タイムライン・プロジェクト別文書・
CRM・ファイル来歴等)。

## Lessons

- `str.splitlines()` は U+2028/U+2029 で割れる — Graph のイベント本文を含む JSONL は
  `split("\n")` で読むこと。
- annex.thin + addunlocked の作業ツリーで working file を上書きすると旧 key の
  ローカルオブジェクトが壊れ得る(B2 push 済みなら実害なし、fsck で検出可)。
  再生成系ファイルは「push 完了 → 再生成」の順を守る。
- 通知中継ドメイン(8card.net 等)は第三者の表示名を運ぶため組織名学習から除外必須。
- 実行中 bash スクリプトの編集禁止(ADR-0011 の教訓を再確認)。

## Consequences

- (+) 「この契約の相手方 → その組織とのメール/会議履歴 → 元ファイルの CID → B2 実体」
  が単一の Datalog クエリ空間で辿れる。
- (+) facts はすべて annex+暗号化で GitHub にはポインタのみ。再現は
  `clone → 鍵 import → datalad get facts → clojure -M:run`。
- (−) 分類・抽出はファイル名/ヘッダのルールベース。本文レベル(契約条件・金額・
  期日・対応義務)の構造化は LLM 抽出の別フェーズとして未着手。
- (−) projects の status/depends-on、org 名の検証、取引先解決の残り 311 件は
  curation ループでの手動作業。
