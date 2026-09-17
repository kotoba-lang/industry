# run 0190 — kotobase-stab tick 2026-09-14 (JST ~20:04)

## 計測 (全生計測、捏造なし)
- `nbb kotobase_lead_loop.cljk funnel-pulse` → EXIT 0 (file-redirect workaround:
  SOUL の `.cljs` は ENOENT、正しくは `.cljk` — run 0184/0189 と同様)
  `SCANNED 1 / VISITORS 0 / SIGNUPS 0 / CHECKOUTS 0 /
   DELTA {:visitors -12859, :signups -42, :checkouts -1} / UNCHANGED false /
   EXTERNAL-FUNNEL-CHANGE false / SCORE unchanged / RESULT recorded`
- `curl -sL https://kotobase.net/api/funnel` → 200 JSON verbatim 要点:
  visitors 0 / signups 0 / signup_completed 0 / registrations 0 /
  by-source 全空 / seeded false /
  persistence.kind = **"isolate-memory"** / durable false —
  「Counters live in isolate memory and reset when the isolate recycles.
  Do not treat these as lifetime totals.」
- 負 delta (-12859/-42/-1) は前 tick (0189: 13256/42/1) からの減少ではなく
  **counter reset**。/api/funnel の persistence 説明と self-consistent。
  本 tick 中の新規 visitor は 0 としか読めない (not-measured 以上は言えない)。

## 安定化チェック (HTTP headers verbatim)
- `GET /` → **301**, `location: https://kotoba.cloud/docs/graph/` (run 0189 は
  bare `https://kotoba.cloud/` だった — redirect 先が変化している)。
  follow → 200 (kotoba.cloud docs ページ, ~110KB)。
- `GET /signup` → **301** → `https://kotoba.cloud/signup` → follow で **404**。
  **signup funnel 入口が実質 dead**。本 tick の最重要 stabilization finding。
- `GET /ipld/v1` → **302** → `https://ipfs.kotobase.net/ipfs/v1` → follow で
  **400** (= route 存在の正常値に到達。前 tick と異なり ipld 面は
  ipfs.kotobase.net への 302 経路に変わっている)。
- `GET /ipld/` → 302 → ipfs.kotobase.net/ipfs/ (bare 404 期待値は redirect
  に覆われ本 tick 未直測)。
- `GET /api/funnel` (no follow) → 301 → `https://kotoba.cloud/api/funnel`;
  follow で 200 JSON (上記)。
- 原因推定 1 行 (UNCONFIRMED): apex domain を kotoba.cloud へ移す移行/再deploy
  (run 0189 の 301 と同系だが対象・destination が拡大・変化) が進行中で、
  /signup の転送先がまだ kotoba.cloud 側に存在しない。

## SCORE → SELECT (WIP=1)
- SCORECARD は 2026-08-14 観測分のまま (これより新しい scorecard は無い)。
- Selected action (1 件): **「/signup → kotoba.cloud/signup → 404 の
  funnel 入口断線」を owner への最優先報告に据える**。
  根拠の数字: signups +0 (counter reset 直後、かつ入口が 404 —
 signup 導線は物理的に閉じている) / x402 settlements 0 (0185–0189 で連続)。
  58/100 の signup-activation draft (0045) は入口復旧まで無意味なため
  順位を実質上書きする stabilization finding。
- Bounded experiment: 外向け送信はしない (SOUL.md 原則)。本 run file と
  報告文で止まる。worker 再 deploy / redirect rule 変更もしない
  (原因特定前の本番操作は危険、run 0189 と同じ判断)。

## Decision → HOLD + ESCALATE (report)
- spend 無し / self-purchase 無し / outbound send 無し / deploy 無し。
- canvas-ledger.edn は触っていない (single-writer rule)。

## Next verification
1) **Owner: kotoba.cloud への apex redirect の意図確認** — 意図的移行なら
   `kotoba.cloud/signup` の 404 を埋める (rebrand 後の signup page) か
   `location` を正しい新入口へ; 事故なら redirect rule を戻す。
   確認経路: `wrangler deployments list` / Cloudflare Redirect Rules /
   直近 deploy ログ (repo 外設定のため repo からは検証不能)。
2) 入口復旧後: / 200, /signup 200, /ipld/v1 400, /ipld/ 404 の再実測。
3) counter reset 後の funnel が非ゼロに戻るか (visitors/signups の tick delta)。
4) 繰り越し: checkout 1 件の検証 / counsel written reply / draft 0045 go-no-go。
5) 環境注意: 本 tick も foreground terminal が stdout を返さない異常
   (0187 以降継続)。全出力を file redirect で取得して回避。原因 UNCONFIRMED。

## exaggeration-guard
- verified external revenue == 0。counter は isolate-memory で reset 済み —
  lifetime totals としては読めない。signups 0 は「新規 0」であって
  「過去 42 が消えた」という意味ではない (persistence 説明の通り)。
- redirect 変化は実測 (headers verbatim) だが意図/原因は UNCONFIRMED。
