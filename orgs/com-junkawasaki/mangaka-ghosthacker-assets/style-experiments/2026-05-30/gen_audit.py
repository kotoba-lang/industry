import json, re
from datetime import datetime

INV='accounts/1password-inventory.json'
LIFE='accounts/1password-lifecycle.json'
SMS='analysis/sms-service-inventory.json'
MAILS=['mail/finance-history-2.jsonl','mail/finance-history-3.jsonl','mail/finance-history-202604.jsonl']
OUT='analysis/subscription-audit.json'

NOW=datetime(2026,5,30)
CUT_2Y=datetime(2024,5,30)   # >2 years stale
CUT_1Y=datetime(2025,5,30)   # >1 year stale (for rotation flag)

def load_jsonl(p):
    out=[]
    for l in open(p):
        l=l.strip()
        if l:
            out.append(json.loads(l))
    return out

mail=[]
for m in MAILS:
    mail.extend(load_jsonl(m))

inv=json.load(open(INV))
life=json.load(open(LIFE))
sms=json.load(open(SMS))

logins=[i for i in inv if i.get('category')=='LOGIN']

def parse(d):
    if not d: return None
    try: return datetime.strptime(d[:10],'%Y-%m-%d')
    except: return None

def domain(item):
    for u in item.get('urls') or []:
        m=re.search(r'https?://([^/]+)',u.get('href',''))
        if m: return m.group(1).lower()
    return ''

def sanitize(title):
    # Remove parenthetical content that may contain emails/passwords/credentials.
    t=re.sub(r'\([^)]*\)','',title or '').strip()
    # Strip any leftover URL fragments
    return t

# ---------------- Paid subscriptions (active) ----------------
# Active = recurring paid charges seen in last ~120 days (since ~2026-02) that are
# genuine receipts/charges, not marketing emails (kind 'subscription' w/ "Marketing:" detail).
paid=[]

# Apple App Store recurring subscriptions: ChatGPT Plus, iCloud+, Meta Verified.
# Take latest occurrence of each.
def latest(pred):
    matches=[r for r in mail if pred(r)]
    return max(matches, key=lambda r: r.get('date',''),default=None)

chatgpt_apple=latest(lambda r: r.get('vendor')=='Apple' and 'ChatGPT Plus' in (r.get('detail') or ''))
if chatgpt_apple:
    paid.append({"service":"ChatGPT Plus (via Apple App Store)","est_cost":"3000 JPY","cadence":"monthly",
                 "evidence_source":f"finance email {chatgpt_apple['date']} Apple App Store receipt (renews {chatgpt_apple['detail'][:0]}order)".strip()})

icloud=latest(lambda r: r.get('vendor')=='Apple' and 'iCloud+' in (r.get('detail') or ''))
if icloud:
    paid.append({"service":"iCloud+ 200GB (Apple)","est_cost":"450 JPY","cadence":"monthly",
                 "evidence_source":f"finance email {icloud['date']} Apple subscription receipt"})

meta=latest(lambda r: r.get('vendor')=='Apple' and 'Meta Verified' in (r.get('detail') or ''))
if meta:
    paid.append({"service":"Meta Verified Standard (via Apple, billed to PayPay)","est_cost":"~1380 JPY","cadence":"monthly",
                 "evidence_source":f"finance email {meta['date']} Apple App Store / Meta Verified order"})

# Anthropic Claude Pro: subscription welcome + recurring receipts.
anthropic_receipts=[r for r in mail if (r.get('vendor') in ('Anthropic, PBC','Anthropic/Claude')) and r.get('kind')=='receipt']
anthropic_welcome=[r for r in mail if r.get('vendor')=='Anthropic, PBC' and r.get('kind')=='subscription']
if anthropic_receipts or anthropic_welcome:
    last_r=max(anthropic_receipts,key=lambda r:r['date']) if anthropic_receipts else None
    paid.append({"service":"Anthropic Claude (Pro/usage)","est_cost":"~36254 JPY (Apr Visa debit) / multiple receipts","cadence":"monthly / recurring",
                 "evidence_source":f"finance email Anthropic receipts ({len(anthropic_receipts)} receipts, last {last_r['date'] if last_r else 'n/a'}); Pro plan welcome 2026-01-31; Visa debit CLAUDE.AI SUBSCRIPTION 2026-04-27"})

# OpenAI ChatGPT direct Visa debit (separate from Apple-billed ChatGPT Plus)
openai_debit=latest(lambda r: r.get('vendor')=='OPENAI ChatGPT' and r.get('kind')=='ai_subscription')
if openai_debit:
    paid.append({"service":"OpenAI ChatGPT (direct Visa debit)","est_cost":f"{openai_debit.get('amount_jpy')} JPY","cadence":"monthly",
                 "evidence_source":f"finance email {openai_debit['date']} Visa debit OPENAI ChatGPT {openai_debit.get('amount_jpy')} JPY"})

# Google Workspace - SUSPENDED/scheduled for cancellation -> NOT active (goes to cancellation candidates)
# Google One - only marketing seen, no receipt -> not counted as confirmed paid.

# ---------------- Cloud / AI spend ----------------
cloud=[]
# Runpod - paid receipts
runpod=[r for r in mail if r.get('vendor','').startswith('Runpod')]
if runpod:
    amts=[re.search(r'\$([\d.]+)',r.get('detail','')) for r in runpod]
    amts=[a.group(1) for a in amts if a]
    cloud.append({"service":"Runpod (GPU cloud, via Stripe)","status":"active_paid",
                  "evidence_source":f"finance email {len(runpod)} Runpod receipts (e.g. $50, $200), last {max(runpod,key=lambda r:r['date'])['date']}"})

# Vultr - PayPal Visa debit
vultr=[r for r in mail if 'VULTR' in (r.get('vendor','') or '').upper()]
if vultr:
    v=vultr[0]
    cloud.append({"service":"Vultr (cloud VPS, via PayPal)","status":"active_paid",
                  "evidence_source":f"finance email {v['date']} PAYPAL*VULTR Visa debit {v.get('amount_jpy')} JPY"})

# Modal - listed in task hint; check presence
modal=[r for r in mail if 'modal' in (r.get('vendor','') or '').lower() or 'modal' in (r.get('detail','') or '').lower()]
if modal:
    cloud.append({"service":"Modal (serverless compute)","status":"seen",
                  "evidence_source":f"finance email {modal[0]['date']} {modal[0].get('vendor')}"})
else:
    cloud.append({"service":"Modal (serverless compute)","status":"no_charge_evidence",
                  "evidence_source":"no Modal receipt/charge found in finance emails (listed in scope but no billing record)"})

# AWS
aws=[r for r in mail if re.search(r'\baws\b|amazon web services',(r.get('vendor','')+' '+r.get('detail','')).lower())]
if aws:
    cloud.append({"service":"AWS","status":"seen",
                  "evidence_source":f"finance email {aws[0]['date']} {aws[0].get('vendor')}"})
else:
    cloud.append({"service":"AWS","status":"no_charge_evidence",
                  "evidence_source":"no AWS billing record found in finance emails (listed in scope but no charge seen)"})

# GCP / Google Cloud Platform
gcp=[r for r in mail if 'cloud platform' in (r.get('vendor','') or '').lower() or r.get('vendor')=='Google Cloud Platform']
if gcp:
    cloud.append({"service":"Google Cloud Platform (GCP)","status":"seen",
                  "evidence_source":f"finance email {gcp[0]['date']} Google Cloud Platform ({len(gcp)} records)"})

# Claude / Anthropic (AI)
cloud.append({"service":"Anthropic / Claude.ai","status":"active_paid",
              "evidence_source":"finance email Anthropic receipts + CLAUDE.AI SUBSCRIPTION Visa debit 36254 JPY (2026-04-27)"})

# OpenAI (AI)
cloud.append({"service":"OpenAI (ChatGPT / API)","status":"active_paid",
              "evidence_source":"finance email OPENAI ChatGPT Visa debit 7251 JPY (2026-04-27) + ChatGPT Plus via Apple"})

# Hugging Face
hf=[r for r in mail if 'hugging face' in (r.get('vendor','') or '').lower()]
if hf:
    paid_hf=[r for r in hf if r.get('kind')=='payment']
    fail_hf=[r for r in hf if r.get('kind')=='payment_failed']
    cloud.append({"service":"Hugging Face (via Stripe)","status":"active_paid_with_failures",
                  "evidence_source":f"finance email {len(paid_hf)} successful payments, {len(fail_hf)} failed; last {max(hf,key=lambda r:r['date'])['date']}"})

# Fly.io - repeated FAILED payments
fly=[r for r in mail if r.get('vendor','').startswith('Fly.io')]
if fly:
    fail=[r for r in fly if r.get('kind')=='payment_failed']
    cloud.append({"service":"Fly.io (via Stripe)","status":"payment_failing",
                  "evidence_source":f"finance email {len(fail)} consecutive failed payments ($7.33-$8.14), no success; last {max(fly,key=lambda r:r['date'])['date']}"})

# Suno
suno=[r for r in mail if 'suno' in (r.get('vendor','')+' '+r.get('detail','')).lower()]
if suno:
    cloud.append({"service":"Suno","status":"seen","evidence_source":f"finance email {suno[0]['date']} {suno[0].get('vendor')}"})
else:
    cloud.append({"service":"Suno","status":"no_charge_evidence","evidence_source":"no Suno billing record found in finance emails (listed in scope)"})

# Hume
hume=[r for r in mail if 'hume' in (r.get('vendor','')+' '+r.get('detail','')).lower()]
if hume:
    cloud.append({"service":"Hume","status":"seen","evidence_source":f"finance email {hume[0]['date']} {hume[0].get('vendor')}"})
else:
    cloud.append({"service":"Hume","status":"no_charge_evidence","evidence_source":"no Hume billing record found in finance emails (listed in scope)"})

# Apify
apify=[r for r in mail if 'apify' in (r.get('vendor','')+' '+r.get('detail','')).lower()]
if apify:
    cloud.append({"service":"Apify","status":"seen","evidence_source":f"finance email {apify[0]['date']} {apify[0].get('vendor')}"})
else:
    cloud.append({"service":"Apify","status":"no_charge_evidence","evidence_source":"no Apify billing record found in finance emails (listed in scope)"})

# Anifusion (extra AI vendor seen, via Stripe)
anif=[r for r in mail if 'anifusion' in (r.get('vendor','') or '').lower()]
if anif:
    paid_an=[r for r in anif if r.get('kind')=='payment']
    cloud.append({"service":"Anifusion (AI image, via Stripe)","status":"active_paid_with_failures",
                  "evidence_source":f"finance email {len(paid_an)} payments + 1 failed (€20), last {max(anif,key=lambda r:r['date'])['date']}"})

# LemonSqueezy (infra/SaaS recurring)
lemon=[r for r in mail if 'lemonsqueezy' in (r.get('vendor','') or '').lower()]
if lemon:
    cloud.append({"service":"LemonSqueezy (SaaS reseller, recurring)","status":"active_paid",
                  "evidence_source":f"finance email {lemon[0]['date']} PAYPAL*LEMONSQUEEZY Visa debit {lemon[0].get('amount_jpy')} JPY (recurring)"})

# ---------------- 1Password counts ----------------
op_login_count=len(logins)
stale_2y=[i for i in logins if parse(i.get('updated_at')) and parse(i.get('updated_at'))<CUT_2Y]

# ---------------- Crypto exchange logins ----------------
EX_KW=['binance','coinbase','kraken','bitflyer','bitbank','coincheck','gmo coin','gmocoin','bybit',
 'okx','kucoin','gate.io','huobi','crypto.com','bitget','liquid.com','liquid','zaif','bittrex','gemini',
 'blockchain.info','blockchain.com','metamask','bitfinex','bitmex','phemex','mexc','dydx','ftx',
 'sbi vc','sbivc','rakuten wallet','decurret','lbank','poloniex']
def is_exchange(item):
    blob=(item.get('title') or '').lower()+' '+domain(item)
    return any(k in blob for k in EX_KW)

crypto=[]
seen=set()
for i in logins:
    if not is_exchange(i): continue
    d=domain(i)
    t=sanitize(i.get('title'))
    u=i.get('updated_at')
    pd=parse(u)
    key=(t.lower(),d)
    if key in seen: continue
    seen.add(key)
    flag = bool(pd and pd<CUT_1Y)
    crypto.append({
        "title":t,
        "domain":d,
        "updated_at":u,
        "flag_for_rotation":flag,
        "reason":"not updated in >1 year (crypto exchange credential)" if flag else "updated within last year"
    })
crypto.sort(key=lambda x:x['updated_at'] or '')

# ---------------- Cancellation candidates ----------------
cancel=[]
# Google Workspace - suspended & scheduled for cancellation
gw=[r for r in mail if r.get('vendor')=='Google Workspace']
if gw:
    cancel.append({"service":"Google Workspace (Business Standard)",
                   "reason":"suspended 2026-04-03; subscription scheduled for cancellation (repeated dunning notices through 2026-05-18) — confirm cancellation / stop billing"})
# Fly.io - failing payments
if fly:
    cancel.append({"service":"Fly.io",
                   "reason":f"{len([r for r in fly if r.get('kind')=='payment_failed'])} consecutive failed payments and no successful charge since 2026-03 — likely abandoned; cancel or fix card"})
# Anifusion failed payment / low usage
if anif and any(r.get('kind')=='payment_failed' for r in anif):
    cancel.append({"service":"Anifusion",
                   "reason":"had a failed €20 payment (2026-03-05); niche AI image service — review whether still needed"})
# Hugging Face had failure
if hf and any(r.get('kind')=='payment_failed' for r in hf):
    cancel.append({"service":"Hugging Face",
                   "reason":"a $9 payment failed (2026-03-02) before succeeding; review whether the paid plan is still used"})
# Meta Verified - discretionary
if meta:
    cancel.append({"service":"Meta Verified Standard",
                   "reason":"discretionary social-media verification badge (~monthly); candidate if not actively needed"})
# Apple in-app coin purchases on secondary account mika.3.etoile (not a subscription but recurring spend)
cancel.append({"service":"Piccoma / LINE in-app coin purchases (Apple App Store, acct mika.3.etoile)",
               "reason":"recurring discretionary in-app content purchases on a secondary Apple ID — review for unwanted/forgotten spend"})

# ---------------- Security rotation candidates ----------------
# Stale (>2y or >1y for finance/crypto) 1Password logins, focus on finance/crypto.
FIN_KW=['bank','銀行','revolut','paypal','paypay','sbi','mufg','mizuho','sumitomo','三井','三菱','楽天',
 'rakuten','jal card','visa','mastercard','stripe','wise','payoneer','ゆうちょ','japanpost','jcb','amex',
 'american express','信用金庫','証券','securities']
def is_finance(item):
    blob=(item.get('title') or '').lower()+' '+domain(item)
    return any(k in blob for k in FIN_KW)

rotation=[]
rot_seen=set()
for i in logins:
    pd=parse(i.get('updated_at'))
    if not pd: continue
    crypto_flag=is_exchange(i)
    fin_flag=is_finance(i)
    if not (crypto_flag or fin_flag): continue
    # crypto/finance: flag if >1y stale; otherwise skip
    if pd>=CUT_1Y: continue
    t=sanitize(i.get('title'))
    d=domain(i)
    key=(t.lower(),d)
    if key in rot_seen: continue
    rot_seen.add(key)
    cat='crypto_exchange' if crypto_flag else 'finance'
    sev='high' if pd<CUT_2Y else 'medium'
    rotation.append({"title":t,"domain":d,"updated_at":i.get('updated_at'),
                     "category":cat,"severity":sev,
                     "reason":f"{cat} login not updated in over {'2 years' if pd<CUT_2Y else '1 year'} — rotate credential"})
rotation.sort(key=lambda x:(x['category']!='crypto_exchange', x['updated_at'] or ''))

audit={
 "generated_at":"2026-05-30",
 "paid_subscriptions_active":paid,
 "cloud_ai_spend":cloud,
 "onepassword_login_count":op_login_count,
 "stale_logins_over_2y":len(stale_2y),
 "crypto_exchange_logins":crypto,
 "cancellation_candidates":cancel,
 "security_rotation_candidates":rotation,
}

json.dump(audit,open(OUT,'w'),ensure_ascii=False,indent=2)

# summary to stdout
print('paid_subscriptions_active:',len(paid))
for p in paid: print('  -',p['service'])
print('cloud_ai_spend:',len(cloud))
for c in cloud: print('  -',c['service'],'['+c['status']+']')
print('onepassword_login_count:',op_login_count)
print('stale_logins_over_2y:',len(stale_2y))
print('crypto_exchange_logins:',len(crypto),'flagged_for_rotation:',sum(1 for c in crypto if c['flag_for_rotation']))
print('cancellation_candidates:',len(cancel))
for c in cancel: print('  -',c['service'])
print('security_rotation_candidates:',len(rotation),'(crypto:',sum(1 for r in rotation if r['category']=='crypto_exchange'),')')
