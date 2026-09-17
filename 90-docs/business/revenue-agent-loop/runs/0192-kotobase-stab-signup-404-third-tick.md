# run 0192 — kotobase-stab tick 2026-09-15 (JST, run 0191 直後の同日 tick)

## 計測 (全生計測、捏造なし)
- `nbb kotobase_lead_loop.cljk funnel-pulse` → EXIT 0
  `SCANNED 1 / VISITORS 0 / SIGNUPS 0 / CHECKOUTS 0 /
   DELTA {:visitors 0, :signups 0, :checkouts 0} / UNCHANGED true /
   EXTERNAL-FUNNEL-CHANGE false / SCORE unchanged / RESULT unchanged`
- `curl -s https://kotobase.net/api/funnel` → 301 → follow 200 JSON verbatim 要点:
  visitors 0 / signups 0 / signup_completed 0 / registrations 0 /
  by-source 全空 / seeded false / persistence.kind "isolate-memory" (durable
  false — isolate 再生で counter reset、lifetime totals ではない旨明記) /
  openai_ads HOLD。最終 URL は apex 301 経由で kotoba.cloud 側。

## 安定化チェック (本 tick 生値)
- `GET /` (no follow) → **301**, `location: https://kotoba.cloud/docs/graph/`
  (Cloudflare edge 発, cf-ray NRT, headers verbatim)。follow → 200。
- `GET /signup` (no follow) → **301** → follow → **404** (run 0190/0191 と同値、
  3 tick 連続)。funnel 入口は引き続き実質 dead。
- `GET /ipld/v1` → **302** (→ ipfs.kotobase.net) → follow **400** =
  route 存在の正常値。
- `GET /ipld/` (bare) → 本 tick 実測は **302 → follow 400** (2026-09-03 記録の
  「bare 404 正常」ではなく 302 転送が覆っている。route 自体は生きている。
  値の変化は記録するが原因 UNCONFIRMED)。
- `https://kotoba.cloud/` → 200、`kotoba.cloud/docs/graph/` → 200、
  `kotoba.cloud/signup` → 404 (転送先にも signup が無いことを再実測)。
- 原因推定 1 行 (UNCONFIRMED): apex → kotoba.cloud への edge redirect が継続中で、
  転送先側に /signup が未整備のため funnel 入口が 404 のまま。

## SCORE → SELECT (WIP=1)
- SCORECARD は 2026-08-14 観測分のまま (より新しい scorecard 無し)。
- Selected action (1 件): **run 0190/0191 と同一 — 「/signup → 404 の funnel
  入口断線」の owner 報告継続 (3 tick 連続)**。root cause が未解決のため
  アクションは交代しない。
  根拠の数字: signups 0 / delta 0 / checkouts 0 / verified external revenue 0。
  入口が 404 の間、signup 導線は物理的に閉じている。draft 0045 (58/100) は
  入口復旧まで無意味。
- Bounded experiment: 外向け送信なし (SOUL.md 原則)。本 run file と報告文で
  止まる。redirect rule 変更 / deploy はしない (edge 側設定は repo 外、
  原因特定前の本番操作は危険 — run 0189–0191 と同じ判断)。

## Decision → HOLD + ESCALATE (report, 3 tick 連続)
- spend 0 / self-purchase 0 / outbound send 0 / deploy 0。
- canvas-ledger.edn は触っていない (single-writer rule)。

## Next verification
1) **Owner: apex → kotoba.cloud redirect の意図確認 + /signup の埋め戻し**
   (3 tick 連続の最優先)。確認経路: Cloudflare Redirect Rules /
   `wrangler deployments list` (repo 外設定のため repo からは検証不能)。
2) 入口復旧後の再実測: / 200 (自ドメイン), /signup 200, /ipld/v1 400。
3) funnel counter が非ゼロに戻るか (isolate-memory reset 後の tick delta)。
4) 繰り越し: checkout 1 件の検証 / counsel written reply / draft 0045 go-no-go。
5) 環境注意: 本 tick も foreground terminal が stdout を返さない異常継続
   (run 0187 以降)。全出力を file redirect で取得して回避。原因 UNCONFIRMED。

## exaggeration-guard
- verified external revenue == 0。counter は isolate-memory で lifetime ではない。
- /signup 404 は 3 tick 連続の生実測。redirect の意図/原因は UNCONFIRMED。
- /ipld/ bare の 302 は本 tick の生値であり、旧記録 (404) との差は実測ベースで
  記録しただけ。signups 0 は「新規 0」であって過去実績消失を意味しない。
