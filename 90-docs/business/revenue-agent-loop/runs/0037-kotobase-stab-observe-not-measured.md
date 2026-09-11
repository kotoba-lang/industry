# Run 0037 — kotobase-stab window: OBSERVE only (no signal obtainable)

_Recovered 2026-09-11 from `0037-kotobase-stab-observe-not-measured.edn`, which held only `;;` comment lines (it read as `nil`, not as a record). Text is verbatim; only the comment prefix is removed._

Status: not-measured (not CLEAN)
Started: 2026-09-07
Owner: kotobase-stab cron (Hermes Agent
Mode: cash-first (external verified revenue = 0 2026-08-14);
latest SCORECARD prior unchanged

Measurement attempt
  curl https://kotobase.net/api/funnel        : NOT MEASURED — sandbox terminal returned no output (rc reported 0 but empty, and "Terminal environment unavailable" on stat of repo path); web gateway down ("Nous Tool Gateway not available")
  nbb funnel-pulse                           : NOT MEASURED — nbb not reachable (terminal backend unavailable)
Stability / etc landing checks               : NOT MEASURED — network unreachable from backend

Per SOUL.md: 応答なし/到達不能 は CLEAN ではなく not-measured として記録する。
No funnel number, no stability status code は claimedこのrun。Unknown values recorded as "not measured", not zero.

Score / SELECT
  No fresh observation → no score deltas made.
  Last measured SCORECARD (2026-08-14,: top-ranked executable action was
  cloud-itonami 6399/6310 hosted-money Paid-pilot yes/no (75/100, blocked by
  no contact path — is a状态, not a counsel gate; see SCORECARD line 41-42..
  net-kotobase named-accounts discovery は queued (Beekle form pours human Chrome, Turnstile..
  Gate has  still: product-specific counsel (red for payment-soliciting products;;
  consult/existing live accounts は blocked only by "no contact path" (no counsel needed to ask existing tenants yes/no..
  BUT 外向け送信は禁止此run — draft tylko stop.

Selected action (WIP=1,: DRAFT ony — 5件 の外部 cloud-itonami tenant へ
  paid pilotの yes/no を尋ねる個別DM案。scope: email to tenant owners,
  no public post, no charge, no solicit of new numbers. Assumes contacting
  existing tenants (no gate newly needed))...

Draft (宛先 bear… existing external tenant owners —
  do NOT send this run; left here for next run with signal):
    Subject: kotobase 商用スモール pilotの御確認
    Body: 既存 tenant 様向け — ¥20k / managed hosting paid pilot を
    1外的tenant 当たり 1問 yes/no で確認したい。詳細はお持ちの
    account の billing page です。 ご返信歓迎。 (No charge yet..
  宛先: cloud-itonami の 5 外部 tenant owner メール (実測 2026-08-08: externalPaid 0,
    activeSubscriptions 0); contact list は SCORECARD/console の owner 側に存在 —
    not measured here this run..

Evidence-free guardrails
  Owner self-purchase / test-mode は revenue と数えない。
  No revenue, no CAC, no gross profit は booked 此run (一切...
  canvas-ledger.edn には触れていない (single-writer rule..)

Next verification (when backend回归:
  curl -s https://kotobase.net/api/funnel を実測し funnel 状態を測る;
  /, /signup 200 check;/ipld/v1 400 check;
  nbb funnel-pulse を実測し 1 run から更新する。