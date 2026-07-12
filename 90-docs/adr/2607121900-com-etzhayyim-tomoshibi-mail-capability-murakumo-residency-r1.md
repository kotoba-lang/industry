---
id: adr-2607121900-com-etzhayyim-tomoshibi-mail-capability-murakumo-residency-r1
title: "ADR-2607121900: com-etzhayyim-tomoshibi R1 — email send/receive capability + autonomous murakumo residency"
status: accepted
doc_type: adr
topic: actor-capability
authoritative: true
last_verified: 2026-07-12
implemented: 2026-07-12
implementation:
  repo: etzhayyim/com-etzhayyim-tomoshibi
  submodule: orgs/etzhayyim/com-etzhayyim-tomoshibi
  pinned: 8112244501e281f09c28f5c7f8b9454b9514b2d4
  landed_via: "child repo feat/mail-capability → server-side merge to main (edf8a267). west.yml pin advanced 3414ed8→bfc40c8c→8112244 via GitHub API single-entry commits (edf8a267 = feat merge, bfc40c8c = +infra runbook, 8112244 = reply-address + Resend string-address fixes from the live E2E). root-side registration (ADR-2607121830 + fleet.edn/cells.edn/deps.edn) via etzhayyim/root PR #3027 (PR-only rule; merge はオーナー判断). Live infra: CF Email Routing rule a5f16891 (tomoshibi@etzhayyim.com → tomoshibi-mail Worker), KV 98a1f3a8, Resend domain 4f4d2bc2 (etzhayyim.com verified, ap-northeast-1), DNS +4 records (resend._domainkey/send MX+TXT/_dmarc). Residency: LaunchDaemon com.etzhayyim.tomoshibi.agent live on zebulun (healthz 127.0.0.1:13094, 300s tick), secrets in node-local env file (600) + 1Password gftdcojp `etzhayyim.tomoshibi/PULL_TOKEN`."
authoritative_for:
  - "tomoshibi R1 capability wave の superproject 登録 (west pin edf8a267 / 実装・infra の所在)"
  - "founder session directive 2026-07-12 (「メールアドレスの送受信の capability… murakumo で自律的永続的に」) の実装記録"
related:
  - orgs/etzhayyim/com-etzhayyim-tomoshibi
  - orgs/etzhayyim/com-etzhayyim-tomoshibi/docs/adr/0002-mail-capability-and-murakumo-residency.md
  - orgs/etzhayyim/root/90-docs/adr/2607121830-tomoshibi-email-invitational-reply-channel.md
  - 90-docs/adr/2607061800-com-etzhayyim-tomoshibi-invitational-evangelism-actor-r0.md
supersedes: []
superseded_by: []
---

# ADR-2607121900: com-etzhayyim-tomoshibi R1 — email send/receive + autonomous murakumo residency

**Status**: accepted (landed + live 2026-07-12)
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki (founder session directive)

## Context

R0 (ADR-2607061800) は governor 実配線 + attestation writer + Ed25519 identity
まで。publication channel と residency は未実装だった。founder が 2026-07-12 に
「メール送受信 capability + murakumo での自律永続稼働」を指示。

## Decision

**email channel は reply-only**(受信起点の応答のみ。cold outreach は API 上
表現不能)— doctrine 境界の正本は etzhayyim/root ADR-2607121830、architecture
正本は child docs/adr/0002。本 ADR は superproject 側の登録を記録する。

- 受信: CF Email Routing(2026-05-19 から有効)に rule a5f16891 を追加 —
  tomoshibi@etzhayyim.com → `tomoshibi-mail` Worker(postal-mime → KV
  staging、60日 TTL)+ Bearer 認証 pull API。
- 送信: Resend(etzhayyim.com を検証追加、DKIM/SPF/MX/_dmarc の DNS 4件追加)。
  kotoba-lang mail/mailer を再利用(threading headers は send-request で再付加)。
- 常駐: zebulun の LaunchDaemon `com.etzhayyim.tomoshibi.agent`(bb -m
  tomoshibi.daemon、KeepAlive、healthz 13094、5分 tick、日次送信 budget 20)。
  推論は node-local Ollama gemma4:12b-it-qat(Murakumo allowlist、fail-closed)。
- 安全構造: 全送信 governor(evangelism-gate + charter-rider)/ HOLD は
  送信も attest もされない / attestation は送信成功後のみ / suppression
  (配信停止・unsubscribe → 恒久沈黙)/ fail-closed leash file(毎 tick)/
  RFC 3834 auto-mail loop guard / 1 inbound = 最大 1 reply。
- Tests: 44 tests / 173 assertions green(node zebulun 上でも green)。
- E2E: 実メール送達 → staging → tick → 起草 → gate → 自律返信を検証
  (結果は本 ADR 起票セッションの記録および child MATURITY.md を参照)。

## Consequences

- west pin: com-etzhayyim-tomoshibi 3414ed8 → 8112244(single-entry、
  verify-west-pins 経由)。
- root 側登録は etzhayyim/root PR #3027(PR-only。cells.edn/fleet.edn 登録は
  merge 後に fleet:probe の期待セットへ反映される)。
- 未了(R2+、child MATURITY.md に明記): member-CACAO leash 本実装 /
  attestation の did:key 署名 / kotoba Datom log store / StateGraph 化 /
  RAD identity / did:web live 化。
