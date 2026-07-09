import json, sys, re
from datetime import datetime, timezone, timedelta

infile = sys.argv[1]
outfile = sys.argv[2]
threadid = sys.argv[3]
subject_title = sys.argv[4]

JST = timezone(timedelta(hours=9))

with open(infile, encoding='utf-8') as f:
    data = json.load(f)

msgs = data['messages']

def parse_date(d):
    dt = datetime.fromisoformat(d.replace('Z', '+00:00'))
    return dt.astimezone(JST)

for m in msgs:
    m['_dt'] = parse_date(m['date'])
msgs.sort(key=lambda m: m['_dt'])

def clean_body(body):
    if not body:
        return "", []
    lines = body.split('\n')
    drive_links = []
    for ln in lines:
        for url in re.findall(r'https?://[^\s<>"]+', ln):
            if 'drive.google.com' in url or 'docs.google.com' in url:
                drive_links.append(url.rstrip('.,)'))
    # strip quoted history (> lines)
    out = [ln for ln in lines if not ln.strip().startswith('>')]
    text = '\n'.join(out)
    # cut long confidentiality boilerplate footer
    markers = ['本メールには', '本電子メールは', 'This email', 'This e-mail',
               '本メールに記載された情報', 'このメールおよび', '本メールおよび添付',
               '本メールは送信者', '本メールおよびその添付']
    lowest = len(text)
    for mk in markers:
        idx = text.find(mk)
        if idx != -1 and idx < lowest:
            lowest = idx
    text = text[:lowest]
    text = re.sub(r'\n{3,}', '\n\n', text).strip()
    return text, drive_links

def find_att_names(body):
    names = []
    if not body:
        return names
    for m in re.findall(r'「([^」]*)」', body):
        if re.search(r'(振込履歴|補助元帳|預り金|預金|調書|仕訳|貯金|預払|普通預金|ゆうちょ|帳簿|元帳|データ)', m):
            names.append(m)
    for m in re.findall(r'[^\s「」（）()【】]*\.(?:pdf|xlsx|xls|csv|zip|docx|doc|pptx)', body, re.IGNORECASE):
        names.append(m)
    return names

all_senders = set()
all_drive = []
all_att_names = []
total_att = 0
parts = []
for i, m in enumerate(msgs, 1):
    sender = m['sender']
    all_senders.add(sender)
    to = m.get('toRecipients') or []
    cc = m.get('ccRecipients') or []
    for r in to: all_senders.add(r)
    for r in cc: all_senders.add(r)
    raw = m.get('plaintextBody', '')
    body, links = clean_body(raw)
    all_drive.extend(links)
    real_atts = m.get('attachments') or []
    natt = len(real_atts) if real_atts else len(m.get('attachmentIds') or [])
    total_att += natt
    # prefer real attachment filenames, fall back to body-referenced names
    real_names = [a.get('filename') for a in real_atts if a.get('filename')]
    names = real_names if real_names else find_att_names(raw)
    all_att_names.extend(names)
    msg_att_names = names
    dt = m['_dt'].strftime('%Y-%m-%d %H:%M JST')
    header = f"## {i}. {dt} — {sender}\n"
    header += f"**To:** {', '.join(to) if to else '(none)'}  \n"
    if cc:
        header += f"**Cc:** {', '.join(cc)}  \n"
    header += f"**Subject:** {m.get('subject','')}\n"
    if natt:
        if msg_att_names:
            header += f"**添付ファイル:** {natt}件 — {', '.join(msg_att_names)}\n"
        else:
            header += f"**添付ファイル:** {natt}件\n"
    parts.append(header + "\n" + body + "\n")

oldest = msgs[0]['_dt'].strftime('%Y-%m-%d')
newest = msgs[-1]['_dt'].strftime('%Y-%m-%d')

def uniq(seq):
    seen = set(); out = []
    for x in seq:
        if x not in seen:
            seen.add(x); out.append(x)
    return out

uniq_drive = uniq(all_drive)
uniq_names = uniq(all_att_names)

att_lines = []
if total_att:
    att_lines.append(f"  - 添付ファイル {total_att}件（本文中参照名: {', '.join(uniq_names) if uniq_names else '不明'}）")
for l in uniq_drive:
    att_lines.append(f"  - {l}")
if att_lines:
    att_str = '\n' + '\n'.join(att_lines)
else:
    att_str = 'なし'

participants = ', '.join(sorted(all_senders))

out = f"""# [ZeLo / LingLing訴訟] {subject_title}

- Thread ID: `{threadid}`
- メッセージ数: {len(msgs)}
- 期間: {oldest} 〜 {newest}
- 参加者: {participants}
- 添付: {att_str}

---

""" + "\n---\n\n".join(parts)

with open(outfile, 'w', encoding='utf-8') as f:
    f.write(out)

print(f"WROTE {outfile}")
print(f"messages={len(msgs)} range={oldest}..{newest}")
print(f"total_attachments={total_att} drive_links={len(uniq_drive)} att_names={uniq_names}")
for l in uniq_drive:
    print("  LINK:", l)
