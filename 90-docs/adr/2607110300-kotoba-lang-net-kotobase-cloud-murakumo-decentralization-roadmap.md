# ADR-2607110300: kotoba-lang / net-kotobase / cloud-murakumo — 「分散型経済/ブロックチェーン」を名乗るための統合ロードマップ

**Status**: accepted（設計のみ。実装は後続タスク）
**Date**: 2026-07-11
**Deciders**: Jun Kawasaki

## Context

2026-07-10 に3リポジトリ横断で成熟度調査を実施した結果、共通パターンが見つかった:
**自己主権ID(CACAO/did:key/IPNS)は実装・テスト済みで本物だが、合意形成・経済的
強制力・複数独立ノードは依然として設計/ADR段階**。具体的なギャップ:

- `kotoba-lang/witness-quorum`: N-of-M閾値署名ロジックは実装+テスト済み(26 tests/
  77 assertions)だが、「書込み後にオペレータ管理下fleetが後追いでcosignする」
  Certificate Transparency型パターンで、**本番ネットワークトランスポートが
  未移植**(post-hoc、pre-commit quorumではない)。
- `kotoba-lang/murakumo/src/murakumo/overlay/`: QUICドライバ・relay・cert等、
  実働する P2P トランスポート層が既に存在する(`quic_driver.clj`/`transport.clj`/
  `dial.clj`/`forward.clj`等、177 tests/811 assertions)。**witness-quorumとは
  接続されていない** — 車輪の再発明を避けられる既存資産。
- `gftdcojp/net-kotobase`: 単一K8s pod(Cloudflare Workerがフロント)の本番SaaS。
  ADR-2607021700で「外部有料テナント0」と自認。CACAOによるテナント別グラフ
  自己主権書込みは実装済みだが、単一運営者インフラの認可用途に留まる。
- `gftdcojp/cloud-murakumo`: **経済フェーズの計画自体はADR-2607030030に既にある**
  (Phase1=単一テナント企業fleet[済]、Phase2=fleet連邦+credits相互運用+overlayの
  QUIC/WebRTC結線[未]、Phase3=公開マーケット+proof-of-compute+chain gateway[未])。
  `pay/core.cljc`の決済実行部は「honest default」で全操作`:hold`止まりの未接続
  実装。
- `kotoba-lang/kekkai`: `governor.cljc`を持つゼロトラストACL(19 tests/66 assertions、
  CLAUDE.mdのActorパターンのGovernor相当)だが、**ネットワーク到達性の統治のみ**
  で価値(資金・信用)の統治権限は無い。
- `kotoba-lang/engi`(ADR-2607101100): 相互信用通貨v1は実装+本番kotobase.netへの
  実ライブ検証済みだが、クロスエージェント検証がkotobase.netの`:public-reads?`
  401制約で機能せず自己申告に後退。解消はADR-2607022600 Wave4(CACAO depth-2
  delegation、`kotoba-lang/cacao`と`kekkai/cacao.cljc`の統合、現状**未着手**)に
  依存すると明記済み。

**本ADRが新たに決定する余地は狭い** — 経済面の大方針(新L1を作らない、
proof-of-computeがPhase3ゲート、Rustを拡張せずcljcで閉じる)は既にADR-2607030030
/ADR-2607071900で確定済みであり、本ADRはそれを覆さない。本ADRの役割は
**identity層(kotoba-lang)・storage層(net-kotobase)・economy層(cloud-murakumo)を
繋ぐ、今まで誰も明示していなかった接続点**を設計することに限定する。

## Decision

既存のPhase語彙(ADR-2607030030)をそのまま使い、そこに識別/台帳/統治の接続を
追加する形で拡張する。

### Phase 1（現状。ほぼ完了 — 変更なし）
自己主権ID(CACAO/did:key/IPNS、実装・テスト済み) + 単一書き込み者の改ざん検知
台帳(`kotoba-ledger-clj`/`kotoba`のhash-chain) + witness-quorumの事後cosign +
net-kotobaseの単一pod SaaS + cloud-murakumoの単一テナントfleet課金。

### Phase 2（本ADRの主眼 — witness-quorumとmurakumo overlayを接続する）

1. **witness-quorumの本番トランスポートを新規実装しない。既存の
   `murakumo/overlay`(QUICドライバ)をそのまま流用する。** 理由: ADR-2607030030の
   Phase2ゲート自体が「overlayのQUIC/WebRTC結線」を要求しており、witness-quorum
   が必要とするトランスポートと完全に同じものを別々に作る必要が無い。
   `overlay/transport.clj`に witness-quorum の提案(propose)/署名収集
   (collect-sig)メッセージ種別を追加するだけで済む。
2. **post-hoc cosignから pre-commit quorum へ変更する。** 書き込み者は
   エントリをコミットする前に witness群へ提案をbroadcastし、M-of-N署名を
   収集してからコミットする。これで「書いてから後追い証明」ではなく
   「書く前の合意」になる。ただし fork-choice/view-changeは対象外(下記
   「やらないこと」参照) — 対立する提案の解決アルゴリズムは無く、witness
   全員が同一fleetの運営者管理下にある間は Byzantine fault tolerance では
   なく crash fault tolerance相当と正直に呼ぶ。
3. **net-kotobaseの単一pod SPOFを解消する。** 同一Cloudflare Worker配下に
   2つ目以降のpodを追加し、テナントグラフのMerkle rootをPhase2のwitness-quorum
   機構で相互署名させる。これは「単一マシン故障への耐性」であり、「複数
   独立運営者による分散」ではない — 運営主体は引き続き1社。
4. **経済台帳(cloud-murakumoのgpu-seconds課金エントリ)も同じwitness-quorum
   機構で証人署名させる。** これによりADR-2607030030 Phase2の「ノード自己署名
   feed」要件を、witness-quorumの再利用という具体的な機構で満たす。identity
   台帳と経済台帳が同じ改ざん検知強度を持つようになる(現状は別物)。
5. **ENGI(ADR-2607101100)のクロスエージェント検証は本ADRでは解決しない。**
   ADR-2607022600 Wave4(CACAO depth-2 delegation)が前提条件のままであり、
   Wave4着手は別タスクとする。

### Phase 3（ADR-2607030030のPhase3を継承・具体化 — kekkaiを価値統治へ拡張）

1. **proof-of-computeの検証者はPhase2で作ったwitness-quorumをそのまま使う。**
   M-of-N witnessがサンプル再計算し、一致/不一致の署名付き verdict を出す
   (ADR-2607030030が既に設計として示した「決定論的等価性によるサンプリング
   再計算+不一致でcredits没収」の具体的な実行機構)。
2. **kekkaiを「ネットワーク到達性の統治」から「価値の統治」へ拡張する。**
   `kekkai/governor.cljc`(既存のGovernor抽象)にslashing verdict(上記1の
   witness-quorum合議結果)を入力させ、`kekkai/acl.cljc`のdeny/allowと同じ
   型で「treasury releaseを許可/拒否」を判定させる。新しいコンポーネントを
   増やすのではなく既存Governorのスコープを広げる形にする。
3. **chain mint/burn gatewayは既存方針どおり(新L1は作らない)。** 変更なし。

### Phase 4（新規 — 本ADRで初めて明示する。実装は伴わない）
Phase 1〜3で作るのは依然として「単一運営者配下の複数ノード」に留まる。
**真に「分散型」を名乗るには、witnessを他社(独立した第三者運営者)に開放し、
その第三者が経済的な参加コスト(stake)を負う必要がある。** これは:
- 設計の形だけ示す: reputation加重witness選定 + stakingボンド(ENGIまたは
  chain gatewayが提供する外部担保、いずれもPhase2/3が前提条件)。
- 紛争解決: 現状どこにも無い「slashing verdictへの異議申立て」機構。kekkaiの
  Governorをさらに拡張する形で置く想定だが、設計は本ADRのスコープ外とし、
  実際に第三者運営者が現れる段階で別ADRを起票する。
- **Phase 4に到達するまでは、対外的に「分散型経済/ブロックチェーン」と
  名乗らない。** Phase 1〜3が完成しても実態は「複数ノードで耐障害性のある、
  自己主権IDと接続された改ざん検知台帳＋経済的支払いレール」であり、これは
  今日より大きく前進した状態だが、まだ「単一運営者への信頼」を前提にしている。

## やらないこと（既存決定を上書きしない）

- 新しいL1・独自コンセンサスチェーンを作らない(ADR-2607030030を継承)。
- witness-quorumの本番化をRust側(kotoba-lattice)で実装しない。cljc/clj
  (murakumo control plane)で閉じる(ADR-2607071900の先例を継承)。
- witness全員が単一運営者配下である間のfork-choice/view-change設計はしない
  (Byzantine前提を持ち込まない — crash fault tolerance相当と呼ぶ)。
- ENGI Wave4(CACAO depth-2 delegation)の実装は本ADRの範囲外。

## Consequences

- Phase2完了時点で「identity台帳」と「経済台帳」が同一のwitness-quorum機構で
  改ざん検知されるようになり、kotoba-lang / net-kotobase / cloud-murakumoの
  3リポジトリが技術的に1本の信頼チェーンで繋がる。
- 新規インフラを増やさず、既存の実働資産(murakumo overlayのQUIC、
  witness-quorumの署名ロジック、kekkaiのGovernor抽象)を接続するだけなので、
  実装コストは相対的に小さい。
- 「分散型経済/ブロックチェーン」という言葉を対外的に使ってよいのは
  Phase 4(独立第三者運営者+経済的skin-in-the-game)以降と明確化される —
  Phase 1〜3の完成をもって早期にその言葉を名乗ることを本ADRは意図的に禁じる。

## Alternatives

- **witness-quorum用に新しいP2Pトランスポートを実装する**: 却下。
  `murakumo/overlay`に実働QUIC実装が既にあり、ADR-2607030030 Phase2の
  ゲート自体が同じ結線を要求しているため、車輪の再発明になる。
- **proof-of-computeとkekkaiの統治を無関係な別システムとして新設する**:
  却下。`kekkai/governor.cljc`という既存のGovernor抽象があるので、それを
  拡張する方がActorパターン(封じ込め+独立Governor+台帳、CLAUDE.md)と整合する。
- **Phase 4(真の分散化)まで一気に設計する**:却下。独立運営者が実在しない
  段階で紛争解決やstaking経済の細部を設計しても検証不能であり、実際に
  第三者運営者の需要が生まれた時点で別ADRとして起票する方が誠実。

## Addendum (2026-07-10): real multi-machine reachability verification

Both Phase 2 (murakumo/witness-quorum, QUIC) and Phase 3
(cloud-murakumo, HTTP) had an explicit honesty gap: "real network
verification across multiple physical machines is deferred — this
sandboxed environment has no Tailscale access to the real fleet."
That gap is now partially closed for the HTTP side:

- Confirmed via `tailscale status` + `bb murakumo nodes`: 6 of
  fleet.edn's 10 real nodes were online at test time (naphtali, judah,
  zebulun, issachar, asher, benjamin), reachable over the actual
  Tailscale mesh from the session host.
- None of the fleet nodes have a real JVM/Clojure/bb installed (only
  macOS's `/usr/bin/java` stub) — `bb murakumo nodes` independently
  confirmed `mesh: absent/stopped` on all of them. Installing a JVM on
  production fleet hardware to run the witness listener THERE was
  judged out of scope for a verification pass (a real provisioning
  action, not a read-only check) — so the session host ran
  `cloud_murakumo.verify.witness-rpc/serve!` (the Phase 3 HTTP
  listener, gftdcojp/cloud-murakumo#13) locally, bound to all
  interfaces, and 5 real fleet machines (naphtali, judah, zebulun,
  issachar, asher) reached it over their real Tailscale IPs via plain
  `curl` (already present on stock macOS, no remote provisioning
  needed) — a genuine cross-physical-machine network hop, not
  localhost.
- Verified both the plain echo RPC and the actual
  `witness-compute-handler` proof-of-compute logic: a request from
  naphtali with a matching recompute claim correctly returned
  `{:verdict :accept}`; a request from zebulun with a deliberately
  mismatched claim correctly returned `{:verdict :reject :reason
  "recompute mismatch: ..."}` — the real slashing signal, produced by
  the real `verify/compute.cljc` logic, delivered across a real
  network hop between two different physical machines.
- No files were written and no software was installed on any fleet
  node (SSH was used only to run stock `curl`); the listener process
  on the session host was killed and its ports confirmed closed after
  the test — no lasting state change anywhere.

**What this does and does not prove**: it proves the wire protocol
(request/response envelope framing, HTTP round-trip, the
proof-of-compute accept/reject logic) works correctly between distinct
physical machines over the real tailnet, not just in a single JVM
process or on localhost. It does NOT establish multi-operator
decentralization — the session host and all 5 fleet machines remain
under the same single Tailscale account/operator (`com-junkawasaki@`),
so this is still Phase 1-3 territory per this ADR's own labeling rule,
not Phase 4. The murakumo/witness-quorum QUIC side (Phase 2) was not
re-verified in this pass (it requires kwik/bouncycastle + cert
material neither present nor installed on the fleet nodes); its real
end-to-end multi-machine verification remains open.

## Addendum 2 (2026-07-10): nbb, not JVM, for the witness-rpc dial client

The first addendum's real-fleet verification defaulted to running the
witness-rpc HTTP listener on the session host and having fleet
machines reach it via stock `curl` — no JVM was installed on any fleet
node, but the *session host's own* side used the JVM
(`cloud_murakumo.verify.witness-rpc`, `.clj`). Owner feedback: that
pattern doesn't establish that fleet nodes themselves could run the
CLIENT side of this protocol without a JVM, and JVM is this org's own
lowest-priority runtime (CLAUDE.md 2026-07-10: kotoba wasm >
clojurewasm > ClojureScript > nbb, JVM/bb demoted to last resort).

Checked: all previously-verified fleet.edn nodes (naphtali, judah,
zebulun, issachar, asher) have Node.js already installed (v22–v26) but
NO JVM at all. `kotoba-lang/murakumo`#18 adds
`murakumo.overlay.witness-dial` (`witness_dial.cljs`) — an nbb
(ClojureScript-on-Node) client speaking the same witness-rpc wire
contract as `witness_http_transport.clj`'s `http-dial!`, runnable via
`npx --yes nbb` with no persistent install. Verified twice against real
hardware:

1. naphtali and zebulun ran the dial logic inline via `npx --yes nbb -e
   ...` against a local witness-rpc server, both `:accept` and
   `:reject`/slashing paths, over the real Tailscale mesh.
2. The exact file that landed in git was `scp`'d to naphtali, run via
   `npx --yes nbb witness_dial.cljs <url> <payload>`, got a correct
   response over the real network, then removed.

No software was installed and no files were left on any fleet node in
either pass.

**What remains JVM-bound, unchanged**: the QUIC transport
(`murakumo.overlay.quic-driver`, kwik/bouncycastle) and
`produce-http-witnessed-attestation` (needs witness-quorum's JVM-only
Ed25519 signer) — neither has an nbb-native equivalent yet. The
witness-rpc *dial* capability specifically no longer requires a JVM
anywhere in its critical path on the client side, and that is now
proven on real fleet hardware rather than asserted from the session
host alone.

## Addendum 3 (2026-07-10): attestation signing ported to nbb too

Addendum 2 closed the witness-rpc dial-only gap for nbb but left
attestation *signing* JVM-bound (needed witness-quorum's JVM-only
Ed25519 signer). kotoba-lang/witness-quorum#3 adds nbb siblings
(`signer.cljs`/`selector.cljs`/`attestation.cljs`, using
`@noble/curves/ed25519`) to the existing JVM files, same public API,
same namespaces (selected by file extension, not `.cljc` -- the
existing per-concern-per-file convention this repo already uses).

Cross-platform compatibility was verified, not assumed: for a fixed
seed and message, the JVM signer, the nbb signer run locally, and the
nbb signer run on real fleet hardware (naphtali, over SSH, cleaned up
after) all produced the **exact same public key and signature bytes**.
`produce-attestation`'s full pipeline (validate -> sign -> format) was
also verified end-to-end under nbb, including independent signature
verification of the result.

**What now works via nbb (no JVM) on a fleet node**: dialing a
witness-rpc endpoint (murakumo#18) AND producing a signed
witness-quorum attestation over the result (this PR). **What remains
JVM-bound**: the QUIC transport (kwik/bouncycastle have no nbb-native
equivalent) and murakumo's `produce-http-witnessed-attestation`
composition itself hasn't been re-pointed at the new nbb signer yet
(it still calls the JVM `attestation/produce-attestation`) -- wiring
an nbb version of that composition is a small follow-up, not done in
this pass.

## Addendum 4 (2026-07-10): full nbb dial+sign pipeline, JVM no longer needed anywhere in it

Addendum 3's follow-up: `kotoba-lang/murakumo`#19 wires the nbb dial
(#18) and nbb attestation signing (`witness-quorum`#3) together into
`murakumo.overlay.witness-dial-attest` — the nbb-native sibling of
`witness_http_transport.clj`'s `produce-http-witnessed-attestation`.
Verified locally end-to-end (real HTTP round trip to a local
cloud-murakumo witness-rpc server + real Ed25519 signing +
independent signature verification), both the `:accept` and
`:reject`/slashing paths.

Building this surfaced a real bug: `witness_dial.cljs`'s top-level
`-main` call fired on every `require`, not just direct CLI use —
composing it as a library re-triggered `process.exit`. Fixed in the
same PR (now invoked via `nbb -m`, verified both paths still work).
Also: nbb's npm module resolution is relative to the entry
script/cwd, not the requiring source file's directory, so murakumo
needed its own `package.json`/`@noble/curves` even though
witness-quorum already had one — each nbb-runnable deployable unit
needs its own copy of npm deps it transitively touches.

**Status now**: the witness-rpc dial-and-sign pipeline runs entirely
on Node.js (nbb), no JVM anywhere in that path, verified on real
fleet hardware for the dial and signing halves separately (addenda
2-3) and end-to-end locally (this addendum). What's still JVM-bound
and unchanged: the QUIC transport (kwik/bouncycastle, Phase 2) has no
nbb-native equivalent yet.

## Addendum 5 (2026-07-15): 「やらないこと」項目1・項目3、ENGI/ENスコープに限定して撤回

ADR-2607993000(engi-l1-byzantine-consensus-en-currency)により、本ADRの
「やらないこと」節の以下2項目は **ENGI/EN というスコープに限定して**撤回された:

> - 新しいL1・独自コンセンサスチェーンを作らない(ADR-2607030030を継承)。
> - witness全員が単一運営者配下である間のfork-choice/view-change設計はしない
>   (Byzantine前提を持ち込まない — crash fault tolerance相当と呼ぶ)。

オーナー判断(2026-07-15): 単一運営主体のままでも、コンセンサスアルゴリズム
自体は今から Byzantine 耐性ありで設計し、実際に第三者 witness が参加する段階で
プロトコルを書き直さずに済むようにする。`kotoba-lang/engi` を chained HotStuff
型 BFT の L1 に、新規 `kotoba-lang/en` をその上のネイティブ通貨単位にする設計・
最小プロトタイプは ADR-2607993000 参照。

**温存される部分**(本Addendumでも変わらない): 運営主体は引き続き単一
(`com-junkawasaki`)で、Phase 4(独立第三者運営者+経済的stake)に到達するまで
外部に「分散型経済/ブロックチェーン」と名乗らないという本ADRの命名規律は不変。
witness-quorum の本番化を Rust 側で実装しない方針、ENGI Wave4(CACAO depth-2
delegation)が本ADRの範囲外である点も不変。「やらないこと」の項目2(Rust実装
禁止)・項目4(Wave4範囲外)はそのまま有効。

## Addendum 6 (2026-07-15): Phase 4 を ENGI/EN witness admission に限定して具体化(ADR-2607994000)

Addendum 5 が撤回した「Byzantine前提を持ち込まない」の続き — オーナー
指示「com-junkawasaki 単一でなく分散型が成立するように設計して」を受け、
Phase 4(本ADR §「Phase 4」— 独立第三者運営者+経済的stake、従来「shape
only、着手する場合は別ADR」としていたもの)を ADR-2607994000 が具体化した:
permissionless staking(外部担保bond、EN自体ではない — ADR-2607101100 §4の
既存のUSDC on Base L2境界を想定)、stake-weighted quorum、equivocation
限定のスラッシング(異議申立て機構を新設せずに済む設計)。詳細は
ADR-2607994000 参照。

**Phase 4 の到達条件は不変**: 実際に独立した第三者組織が bond して witness
として参加するまでは、本Addendumの設計が存在しても実質的な集中度は
変わらない(Day1時点で bond witness は com-junkawasaki 1者のみ)。
「分散型経済/ブロックチェーン」と対外的に名乗ってよいのは、実在する
独立第三者が bond し、それが本ADRの命名規律の意味での Phase 4 に達した
と確認できてから。
