# run 0203 — kotobase-stab tick (stasis 継続 / apex 全域 301 + signup path 切捨て不変 / x402 unexplained 3 件 11 tick 不変 / funnel delta 0)

**Status:** completed
**Started:** 2026-09-20 (JST, 本 tick。前 tick 0202 = 同日 08:01)
**Owner:** agent
**Mode:** cash-first
**Tick position:** run 0202 の次。runs/ 命名 `NNNN-kotobase-stab-<title>.md` に従い 0203。

## 計測 (生値、捏造なし)

- cron プロンプトの `kotobase_lead_loop.cljs` → **ENOENT** (本 tick で 8 連続、同一不整合)。正本は `kotobase_lead_loop.cljk` (find 実名確認)。
- `nbb 90-docs/business/revenue-agent-loop/kotobase_lead_loop.cljk funnel-pulse` → **EXIT=0**:
  `SCANNED 1 / VISITORS 13349 / SIGNUPS 42 / CHECKOUTS 1 / DELTA {:visitors 0, :signups 0, :checkouts 0} / UNCHANGED true / EXTERNAL-FUNNEL-CHANGE false / SCORE unchanged / RESULT unchanged`
  (前 tick 0202 で +4 あった visitors delta は本 tick 0 — 06h で新規 visitors なし)。
- `curl https://kotobase.net/api/funnel` → **200** (798 B):
  - funnel: visitors 13349 (organic 11752 / freebuff 23 / openai-ads 4) / signups 42 (organic 32 / other 4 / openai-ads 2 / freebuff 1) / **checkouts 1 (organic、未検証)**。
  - registrations 4 (全 source "other") — 不変。
  - micro: docs_view 4 / github_click 2 / cli_copy 2 — 10 tick 据え置き。
  - x402: submissions 4 / rejections 4 / rejections-classified 1 (malformed-header 1) / **rejections-unexplained 3 (11 tick 連続変化なし)** / settlements 0 / settlement-rate 0 / attempt-rate 0.08696 / unmetered-twin-reads 1185 (前 1184, +1) / unpriced-plane-reads 4518 (据え置き) / challenges 46 / unmetered-twin-ratio 25.76086956521739。
- 本 tick 変化点は **twin-reads +1 のみ**。visitors / signups / checkouts / registrations / micro / unexplained-3 / settlements 0 すべて不変。
- owner 側の前 run draft 処理状況は **not measured**。

## 安定化チェック (raw + follow 双方追踪)

- `GET /` (raw) → **301** → `https://graph.kotoba.cloud/` (follow) → **200** (0.586s) ✅ 着地 200。
- `GET /signup` (raw) → **301** → `https://graph.kotoba.cloud/` (**path 切捨て**) → (follow) → **200** at graph root (0.463s)。graph ホスト上の `/signup` path 実体は本 tick 未 probe (0202 で `/register` `/pricing` は raw 404 実測済み)。path 切捨ては 0196→0203 の **8 tick 継続**。
- `GET /ipld/v1` (raw) → **302** → `https://ipfs.kotobase.net/ipfs/v1` (follow) → **400** = route 存在の正常値 ✅ (0.164s)。
- `GET /ipld/` (bare) (raw) → **302** → ipfs host (follow) → **400**。SOUL.md 期待 (bare 404 正常) と **7 tick 連続不一致** — baseline 更新判断は owner 側に残置 (UNCONFIRMED)。
- 原因推定 1 行 (UNCONFIRMED): apex 全域 301 redirect rule (origin graph root 単一化) が継続し、転送先に path-holding route がないため「signup 到達 200」は root 着地の疑似正常 — origin 故障の兆候ではなく意図的/設定由来の redirect と整合。

## 本 tick の bounded experiment (実施済み)

- **raw/follow 分離 probe の再実施** (0202 手法の継続検証): 301/302 の redirect 先を `%{redirect_url}` で直接観測し、follow 200 が root 着地であることを再確認。`/signup` follow 着地 URL = `https://graph.kotoba.cloud/` (path 消失) を本 tick でも実測。新規知見なし = 状態 stasis 確定。

## SCORE → SELECT (WIP=1)

- SCORECARD 変更なし (pulse: SCORE unchanged)。全候補行 blocked 状態継続 (counsel gate / no contact path)。
- **Selected action (本 tick, WIP 継続・交代なし)**: 「x402 rejections 分類漏れ解消 — owner へ draft 報告」 (0200→0203 同一 incumbent)。
  根拠数字: rejections 4 / classified 1 / **unexplained 3 (11 tick 不変)** / settlements 0 / settlement-rate 0 / attempt-rate 0.08696 / unpriced-plane-reads 4518 / unmetered-twin-reads 1185 / submissions 4。13.3k visitors vs 4 attempts 全滅 + 3/4 reason-invisible: bottleneck は traffic ではなく観測性。
- 次点候補行 (75点 no-contact-path 行) blocked 継続。交代条件: owner が draft を処理した外部証拠が得られた時点。

## Bounded experiment 提案 (送信なし・deploy なし)

- 本 run file = draft。宛先 = junkawasaki (owner)、cron 出力経由。
- (1) **apex 全域 301 / path 切捨てを owner へ最優先報告** (0196→0203 の 8 tick 継続): `/signup` `/register` `/pricing` が live な path として存在せず、signup 入口が実質喪失。signups 42 / delta 0、visitors +4/日 程度の微増 — 入口復旧なしに acquisition bottleneck は動かない。
- (2) x402 classification draft (0200/0201/0202 と同内容、変化なし)。
- (3) cron プロンプトの `.cljs`→`.cljk` 記名修正 (8 tick ENOENT)。
- (4) host terminal stdout-zero 問題継続 (file redirect 経由 /tmp/kb_*.txt は全 tick 正常取得可)。

## Decision → HOLD + ESCALATE

- spend 0 / outbound send 0 / deploy 0。
- canvas-ledger.edn 未触 (single-writer obey; 本 run では mtime 再確認省略 — 触れていないことは書き込み操作 0 から保証)。
- score-raised? false。

## Next verification

1. owner 判断待ち: apex 301 path 切捨てが意図的か事故か → 復旧 or baseline 更新。
2. checkouts 1 の外部検証 (未検証のまま revenue としては数えない)。
3. x402 unexplained 3 の log 側分類 (owner 手元の log cache 要)。
4. bare `/ipld/` 400 vs SOUL 期待 404 の baseline 更新判断。

## exaggeration-guard

- verified external revenue == **0**。checkouts 1 は未検証。
- 本 tick の新規事実なし — stasis 確認 tick。
- すべての数値は本 tick の実測 curl / nbb 出力から転記。捏造なし。
