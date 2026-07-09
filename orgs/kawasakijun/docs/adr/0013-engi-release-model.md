# ADR-0013: Engi (縁) — 関係・アカウント・サブスクの手放し評価モデル

- Status: Accepted (2026-06-11)
- 関連: ADR-0005 (証拠保全 = legal hold), ADR-0010 (life graph), ADR-0012 (dyad), goal 29_subscription_audit

## 課題

人・アカウント・サブスク・ドメイン・法人など「縁」が累積し、月額コスト (¥319k → 目標 ¥220k)、
注意コスト (通知・請求メール)、攻撃面 (休眠アカウント)、認知負荷を生んでいる。
「何を手放すか」を場当たりでなく、評価可能・監査可能な決定として扱いたい。

## 決定: engi エンティティ

縁起 (依存して生起したもの) は、依存が解けたとき記録とともに手放せる。
各縁に **6値の decision** を与え、Datalog で「切離キュー」「保全リスト」「削減見込み」を導出する。

| decision | 意味 |
|---|---|
| `:keep` | 維持 (guardrail/健康/稼働中事業に寄与) |
| `:reduce` | 縮小 (プラン降格・通知ミュート・利用集約) |
| `:transfer` | **個人→法人への付け替え** (事業インフラが個人カードに乗っている縁) |
| `:archive` | 記録を warehouse に保全して閉鎖 |
| `:sever` | 切離 (export-first 完了後にのみ実行可) |
| `:hold` | **法的保全 — 係争終結まで操作禁止** (ADR-0005) |

### 判定規則 (辞書式)

1. **legal-hold が立っていれば無条件 `:hold`** — 係争 (LingLing/水谷/Rokes/鹿児島大) の証拠・証人・名義に関わる縁。
   「切りたい」が判定を上書きすることはない。
2. guardrail (00_nodoka_wellbeing)・健康 (11_health_steady) に寄与する縁は `:keep` (コストでは切らない)。
3. 残りはコスト対寄与: (月額 + 注意コスト) vs goal 寄与。事業インフラなのに個人決済 → `:transfer`。
4. **`:sever` は `export-first` 完了が前提条件** — 縁を切る前に記録を annex→B2 へ。消去ではなく解放。

### 人間関係への適用上の制約

- 人の縁の `:sever` は原則使わない。使うのは `:cold-inbound` (面識なき営業) のブロックのみ。
- 証人 (`:witness`) は本人の心情と無関係に `:hold` — 将来の供述価値が消えるため。
- 家族・co-parent は engi の評価対象外 (ADR-0012 §3 と同じガードレール)。

## 計測との接続

- 注意コスト: 将来は sender 別メール件数/月 (email datoms) から自動算出。現在は curated 推定。
- 削減見込み: `:engi/monthly-cost-jpy` の :sever/:archive/:reduce/:transfer 合計 → goal 29 のKPI。
- 完了の定義: `:engi/status :done` + (export-first の場合) warehouse 内の annex パス記載。

## 実行フロー: メール解約 (2026-06-11 本人指示で追加)

各サービスの管理画面に入るのは手間なので、**解約・縮小・退会は原則メールで送る**。
チャネル既定 = `:engi/cancel-email` 宛のメール。Web 画面でしか受け付けないと判明したものだけ
`:cancel-channel :web-only` に倒す (その場合も先にメールで依頼し、断られた記録を残す —
特定商取引法・約款上、メール解約を拒めないサービスは多い)。

```
① engi entry が :approved になる (本人判断)
② export-first = true なら warehouse 保全を先に完了
③ Claude が解約メールの Gmail 下書きを生成 (テンプレ: 契約特定情報 +
   解約意思 + 希望日 + 確認返信の要求。証跡として BCC root@... は使わず
   jun784+engi-<id>@gmail.com を Reply-To/CC に)
④ 本人が下書きを確認して送信 (送信ボタンは常に本人)
⑤ manimani が返信を :waiting で追跡。確認返信 = 証跡として cid 化
⑥ :status :sent → 確認取得で :done。decision ledger に記録
```

下書き生成は Gmail MCP (create_draft) で即時可能。送信済み解約メールと先方の確認返信は
ingest-eml.py で cid 化し、:prov/derived-from で engi entry に紐付ける (解約紛争の保険)。

## ファイル

- `orgs/personal/facts/engi.edn` — 評価の SSoT (annex/暗号化)
- `orgs/personal/bin/datomic/queries/engi.edn` — sever-queue / legal-holds / savings ビュー
