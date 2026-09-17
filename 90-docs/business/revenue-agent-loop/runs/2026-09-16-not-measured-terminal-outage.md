# Run 2026-09-16 — terminal outage run (not-measured run)

## 実行環境の異常
- terminal ツールが完全に空出力 (`echo hello` すら output="" / exit 0)。
  → nbb funnel-pulse も curl も実行不能。**CLEAN ではなく not-measured**。
- search_files も rg 不在で機能せず、runs/ や score ファイルの一覧・既読ができない。
- canvas-ledger.edn は未着手 (単一 writer ルールどおり触らない)。

## 信号 (web_extract 経由の代理測定 — status code / レイテンシは取得できず)
- https://kotobase.net/ : 本文取得OK (LP 応答あり)。
- https://kotobase.net/signup : 本文取得OK (Free/Starter $25/Professional $132 の価格記載あり)。
- https://kotobase.net/ipld/v1 : fetcher が CRAWL_HTTP_400 → route 存在と整合 (期待どおり 400 系)。
- https://kotobase.net/ipld/ (bare) : 今回も 400 系応答。2026-09-03 実測の 404 と一致せず —
  検証手段 (curl -w) がないため断定しない。要 curl 再測。
- /api/funnel : fetcher が https://kotoba.cloud/api/funnel に到達。内容は
  visitors/signups/signup_completed すべて 0、persistence: isolate-memory (durable: false,
  seeded: false) — カウンタは非永続でリセット済み。**BMC の 2,080,186 req/7d 等と突合不能。
  kotobase.net → kotoba.cloud へのリダイレクト/移行の可能性あり (docs/graph に
  "former kotobase.net ... under kotoba.cloud paths" の記述を確認)。**
  → これは funnel の数字ではなくドメイン移行の兆候として記録する。

## 安定化チェック
- 人気 3 エンドポイント (/ /signup /ipld/v1) は代理手段で生存確認。
- 原因推定: サイト自体は生きている。ただし正確な status code/レイテンシは terminal 障害のため未計測。

## SCORE → SELECT
- 100 点 score ファイルを読めず (rg 不在 + ファイル名特定不能) → **score による選択は未実施 (not measured)**。
- WIP=1 は維持。本 run では新規アクションを選択しない。

## 次 (owner への提案 / draft のみ・送信なし)
1. terminal を修復 (Hermes の shell backend 確認、`hermes` docs 参照)。修復後まず:
   `curl -s https://kotobase.net/api/funnel` と `nbb kotobase_lead_loop.cljs funnel-pulse` を再測。
2. kotoba.cloud への移行確認: /api/funnel の redirect 元と BMC canvas の数字の不整合を
   人手で確認する (canvas 更新は canvas-ledger の単一 writer routine 側)。
3. acquisition (visitors 246 → signups 0) への実験 draft は次 run で score ファイルが
   読めた時点で選択する。

## 実験 draft (宛先のみ・送信しない)
- 宛先: owner (junkawasaki)。件名: "kotobase.net → kotoba.cloud 移行と funnel 計測の不整合確認のお願い"。
- 本文草案: /api/funnel が kotoba.cloud 側で zeros/isolate-memory を返すこと、
  kotobase.net ドキュメントに統合記述があること、BMC の 7d 数字と突合できないことの 3 点を確認依頼。

## 次の検証
- terminal 修復後の funnel-pulse 再測と /ipld/ bare の status code 確認。
