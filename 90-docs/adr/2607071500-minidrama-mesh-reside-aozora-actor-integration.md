# ADR-2607071500: minidrama mesh reside 配線 — aozora actor の profile×deploy 統合の最初の実配線

**Status**: accepted (implemented)
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki（指示: aozora actor として profile 作成・deploy できる統合の「設計、実装」）
**Scope**: `orgs/etzhayyim/com-etzhayyim-minidrama`, `orgs/kotoba-lang/murakumo`

## Context

ADR-2607022300（3-substrate topology）は actor の3 facet 登録
（`pin`=kotoba / `reside`=murakumo / `identify`=aozora）を設計したが、
aozora creator actors（ADR-2607071300）については identify 側だけが進み、
**reside（murakumo mesh への常駐）はどの aozora actor too 配線されていなかった**
（murakumo.app.edn に aozora 系 entry なし）。「actor が常駐し、公開されて
p2p/http でアクセス・通信できて、profile 作成・deploy が構成に基づいて行える」
という統合の後半が gap だった。

minidrama をパイロットに選ぶ理由: R0 scaffold 済み（ADR-2607071300 追記）、
CACAO 鍵付き identity（`minidrama.cacao` の `load-or-create-identity!`、
tashikame 同型）と実 aozora Publisher が同日 `b07e2e5` で着地済み、さらに
announce レグ（mp4 → uploadBlob → `app.aozora.embed.video` post を actor 自身の
did:key で createRecord、本番 /videos で E2E 実測）が `93e73ec8` で着地 —
**identify facet が実質完了している唯一の creator actor** であり、残るは
reside のみだった。

## Decision

### 1. kenchi-valuation 同型の「split of duties」で ON-MESH surface を切る

actor 本体（DramaLLM ⊣ DramaGovernor、生成、publish）を mesh に載せるのは
**しない**。governor gate・LLM・PDS 認証は off-mesh の JVM actor に残し、
mesh には検閲対象にならない identity/liveness の2 component だけを置く:

| component | trigger | 役割 |
|---|---|---|
| `drama-profile` | `on-http /minidrama/profile` | actor の identity record（handle `minidrama.aozora.app` / did:web / registry）を応答し、profile datom を assert |
| `drama-heartbeat` | `on-tick`（1h） | resident-liveness datom を append（fleet Datom log に as-of 履歴）＋ identity anchor の再 assert |

これで actor は **常駐**（LaunchAgent 常駐の kotoba-server が host）、
**p2p**（gossipsub lattice の auction が配置・再配置、KSE/graph で通信）、
**http**（mesh routes 経由で on-http 応答）を得る。kenchi の先例
（ADR §6 split: ToS 制約入力は off-mesh、公開 derived record のみ on-mesh）と
同じ理由で、minidrama も「governor が拒否しうる能力は mesh に持ち込まない」。

### 2. 実装（着地済み）

- **`etzhayyim/com-etzhayyim-minidrama`**（merge `fc6b0c78`、pin は announce
  レグ込みの `93e73ec8` へ前進）:
  - `mesh/minidrama.app.edn` — KOTOBA Mesh app manifest（2 components、
    placement `{:spread :zone :require {:tier "edge"}}`）
  - `mesh/drama_profile.clj` / `mesh/drama_heartbeat.clj` — kotoba-clj guest
    （bare ns + `kqe-assert!`/`kqe-query` host imports のみ、JVM interop なし）
  - `test/minidrama/mesh_manifest_test.clj` — manifest shape tests（EDN parse /
    `:src` 実在 / trigger 型 / placement）。lint 0/0、20 tests / 69 assertions
- **`kotoba-lang/murakumo`**（merge `8861d89b`）: `murakumo.app.edn` に
  fleet app `minidrama` を登録（`:manifest
  "../../etzhayyim/com-etzhayyim-minidrama/mesh/minidrama.app.edn"`、
  `:replicas 1`、edge/jp placement）。nbb test 176/810 green。
- **検証**: 両 guest とも `kotoba component build`（kotoba-runtime の wit、
  `kotoba-component` / `kotoba-cron` world）で実 WASM component にコンパイル:
  - drama-profile: `bafyreic2knpq3reapdplo4fqp3iztkydaubxxt7fr2pfzop7pffbdw6wci`
  - drama-heartbeat: `bafyreicafih2mt2k2cnmkhd73zemrrywiaov5vnmqfrxtlngibcuvx466u`

### 3. これで閉じた統合ループ（minidrama = 3 facet の実例）

```
identify (aozora):  CACAO did:key 自己発行 → profile/announce を自分の鍵で createRecord   ✅ b07e2e5 / 93e73ec8
reside   (murakumo): mesh guests + murakumo.app.edn 登録 → nbb reconcile の宣言的管理下     ✅ 本 ADR
pin      (kotoba):  guest は CID で content-addressed（component build が CID を発行）     ✅（deploy 時に pin）
```

## Consequences / Follow-ups

- 実 fleet への配置は operator 実行: `nbb murakumo deploy
  ../com-etzhayyim-minidrama/mesh/minidrama.app.edn <node>`（1回）または
  `nbb reconcile murakumo.app.edn --apply`（収束）。配置後、観測 CID を
  murakumo.app.edn の `:cid` に記入して match-without-rebuild を有効化する。
- 本 ADR の CID はローカル build の実測値。deploy 時の CID と一致することを
  確認して `:cid` に採用する（prelude/コンパイラ更新で変わりうる）。
- aozora 側 registry（`aozora.appview.creator-actors`）の `:keyed? false` →
  `true` への flip は、PDS 側 account/did 昇格（createAccount 経路）と合わせて
  別 follow-up（ADR-2607070400 系列）。
- 他の creator actors（animeka / dougaka）・work actors への横展開は本 ADR の
  型（mesh/<name>.app.edn + murakumo.app.edn entry + shape test）をそのまま
  複製する。

## Related

- ADR-2607022300（3-substrate topology）: reside/identify/pin の設計原典。
- ADR-2607071300（creator actors + minidrama 設計）: パイロット actor の設計と
  R0/identify 側の着地記録。
- ADR-2607071400（murakumo family positioning）: 本配線が乗る control plane の
  ポジショニング（wadm 型 reconcile、auction placement）。
- kenchi mesh surface（`orgs/kotoba-lang/kenchi/mesh/`）: split-of-duties の先例。

## 追記 (2026-07-07): 実 fleet 配置 + E2E 実証完了（follow-up ①②）

- **配置**: operator seed（1Password「Murakumo Operator Seed」）で
  `nbb deploy …/mesh/minidrama.app.edn asher` を実行。両 component の CID は
  ローカル build と**完全一致**（決定的ビルド）。desired datom 10 件 +
  PutRoutes(http=1) が control graph に書かれ、asher に install。
- **E2E 実証**: `POST http://asher:8077/mesh/http/minidrama/profile` →
  **HTTP 200** で actor identity record（handle/did/registry/role）が
  WASM component から応答（GET は 405 — mesh on-http は POST 契約）。
- **:cid 記入**: murakumo.app.edn に representative CID（drama-profile）を記録
  （murakumo `84235dce`）。`nbb reconcile --dry-run` が
  **`minidrama 1 desired / 1 running / satisfied on asher`** を報告 —
  宣言的ループ（desired ↔ observed）が閉じた。
- 運用注: 配置は canary (asher) 1 replica。fleet 全体の常駐化・replicas 増は
  `nbb reconcile --apply` / `--watch` の運用判断に委ねる。

## 追記 2 (2026-07-07): 宣言済み desired state の fleet 収束 — kenchi 配置 + manifest 修理

minidrama で閉じた reside 経路を、murakumo.app.edn に宣言されたまま
`:needs-build`(running 0) で放置されていた他 app に適用した。

- **kotoba-clj reader は regex literal (`#"…"`) 非対応と判明**(kenchi guest の
  deploy で発覚。escape 文字列・`defn-`・`subs`/`count` は可)。kenchi
  `valuation_query.clj` を subs ベースに書き換え(kenchi `7ae1af3b`)、両 guest の
  CID 解決 → asher へ deploy →
  `POST /mesh/http/kenchi/valuation` **HTTP 200** 実測。
- **murakumo.app.edn の 3 manifest パスが broken だった**(murakumo `386e8d2d`
  で修理): kotodama-bot `../kotoba` → `../../com-junkawasaki/kotoba`(examples は
  com-junkawasaki 側)、kenchi `../kenchi-actor` → `../kenchi`(rename)、live-ui
  は `apps/live-ui.edn` 自体が不在(注記のみ、honest desired state のまま)。
- **kotodama-bot は deploy 済みだが `:cid` は意図的に未記録**: example manifest
  の `:links` が placeholder CID(`bafyGemmaModelCid` 等)のままで on-http が
  502(実 model CID + CACAO grant が入るまで収束不能)。
- reconcile 現況: `minidrama 1/1 satisfied` / `kenchi-valuation 1/2 place`
  (2nd replica の `--apply` は operator seed の 1Password 再認証待ち。planner の
  提案先 benjamin は mesh 未設置のため、実配置は auction の生き bidder に決まる)。

## 追記 3 (2026-07-07): 収束完了 — kenchi 2/2 satisfied + reconcile --apply の bare-filename バグ修理

- `reconcile --apply` が **bare filename で FileNotFoundException**
  (`murakumo.app.edn/../kenchi/…`) — `deploy/plan.cljc` の `manifest-dir` が
  slash なしパスを素通ししていたバグを発見・修正（murakumo `5e721ed9`、
  bare filename → "."。test 期待値も更新、176/810 green)。
- kenchi の 2nd replica は cross-node auction 未配線(ADR-2606271600 既知
  ギャップ)のため gossip では収束せず、`nbb deploy … zebulun` の imperative
  配置で充足。zebulun 上で `POST /mesh/http/kenchi/valuation` → HTTP 200 実測。
- 最終状態: **kenchi-valuation 2/2 satisfied on asher,zebulun /
  minidrama 1/1 satisfied on asher**。kotodama-bot / live-ui は設計どおり
  needs-build(placeholder :links / manifest 不在)。
