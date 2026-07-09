# ADR-2607061900: gftdcojp recipient-set, capability-grant, and epoch rotation on top of ADR-2607051000's per-graph DEK

**Status**: accepted
**Date**: 2026-07-06
**Deciders**: Jun Kawasaki

## Context

ADR-2607051000 (accepted same day as this document, see its "Acceptance"
section) designs a per-graph Data Encryption Key (DEK) for `arrangement`/
`kotobase-peer`: AES-256-GCM for tx-block quad payloads, HMAC-SHA256 blind
indexing for `index-root`'s prolly-tree keys, DEK derived from an actor's
key material. That document explicitly scoped itself to **one key, one
graph** and left "how does more than one authorized reader come to hold the
same DEK" as Open question 1 ("per-graph key vs. per-actor key vs. some
other granularity... especially once actor might mean an org/team, not a
single individual").

`gftdcojp` is exactly that org/team case. ADR-2607022300 registered
`gftdcojp/gftdcojp` as a private tenant in `cloud-itonami` (business
activity/effect/audit data — contracts, invoices, HR, CRM from
`orgs/gftdcojp/m365-archive`) with `:itonami.repo/visibility :private` and
CACAO/`did:key` self-issued auth, explicitly rejecting a shared-token or
"public sanitized read" model. That ADR's own Implementation status is
blunt about where things stand: **"現時点で本番に bind された actor は無い"**
(zero actors are bound in production yet — not even Jun's own did:key has
been generated, `2607022300`:174-177) — a real incident that same ADR
documents (`2607022300`:133-179) is a stale Cloudflare Pages deployment that
served gftdcojp business data with no auth at all until it was caught and
the deployment was fixed. This is a tenant that has learned, concretely,
that "private" only means something if the access-control story is airtight
end to end, not just declared in an ADR.

This document is that airtight story for the **encryption** half (the query
API / CACAO-gate half is `cloud-itonami`'s own concern, ADR-2607022300;
ADR-2607051000's own Alternative (c) explicitly separates the two — this
design does not replace CACAO query-gating, it protects the data even if
the query gate is ever bypassed the way it briefly was).

### Why "recipient set", not "one shared DEK handed to gftdcojp as if it were one actor"

gftdcojp is not one key-holder. It is (at minimum) Jun today, and in the
future other named people or service actors. Handing all of them the same
raw DEK (e.g. by literally sharing the key material out of band) has no
way to revoke one member without rotating and manually redistributing to
everyone else, and no audit trail of who was ever given it. The fix already
has same-superproject precedent, just built for a different purpose:

- `kotoba-custody`'s R3 custodian protocol (`orgs/com-junkawasaki/kotoba/
  crates/kotoba-custody/src/protocol.rs`) already has the exact shape this
  document needs — `GrantedShare` (lines ~65-90) carries an `epoch: u64`
  ("Rotation epoch this share belongs to (R3c)"), a `deal_id` ("Dealing
  binding — `combine_granted` requires all grants to agree, so a
  mixed-epoch quorum is rejected"), and `graph_cid_mb` ("Graph this grant
  is for... self-describing so the grant stands alone as evidence"), and
  the release is HPKE-re-wrapped per requester ("a share in flight is
  readable only by this requester"). That system exists to let **custodians**
  release Shamir shares of a *block key* back to one requester under
  quorum — a different job (key recovery, not standing multi-reader
  access) — but the `epoch`/`graph`-scoped/HPKE-wrapped-grant shape is
  exactly transferable.
- `kotoba-crypto/src/hpke.rs` (`orgs/com-junkawasaki/kotoba/crates/
  kotoba-crypto/src/hpke.rs:1-45`) is the concrete wire format to mirror:
  `ephemeral_pk(32) || nonce(12) || AES-256-GCM-ciphertext`, shared secret
  `X25519(ephemeral_sk, recipient_pk)`, key derived via
  `HKDF-SHA256(ikm=ss, salt=eph_pk||recipient_pk, info=b"kotoba/hpke/v1")`.
  This Rust crate is not directly reachable from the `.cljc`/Cloudflare
  Worker runtime `arrangement`/`kotobase-peer` actually ships in (same
  cross-runtime constraint ADR-2607051000 already resolved for its own
  AEAD/blind-index primitives), so this document does not depend on the
  crate itself — it adopts the **same algorithm choices and wire shape**
  (X25519 ECDH + HKDF-SHA256 + AES-256-GCM) for consistency, reimplemented
  via `crypto.subtle` (Worker) / `javax.crypto` (JVM), the same two runtimes
  ADR-2607051000 already targets.
- `kotoba-server/src/xrpc.rs`'s existing `:capability/*` datoms
  (`append_auth_capability_datoms`, `xrpc.rs:2766-`) are worth naming
  explicitly so this document's new datoms are not confused with them:
  those are **audit/receipt** datoms asserted onto a *tx* after the fact
  (`:capability/proofFormat`, `:capability/type`, `:capability/controller`,
  `:capability/invoker` — evidence of what authorization was presented for
  a given write), not an access-grant list. This document's grant datoms
  are a different thing — a standing, queryable *list* of who currently
  holds a wrapped copy of a graph's DEK — and use a separate `:gftdcojp.
  grant/*` attribute namespace to avoid collision with `:capability/*`.

## Decision

### Recipient identity: a companion X25519 keypair per actor

Per ADR-2607051000's Acceptance (Open question 2, resolved to option (b)):
every gftdcojp actor (Jun, and any future member or service account) holds,
alongside its existing Ed25519 `did:key` identity, one companion X25519 KEM
keypair, generated and persisted the same way `cloud_itonami.edge.cacao`
already persists its Ed25519 identity today (load-or-create-once,
`.gftdcojp/identity.edn`-shaped, never committed to git — same discipline
root `CLAUDE.md`'s "kotoba-server（kotobase.net）" section already mandates
for actor secret keys). The X25519 public key is the only new thing that
needs to be *discoverable* by others (to wrap a DEK to it); it can be
published as a DID document `keyAgreement` entry or simply passed
out-of-band the first time a member is provisioned — this document doesn't
mandate a discovery mechanism, since gftdcojp today has exactly one
candidate member (Jun) and a formal directory is premature.

### Grant datoms: openly fetchable, protected by HPKE not by access control

For each `(graph, recipient, epoch)` triple, assert one grant record:

```
{:gftdcojp.grant/graph        <graph-ipns-name-or-cid>
 :gftdcojp.grant/recipient-did <did:key of the recipient's Ed25519 identity>
 :gftdcojp.grant/wrapped-dek  <base64, = hpke_seal(recipient-x25519-pk, dek)>
 :gftdcojp.grant/epoch        <monotonic uint, per-graph>
 :gftdcojp.grant/granted-by   <did:key of the admin actor who authorized this>
 :gftdcojp.grant/granted-at   <ISO-8601 timestamp>}
```

The key design move: **these datoms do not need to be encrypted or hidden
behind a stricter access-control layer than the rest of the graph.** A
wrapped DEK is safe to be world-fetchable, because HPKE's security
guarantee is precisely that only the holder of the matching X25519 private
key can open it — this is the same principle ADR-2606280300 already named
("secrecy is object encryption, not peer allow-lists") and the same
principle `kotoba-custody`'s HPKE-re-wrapped `GrantedShare` already relies
on. Storing grants in the same plaintext (unencrypted) metadata space
`arrangement`/`kotobase-peer` already uses for wrapper-node bookkeeping
(the `{"schema-version" ... "index-roots" ... "prev" ...}` commit map
ADR-2607051000 already named as out-of-scope-for-encryption) avoids a
chicken-and-egg problem: if grants lived *inside* the encrypted content
tree, a recipient would need the DEK to read the grant that gives them the
DEK. Putting them in the open metadata layer sidesteps that entirely.

The one thing this **does** leak, named honestly rather than assumed away:
the recipient-DID list and grant/epoch history for a graph are visible to
anyone who can fetch blocks — "gftdcojp has N members with standing access,
added/removed on these dates" is coarse membership metadata, in the same
spirit as ADR-2607051000's already-accepted "wrapper-node metadata"
non-goal (schema-version, seq/fold counts). If that membership-shape
leakage itself is unacceptable, the grant list would need its own
encryption-at-a-different-key story this document does not attempt.

### Epoch rotation: membership-driven, forward-only by default

- **Adding a member**: no new DEK needed. Assert one new grant datom for the
  new recipient's X25519 pubkey, at the graph's *current* epoch, wrapping
  the DEK that's already in use. O(1) — no re-blinding, no re-encrypting,
  no disruption to existing data. The new member gets access to the
  tenant's full history, not just future writes, which matches the
  business expectation of "a new gftdcojp team member should see the
  existing private ledger," not a Signal-style forward-secrecy model.
- **Removing a member**: mint a new epoch (`epoch + 1`) with a fresh DEK,
  wrap it via HPKE to every *remaining* recipient's X25519 pubkey (new
  grant datoms at the new epoch), and switch all *new* writes to the new
  epoch's DEK/blind-index key from that point on. The removed member's old
  grant datom is left in place as a historical record (not deleted — this
  is an accretion-only datom log, consistent with everything else in this
  stack) but is simply not re-issued at the new epoch.
- **The asymmetry this creates, stated plainly**: a removed member retains
  the ability to decrypt every block written *before* their removal
  (they still hold a valid wrapped-DEK grant for every epoch that existed
  while they had access), but gains no access to anything written *after*.
  This is the same forward-only tradeoff ADR-2607051000's own Key
  management section already named for `index-root` rotation ("Rotating a
  DEK... means re-blinding... every existing leaf, an O(graph) operation")
  — this document does not change that cost, it just makes explicit that
  **the default revocation is forward-only, not retroactive**, because
  retroactive revocation requires the expensive O(graph) re-encrypt/re-blind
  pass under the new epoch for every historical block, which this document
  treats as an available-but-not-automatic escalation (see Open questions),
  not something every removal triggers.
- **Who can mint an epoch / assert a grant**: gated by a new CACAO
  capability scope, `graph:grant`, distinct from `quad:read`/`quad:write`,
  held only by a small tenant-admin allowlist (today: Jun's own DID — the
  same "operator DID allowlist" shape `kotobase-cljc-worker`'s own
  `KOTOBASE_OPERATOR_DIDS` and ADR-2607022300's fail-closed KV permission
  model already use elsewhere in this superproject). Asserting a grant
  datom is itself an ordinary `quad:write` against the metadata space, but
  additionally requires this capability so that holding write access to the
  tenant's business data does not, by itself, imply the power to add or
  remove readers.

### For-cause revocation: mandatory re-encrypt escalation (resolved 2026-07-06)

Forward-only rotation (above) is the default for routine removal. For a
removal reason the owner designates "for cause," this document requires the
stronger, explicit escalation instead — not automatic on every removal, but
mandatory once invoked:

1. Rotate to a new epoch as usual (new DEK, wrapped only to the remaining
   recipients).
2. Export the graph's live dataset via `hot-datoms`/`datoms` — the same
   mechanism ADR-2607051000's own Migration section already uses for its
   WASM→CLJC-style cutover — using a reader who still holds the *old*
   epoch's DEK/blind-index key.
3. Re-assert the exported dataset into a fresh graph root (new commit-chain
   genesis / new IPNS-published head), encrypted from block one under the
   *new* epoch's DEK and blind-index key.
4. Repoint whatever resolves "the gftdcojp graph" for clients to the new
   root.
5. Unpin and garbage-collect the *old* root's blocks from the block store
   (R2/IPFS) so the old ciphertext is no longer fetchable at all, not merely
   re-keyed.

**A hard limit, stated plainly and not softened**: this revokes access
*through the system* going forward only. If the removed party (or anyone)
already fetched and retained a copy of the old ciphertext while they still
held a valid wrapped-DEK grant, no re-encrypt or block deletion can claw
that back — this is a property of any encryption-based revocation scheme,
not a gap specific to this design. If gftdcojp ever needs a guarantee that
goes beyond "can no longer access it going forward" for a real for-cause
case, that is a legal/organizational response (e.g. device recovery,
employment agreement enforcement), not something this — or any — crypto
design can provide.

This is expensive (full re-transact + block GC, comparable cost to the
O(graph) rotation ADR-2607051000 already named) and is therefore a manually
invoked admin operation triggered by an explicit for-cause designation, not
something every removal runs automatically.

## Consequences

- (+) No new cryptographic primitive to design or review: same X25519 +
  HKDF-SHA256 + AES-256-GCM shape as `kotoba-crypto`'s existing `hpke.rs`
  and the same `epoch`/graph-scoped-grant shape as `kotoba-custody`'s R3
  protocol — this document composes existing, already-tested design
  patterns from elsewhere in the superproject rather than inventing one.
- (+) Grant datoms can live in the open metadata layer without weakening
  confidentiality, because HPKE (not access control) is what protects the
  wrapped DEK — avoids the chicken-and-egg problem of gating access to the
  thing that grants access.
- (+) Adding a member is cheap (O(1), no disruption); removal is bounded
  and explicit (one new epoch, wrap-to-remaining-members only).
- (−) Revocation is **forward-only by default** — a removed member is not
  automatically locked out of historical data. For a small closely-held
  tenant like gftdcojp today this is likely acceptable, but this must be
  understood by whoever operates this before a real "remove for cause"
  scenario happens; the escalation path (full re-encrypt under the new
  epoch) is named but not designed or implemented here.
- (−) This document, like ADR-2607051000 it builds on, is a design, not an
  implementation — no code exists yet for either the base DEK/blind-index
  seam or this recipient-set/grant/epoch layer.
- (−) Introduces a new CACAO capability scope (`graph:grant`) that doesn't
  exist in `kotoba-auth` today and needs its own small implementation
  (attenuation rules, depth-2 delegation interaction with the existing
  `quad:read`/`quad:write` scopes) before it can be enforced.
- (±) Membership metadata (who has standing access, when they were
  added/removed) is visible to anyone who can fetch blocks — an accepted,
  named leak in the same spirit as ADR-2607051000's wrapper-node-metadata
  non-goal, not a silent gap.

## Open questions for the owner (resolved 2026-07-06)

Preserved verbatim as the record of what was open at authoring time; each is
now answered, not still open:

1. **Initial recipient list.** ~~Today gftdcojp has zero bound production
   actors...~~ **Resolved: Jun only, for now.** gftdcojp bootstraps solo —
   Jun's Ed25519 identity and companion X25519 keypair are the first (and
   currently only) recipient. Adding a second member later is the cheap
   O(1) path (Epoch rotation, above), not a redesign.
2. **Retroactive revocation policy.** ~~Should any removal reason... mandate
   the expensive full re-encrypt-under-new-epoch pass...~~ **Resolved:
   forward-only is the standing default; for-cause removal mandates the
   re-encrypt escalation** — see "For-cause revocation" above for the
   concrete re-transact + block-GC procedure and its named exfiltration
   limit.
3. **Where grant datoms physically live.** ~~...the owner may prefer a
   different placement (e.g. inside `cloud-itonami`'s existing `itonami.
   repo/*` data model...)~~ **Resolved: a dedicated `gftdcojp/access-grants`
   graph, not folded into `cloud-itonami`'s tenant data model.** Rejected
   the cloud-itonami-integrated alternative on three grounds: (a) it would
   invert the kotoba/kotobase layering ADR-2607032500 established, making
   the encryption design gftdcojp-specific rather than reusable by any
   future tenant; (b) folding grants into the same tenant-encrypted graph
   as business data just relocates the chicken-and-egg problem into
   cloud-itonami's schema rather than solving it — cloud-itonami would
   still need its own unencrypted sub-space for grants; (c) it would
   conflate cloud-itonami's query-API-level KV permissions with the
   crypto-level wrapped-DEK grant into one data model, risking the two
   drifting out of sync — the same shape of gap ADR-2607022300's
   stale-deployment incident already exposed once.
4. **New capability scope vs. reusing `cloud-itonami`'s KV permission
   model.** ~~Should `graph:grant` be a new kotoba-native CACAO
   capability... or should grant/revoke instead be mediated entirely
   through `cloud-itonami`'s existing KV permission layer...~~ **Resolved:
   new CACAO capability `graph:grant`**, as this document originally
   assumed, for symmetry with the rest of kotoba's CACAO capability model
   and consistency with the access-grants-graph decision above (item 3) —
   both keep this design's authority self-contained in kotoba rather than
   delegated to a tenant-specific KV store.
5. **Implementation sequencing.** ~~Should ADR-2607051000's base seam...
   and this recipient-set layer land in one PR, or should ADR-2607051000
   ship first...~~ **Resolved: ADR-2607051000 ships first, standalone**
   (encrypt/blind seam in `arrangement`/`kotobase-peer`, provable with a
   single dev-only DEK, verified end-to-end on both runtimes), with this
   document's recipient-set/grant/epoch layer implemented as a separate,
   independently-reviewable follow-up PR once the base seam is verified —
   smaller review units over a single combined change.

## References

- ADR-2607051000 (ciphertext-over-CID persistence design) — the per-graph
  DEK, AEAD, and blind-index design this document adds multi-reader access
  on top of; see its "Acceptance" section for how its six Open questions
  were resolved in relation to this document.
- ADR-2607022300 (gftdcojp private tenant) — the tenant this document is
  written for; cites "zero bound production actors" and the stale-deployment
  incident that motivates treating "private" as a cryptographic property,
  not just a declared one.
- ADR-2606280300 (kotoba-rad / kotoba-git sovereign repository layer) — the
  origin of "secrecy is object encryption... capability datom + recipient
  set + epoch key... revocation = epoch rotation" as a design principle for
  this whole superproject; this document is the first place that principle
  gets a concrete mechanism (grant datom shape, HPKE wrap, epoch semantics)
  rather than remaining a name-only R2 placeholder.
- `orgs/com-junkawasaki/kotoba/crates/kotoba-custody/src/protocol.rs`
  (`GrantedShare` — `epoch`, `deal_id`, `graph_cid_mb`, HPKE-re-wrap-per-
  requester) — the closest existing same-superproject precedent for the
  epoch/graph-scoped/HPKE-wrapped-grant shape this document adopts, built
  originally for Shamir custodian share release, not standing multi-reader
  graph access.
- `orgs/com-junkawasaki/kotoba/crates/kotoba-crypto/src/hpke.rs` (`hpke_seal`/
  `hpke_open`, lines 1-45) — the concrete X25519 + HKDF-SHA256 + AES-256-GCM
  wire shape (`ephemeral_pk(32) || nonce(12) || ciphertext`) this document's
  wrapped-DEK format mirrors; not directly reusable from the `.cljc`/Worker
  runtime, but the same algorithm choices carry over.
- `orgs/com-junkawasaki/kotoba/crates/kotoba-server/src/xrpc.rs`
  (`append_auth_capability_datoms`, lines ~2766+) — the existing
  `:capability/*` audit/receipt datom schema this document's
  `:gftdcojp.grant/*` namespace is deliberately distinct from (a receipt
  of what was presented on a tx, not a standing access-grant list).
- Root `CLAUDE.md`, "kotoba-server（kotobase.net）= actor が自分の鍵で CACAO
  を自己発行" section — the actor-holds-its-own-key pattern this document's
  companion-X25519-keypair decision extends rather than replaces.

## One-line summary

**Adds a multi-reader access layer on top of ADR-2607051000's per-graph DEK
for gftdcojp's private tenant: each authorized actor holds a companion
X25519 keypair, the graph DEK is HPKE-wrapped once per recipient into an
openly-fetchable (not access-controlled — HPKE is the protection)
`:gftdcojp.grant/*` datom carrying a graph-scoped `epoch`, adding a member
is a cheap O(1) new grant at the current epoch while removing one mints a
new epoch wrapped only to the remaining members — forward-only revocation
by default, with full re-encrypt-under-new-epoch available as a named but
not-automatic escalation — reusing `kotoba-custody`'s existing epoch/HPKE-
grant shape and `kotoba-crypto`'s HPKE wire format rather than inventing new
primitives, and gating grant/revoke itself behind a new `graph:grant` CACAO
capability scope held by a small tenant-admin allowlist.**
