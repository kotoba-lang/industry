# ADR-2607092700: club-shinshi/net-babiniku の決済主体を Shinshi Inc（JK株式会社）へ、@etzhayyim/sdk 決済表面を kotoba-lang/pay 共通ライブラリへ

- **Status**: accepted（2026-07-09 オーナー指示: 「club-shinshi は etzhayyim じゃなくて
  shinshi inc にして。@etzhayyim/sdk は kotoba-lang repo で共通ライブラリにしなおして」）
- **Supersedes**: ADR-2607071000 の Decision #1（etzhayyim レール消費）と Decision #4
  （vendor→etzhayyim crossover 提案）。同 ADR の lexicon shape・client-side signing・
  verify-before-honor の各不変条件は**継承**する。
- **Related**: ADR-2607062200（babiniku creator monetization / honest-default）、
  ADR-2607011940（base-l2 の etzhayyim-sdk → kotoba-lang relocation 前例）、
  ADR-2607051621（kotoba-lang/treasury 抽出）、ADR-2607062300（club-shinshi /
  net-babiniku の jk-luxury org transfer）、ADR-2605220000（shinshi PSP 凍結）

## Context

club-shinshi（gftd 唯一の `:live` 収益 = ExoClick ad）と net-babiniku（chat 稼働、
monetization は HARD-hold）の creator 課金（tip / subscribe / PPV）解禁は、
ADR-2607071000 で「vendor アプリが etzhayyim の Base L2/USDC レールを
`@etzhayyim/sdk` 経由で消費する」設計だった。しかしこの設計には構造矛盾がある:

1. **教義の不整合**: etzhayyim の substrate boundary（`orgs/etzhayyim/root/CLAUDE.md`）
   は payment purpose として `donation`/`kisha`/`grant`/`tithe` 系のみを許可し、
   **外部向け `tip` / `subscription` / `purchase` を明示的に禁止**している。creator
   課金はまさにその禁止対象で、etzhayyim レールは本件の正しい家では最初から無かった。
2. **未承認の crossover**: vendor→etzhayyim の消費パターンは ADR-2605211950 に
   worked example が無く、etzhayyim 側レビュー待ちのブロッカーになっていた。
3. **実装の空洞**: `@etzhayyim/sdk` は `pay()` のみ実装、`payStream` /
   `splitDistribute` / `verify()` は stub。特に `verify()` 不在は entitlement 付与の
   前提を欠き、全 monetization が HARD-hold のままだった。

一方、SDK からの決済部品の kotoba-lang への relocation は既に前例と部分実装がある:
`kotoba-lang/base-l2`（ADR-2607011940 で etzhayyim-sdk から relocate。RPC +
ERC-4337 paymaster）、`kotoba-lang/treasury`（ADR-2607051621。USDC quote /
**on-chain 検証** / append-only ledger — `verify()` 相当）、`kotoba-lang/wallet`
（non-custodial signing）、`kotoba-lang/kessai`（fiat ISO レール抽象、兄弟）。

## Decision

1. **決済・運営主体は JK株式会社（"Shinshi Inc."、GitHub org `jk-luxury`）。**
   club-shinshi / net-babiniku は 2026-07-06 に jk-luxury へ transfer 済み
   （ADR-2607062300）であり、creator 課金の事業主体・PSP/AML/風営法対応・
   platform take（80/20 の 20）の帰属はすべて JK株式会社側。etzhayyim は本件に
   関与しない（donation-only レールを自陣に保持するのみ。変更不要）。

2. **`@etzhayyim/sdk` の決済表面を `kotoba-lang/pay` として共通ライブラリ化**
   （public、Apache-2.0）。pure `.cljc`・network I/O ゼロ・鍵保持ゼロの
   kotoba-lang 層契約（base-l2/treasury と同型）で、`pay.ts` の
   pay / payStream / splitDistribute / escrow 表面を移植:
   - 純関数: USDC 単位（parse/format）、streaming flow-rate、80/20
     `creator-platform-split`（端数は creator 側）、monetization capability
     検証（ADR-2607071000 の lexicon shape を中立化）、receipt、
     **`entitle` = verify-before-honor 判定**。
   - 注入座席: `PayRail` protocol。on-chain 実効は host が base-l2（転送 +
     ERC-4337）/ treasury（検証）/ wallet（署名）で裏打ちする。
   - honest default: `unprovisioned-rail` は全操作を
     `:pay/unprovisioned-capability` で HOLD（babiniku の
     `UnprovisionedCapability` パターン踏襲。決済を偽装しない）。
   - 初版実測: 7 tests / 49 assertions green、clj-kondo errors 0。

3. **chain スタック自体は変更しない**（Base L2 / USDC・EURC・JPYC /
   passkey smart wallet / 署名は payer デバイス側）。変わるのは**運営主体と
   コードの家**であって暗号学的レールではない。platform-held private key の
   禁止も従来どおり維持する。

4. **経済モデルへの帰結（逆トポロジーソートの B3 ゲート更新）**: creator 課金
   解禁のクリティカルパスは「etzhayyim 側レビュー + SDK stub 実装待ち」から
   「**kotoba-lang/pay の rail adapter 実装 + treasury 検証の配線**（Shinshi Inc
   側で完結）」に変わり、外部組織依存が消える。着火順は
   ①shinshi（ad :live → tip 解禁）→ ②babiniku（tip/subscribe）→ 決済 GMV が
   murakumo（生成 GPU）/ net-kotobase（storage）需要へ→ kotoba/kotobase
   （identity/可搬性層）に沈殿、で不変。

## Consequences

- club-shinshi の canvas「準備 (club-shinshi-creator-take): PSP/crypto rail 解禁」の
  参照先は etzhayyim settlement から kotoba-lang/pay + JK株式会社運営に変わる。
  business-model md は生成物のため手編集せず、`gftd canvas note` 経由の更新を
  follow-up とする。
- ADR-2607071000 が提起した etzhayyim 側レビュー（vendor→etzhayyim crossover）は
  **不要になった**（crossover 自体が消滅）。
- follow-up: (a) Superfluid / 0xSplits / Safe-escrow adapter の実装、
  (b) treasury 検証を `entitle` の verification 入力に配線した最初の消費者
  （club-shinshi tip が最短）、(c) `net.babiniku.monetization.capability` lexicon の
  中立 NSID 化の要否検討、(d) jk-luxury 側 repo（club-shinshi / net-babiniku）への
  deps 配線。
- `@etzhayyim/sdk` 本体（atproto / encrypted / ipfs 等の非決済表面）は本 ADR の
  スコープ外（従来どおり etzhayyim 内部用。決済表面のみ中立化）。
