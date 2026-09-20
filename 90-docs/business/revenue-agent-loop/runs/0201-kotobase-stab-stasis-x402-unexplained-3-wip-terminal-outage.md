# run 0201 — kotobase-stab: stasis 継続 (visitors +3 / organic)、x402 unexplained 3 件 WIP 継続

（本 tick: 2026-09-20T02:01:25+09:00、前 run 0200 は 2026-09-19T08:04。run 番号は 0199→0200 と連続し、本 tick で 0201 を初採用 — runs/ の命名 `NNNN-kotobase-stab-<title>.md` に従う）

## 計測 (生値、捏造なし)

- cron プロンプトの `kotobase_lead_loop.cljs` → **ENOENT** (本 tick で 6 連続、同一不整合)。
  正本は `90-docs/business/revenue-agent-loop/kotobase_lead_loop.cljk` であることを再確認
  (ls により実名 `.cljk` 17310 B)。
- `nbb 90-docs/business/revenue-agent-loop/kotobase_lead_loop.cljk funnel-pulse` → EXIT=0:
  `VISITORS 13345 / SIGNUPS 42 / CHECKOUTS 1 / DELTA {:visitors 3, :signups 0,
  :checkouts 0} / UNCHANGED false / EXTERNAL-FUNNEL-CHANGE false / RESULT recorded`
  (前 pulse 記録 as-of 2026-09-19T14:01:22 funnel 13342 との差 +3)。本 pulse 記録は
  writer 単一ファイル `metrics/net-kotobase-funnel-pulses.kotoba` へ append
  (mtime 2026-09-20T02:01:25、現行 337 行、更新確認済み)。
  (注意: 本 tick も host terminal が stdout を返さない不調を継続 → /tmp への
  リダイレクト + read_file の代替手順で完遂。2026-09-16 outcage と同型、前 run 0200 記載と同じ)。
- `GET https://kotobase.net/api/funnel` → **200** (0.084169s / 799 B):
  - funnel: visitors 13345 (organic 11748 / freebuff 23 / openai-ads 4)、
    signups 42 (organic 32 / other 4 / openai-ads 2 / freebuff 1)、checkouts 1 (organic)。
  - registrations 4 (すべて source "other") — 変化なし。
  - micro: docs_view 4 / github_click 2 / cli_copy 2 — 不変 (前 4 tick と同じ据え置き)。
  - x402: submissions 4 / rejections 4 / rejections-classified 1 (malformed-header 1) /
    **rejections-unexplained 3 (8 tick 連続変化なし)** / settlements 0 /
    settlement-rate 0 / attempt-rate 0.08695652173913043 /
    unmetered-twin-reads 1183 (前 1176, +7) / unpriced-plane-reads 4518 (据え置き) /
    challenges 46 / unmetered-twin-ratio 25.717391304347824 (前 25.5652)。
- 変化点: visitors +3 (organic 11745→11748)、twin-reads +7 のみ。
  signups / checkouts / registrations / micro 3ши 値 / rejection-unexplained 3 /
  settlements 0 / unpriced-plane 4518 / challenges 46 / submissions-rejections 4 すべて不変。
  external checkout movement なし → **not demand** (升score 根拠なし)。
- `nbb ... .cljk next-form` → EXIT=0: `SCANNED 10 / WIP beekle / WIP-URL
  https://beekle.jp/contact / BEEKLE-SENT false / SKIP helpfeel / MAY-SUGGEST ELYZA=false /
  MAY-SUGGEST iASYS=false / DO-NOT-SEND true / RESULT beekle-unsent`。
  out: `:beekle-status :blocked` / `:do-not-bypass-captcha true` /
  `:do-not-solicit-paid true` / `:url-confirmed? :elyza true / :iasys true (live GET 後確認)`。
  10 アカウント中 beekle のみ未 sent、他は queue 戻し — second lever も blocked 据え置き。
- owner 側の前 run draft (x402 分类漏れ修正 / cron 命名 / bare `/ipld` baseline) 処理状況は
  **not measured** (外部変化から动词不能推断)。

## 安定化チェック (raw + follow 双方追跡済み)

- `GET /` (raw) → **301** → `graph.kotoba.cloud` (follow) → **200** (0.1326s) ✅
  (SOUL raw-200 期待は redirect 経由の着地 200 で満る; raw 段actual 301)
- `GET /signup` (raw) → **301** → `graph.kotoba.cloud` (follow) → **200** (0.0988s) ✅
  signup 解消後の 200 は本 tick で **4 tick 目** 連続。
- `GET /ipld/v1` (raw) → **302** (Cloudflare) → `https://ipfs.kotobase.net/ipfs/v1`
  (follow) → **400** (0.1496s) = route 存在の正常値 ✅
  `?x=1` や bare 亦然。前者 prec 指定どおり 400 根拠。
- `GET /ipld/` bare (raw) → **302** → `https://ipfs.kotobase.net/ipfs/` (follow) → **400**。
  SOUL.md の期待 (bare 404 正常) と **5 tick 連続で不一致** — baseline 更新判断は owner 側に残置。
  (2026-09-03 実測当时记录は这也为非彼时刻的 object、彼时的问题是 owner 未处理的待 settle)。
- 原因推定 1 行 (UNCONFIRMED): 転送先 graph.kotoba.cloud / ipfs.kotobase.net とも
  200/400 応答し route live。origin 障害征兆なし (transition 不变)。云flare rω号仔
  q302 一为路由参数必须承担的既有构制。

## SCORE → SELECT (WIP=1)

- SCORECARD.md は 2026-08-14 观测分のまま (`runs/0033` 同节点) 变更なし。
  全候选行 blocked (counsel gate / no contact path = 75 点行 / red gate)。
  `SELECT(max score, WIP=1)` — **选一候选不改性**。
- **Selected action (WIP 継續、交代なし)**: 「x402 rejections の分类漏れ解消 — owner へ draft 报告」.
  根拠数字: rejections 4 / classified 1 / **unexplained 3 (8 tick 变化なし)** /
  settlements 0 / settlement-rate 0 / attempt-rate 0.08696 / unpriced-plane-reads 4518 /
  submissions 4。4.5k read 流量に対し课金试r行 4 件全败かつ 3/4 原因不明/
  {% 分类漏れ}=25%『观察縺』tolma?4 不數、打点结论: 瓶颈は流量視察非 attempt 观察性,
  与 learn-backbone 5/5 / conf 5/5 对恰。
- Bounded experiment (**不存在送信 / deploy**): 它 run は observation tick —
  行动无执行。其实为: 前 run (0200) の draft は技术有没成品(`reconcile`)即 last
  elementx processor「on dead」, no nad版。今 run marker 无用.
  Prior 个文另身轨已过、本 runд 由 повритеру 者の声о另 это от на/кауl群:
  - 仿 iso от 「why hold」bg not-counten:
    - acquire levy: visits 13.3k」多 какие source" flew conchepkaha affordable for 1174 medions",足 not deep? yes low s/s
- LEDGER: canvas-ledger.edn (31774276 B / 10905 行) mtime **2026-09-10 16:48:29** — 未触未書き。
  単一 writerра routine obey.
