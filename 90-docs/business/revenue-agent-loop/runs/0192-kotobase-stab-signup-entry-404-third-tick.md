# run 0192 — kotobase-stab tick 2026-09-16 (JST)

## 計測 (全生計測、捏造なし)
- terminal stdout capture は本 tick も全滅 (`echo` すら空、exit 0 のみ)。
  → run 0191 の workaround 継続: 全出力を `/tmp` の file redirect で取得後
  read_file で読む。これで全計測は取得できた。
- `nbb 90-docs/business/revenue-agent-loop/kotobase_lead_loop.cljk funnel-pulse`
  → EXIT 0 / `SCANNED 1 / VISITORS 0 / SIGNUPS 0 / CHECKOUTS 0 /
  DELTA {:visitors 0, :signups 0, :checkouts 0} / UNCHANGED true /
  EXTERNAL-FUNNEL-CHANGE false`
- `curl -sL https://kotobase.net/api/funnel` → 200 JSON (apex 301 経由、
  final = kotoba.cloud/api/funnel): visitors 0 / signups 0 / signup_completed 0 /
  registrations 0 / by-source 全空 / seeded false /
  persistence.kind = "isolate-memory" (durable false — lifetime totals ではない)。
  本 tick の新規 visitor 0 を意味するだけで、過去実績の消失ではない。

## 安定化チェック (curl -w headers verbatim)
- `GET /` nofollow → **301** → `https://kotoba.cloud/docs/graph/` (run 0190/0191 同値)。
- `GET /signup` nofollow → **301** → `https://kotoba.cloud/signup`;
  follow → **404** (final URL kotoba.cloud/signup)。
  **funnel 入口断線は 3 tick 連続で未修復 (0190 → 0191 → 0192)。**
  注: web_extract 経由では kotobase.net/signup に "Start free — kotobase"
  の全文ページ content が取れた (おそらく extractor 側 cache/proxy の別経路)。
  生 curl の 404 を正とする。食い違いは UNCONFIRMED として記録。
- `GET /ipld/v1` nofollow → **302** → `https://ipfs.kotobase.net/ipfs/v1`;
  follow → **400** = route 存在の正常値。ipld plane は生存。
- bare `/ipld/` 直測は省略 (0190/0191 同様、redirect に覆われるため非判定)。
- 原因推定 1 行 (UNCONFIRMED, 0191 から変化なし): apex → kotoba.cloud への
  redirect 移行が継続中で、転送先 kotoba.cloud 側に /signup が未設置のまま。

## SCORE → SELECT (WIP=1)
- SCORECARD は 2026-08-14 観測分のまま、本 tick で更新せず。
- Selected action (1 件, 0190/0191 から継続): **「/signup → kotoba.cloud/signup →
  404 の funnel 入口断線」を owner への最優先報告に据える (3 tick 連続)**。
  根拠の数字: signups 0 + delta 0 (入口 404 の間 signup 導線は物理的に閉鎖)、
  checkouts 0、verified external revenue 0。SCORECARD 上位行 (cloud-itonami 75)
  は counsel gate で blocked のまま変化なし。net-kotobase signup 関連 score 58
  (run 0033 時点) も入口復旧まで上昇しない。
- Bounded experiment: 外向け送信なし / deploy なし / redirect rule 変更なし
  (原因 UNCONFIRMED のままの本番操作は危険、0189–0191 と同一判断)。
  本 run file と報告文で止まる。

## Decision → HOLD + ESCALATE (3 tick 連続)
- spend 0 / self-purchase 0 / outbound send 0 / deploy 0。
- canvas-ledger.edn は触っていない (single-writer rule)。

## Next verification
1) **Owner action (最優先, 3 tick 連続): kotoba.cloud/signup の 404 埋め戻し、
   または apex /signup の redirect 先の意図確認。** 経路: Cloudflare Redirect
   Rules / `wrangler deployments list` (repo 外設定につき repo から検証不能)。
2) 入口復旧後の再実測: / 自ドメイン 200 / /signup 200 / /ipld/v1 400。
3) funnel counter の tick delta が非ゼロに戻るか。
4) web_extract の signup page content と生 curl 404 の食い違い原因
   (edge cache vs extractor 経路) の確認 — 今回は未着手。
5) 繰り越し: checkout 1 件の検証 / counsel written reply / draft 0045 go-no-go。

## exaggeration-guard
- verified external revenue == 0。counter は isolate-memory で lifetime ではない。
- signups 0 は「新規 0」であり「過去実績が消えた」ではない。
- 入口断線の継続は 3 tick 分の生 curl 実測。原因/意図は UNCONFIRMED。
