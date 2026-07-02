---
id: adr-2607022300-actor-deploy-3-substrate-topology
title: "ADR-2607022300: actor deploy 3-substrate topology — kotoba=storage / murakumo=compute / aozora=publish + unified `e7m actor` CLI"
status: accepted
doc_type: adr
topic: substrate-architecture
authoritative: true
last_verified: 2026-07-02
authoritative_for:
  - "etzhayyim actor の deploy 先は3 substrate だが、それらは対等な「deploy 先」ではなく**3つの別の関心事**(storage / compute / publish)を担う。aozora は actor が走る compute 基盤ではなく publish 先＋identity 登録場所。"
  - "依存は厳密なレイヤリング: author → kotoba(storage) → {murakumo | browser-WASM}(compute) → aozora(publish)。循環なし。3 substrate の登録は互いに独立(idempotent)。"
  - "actor の3 facet 登録を `bb e7m`(ADR-2606222000 consolidation)の下で1名詞に統合: `e7m actor {mesh, pin(=kotoba), reside(=murakumo), identify(=aozora), deploy --all}`。既存 `bb aozora:deploy`=`identify`、`bb kotobase:pin`=`pin`、`bb murakumo:deploy`=`reside` をリネーム統合。"
  - "2つの実行会場(browser-WASM=user主権的 / murakumo kototama=communal 常駐)はどちらも kotoba から CID で同一物を具現化し、どちらも aozora へ publish する。venue が publish の署名帰属を分ける(WASM=user鍵、kototama=actor did + member CACAO leash)。"
related:
  - 90-docs/adr/2606222000-*.md                              # bb e7m consolidation (the integration target)
  - 90-docs/adr/2607022200-com-etzhayyim-tashikame-factcheck-aozora-actor-r0.md
  - 90-docs/adr/2607022210-com-etzhayyim-kouhou-public-info-actor-r0.md
  - orgs/etzhayyim/root/70-tools/src/etzhayyim/aozora_deploy.cljc   # aozora identify (shipped)
  - orgs/etzhayyim/root/50-infra/murakumo/fleet.edn                # murakumo reside target
  - orgs/gftdcojp/net-kotobase/kotoba/                            # kotoba storage CLI
  - orgs/etzhayyim/root/70-tools/src/etzhayyim/kotoba_rad.cljc     # RAD identity (storage-side)
---

# ADR-2607022300: actor deploy 3-substrate topology — kotoba=storage / murakumo=compute / aozora=publish + unified `e7m actor` CLI

**Status**: accepted (design; per-facet maturity varies — see §Registration maturity)
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

オーナーが tashikame/kouhou の live aozora 投稿(deploy)を進める中で、**3 substrate CLI**(kotoba / murakumo / aozora)の**依存関係の美化**を問うた。実態(調査):

- **kotoba** = Rust server CLI(`gftdcojp/net-kotobase/kotoba`: serve/block/quad/sparql/q/cypher/media/commit)。content-addressed Datom log + IPLD block + pin。browser-WASM 実行基盤(kotoba-clj compiler + kotoba-runtime + IdbBlockStore)は**部品はあるが actor deploy に未結線**(gap)。
- **murakumo** = bb tasks のみ(`bb murakumo:deploy --node`, `bb fleet:probe`)。10-node fleet + EVO-X2 inference(Ollama/LiteLLM)。lite_runner は issachar で LIVE、full kotodama cell は R0 scaffold。**kototama = kotodama cell を murakumo に常駐させた形**(用法であり別 codebase ではない)。
- **aozora** = `bb aozora:deploy <name> [--apply]`(shipped)。actor の profile record(rkey=self)+ did.json + lexicon を aozora.app PDS に createRecord。dry-run 既定、`--apply` + `LEASH`(member CACAO)で実効。
- 統合 CLI は `bb e7m`(ADR-2606222000 consolidation)に集約中。既存 e7m(TS)・etzhayyim-cli(Go)はレガシー。

オーナーの素描:「actor → kotoba deploy → IPLD 永続化+pin → browser WASM(user 実行)」「kototama は murakumo deploy で常駐・分散 inference」「aozora cli にも deploy できるが適切か?」。

## Decision

### 1. 3 substrate は対等な「deploy 先」ではない — 3つの別関心事

| substrate | 関心事(単一) | actor の何を登録 | 実行主体 |
|---|---|---|---|
| **kotoba** | storage(content-addressed Datom + IPLD + pin) | code + state(CID で検索可能) | なし(在り処) |
| **murakumo** | compute(常駐)(fleet + 分散 inference) | kototama(常駐 Pregel cell) | ここで走る(fleet 側) |
| **aozora** | publish/social(ATProto PDS) | identity(did + profile + lexicon) | なし(発話の宛先) |

**aozora は actor が走る場所ではない。**「aozora に deploy する」は**identity 登録(1回・静的)**のこと solely。actor を aozora で走らせるのはカテゴリエラー。これは kotoba の pin、murakumo の reside と**対称な「登録」操作の1つ**にすぎない。

### 2. 厳密なレイヤリング(循環なし)

```
AUTHOR (.cljc) ──mesh──▶ KOTOBA(storage: pin code+state by CID)
                           │  materialize by CID
                ┌──────────┴───────────┐
                ▼                      ▼
        EXECUTE 常駐                EXECUTE user側
        murakumo kototama          browser WASM (主権的)
        (分散 inference)           (IdbBlockStore)
                │                      │
                └───── both publish ───┘
                            ▼
                        AOZORA(publish: createRecord)
                        identify: did+profile 登録(1回)
```

依存の向き: **author → kotoba → {murakumo | wasm} → aozora**。aozora は実行時に何にも依存しない(純 sink)。3つの登録は互いに独立(idempotent)。

### 3. 2 実行会場の双対

- **browser-WASM**(user 主権的): pinned code を CID で検証しつつ user 端末で実行 = no-server-key の純形。
- **murakumo kototama**(communal 常駐): fleet で常駐・分散 inference・cron。
- 両者とも**kotoba から同一物を具現化**し、**aozora へ publish**する。
- 帰属の差: WASM からの publish は **user の鍵**、kototama からは **actor did + member CACAO leash**。これが aozora 側の署名帰属を分ける。

### 4. 統合 CLI(`bb e7m` の下、ADR-2606222000)

3 facet を1名詞 `actor` の下に、各関心事=1動詞で揃える(既存 task のリネーム統合):

```
e7m actor mesh      <name>            # .cljc → kotoba.app.edn(共通前段)
e7m actor pin       <name>  # kotoba  # code+state を IPLD pin(= bb kotobase:pin)
e7m actor reside    <name>  # murakumo# kototama を fleet に schedule(= bb murakumo:deploy)
e7m actor identify  <name>  # aozora  # did+profile+lexicon を PDS 登録(= bb aozora:deploy)
e7m actor deploy --all <name>         # 上3つを冪等に一括
e7m actor post      <name> …          # runtime publish(実行会場から。別件)
```

「aozora cli で 1,2,3 を deploy」= **`e7m actor identify`**(= 既存 `aozora:deploy`)が (1) PDS did/profile 登録・(2) `LEASH` member CACAO・(3) createRecord 認証を担う。**既に8割 shipped**。残る gap は child-repo actor が manifest 形式を持たない点と(2)の member 鍵のみ。

## Registration maturity(実態)

| facet | 動詞 | 既存 | 成熟度 |
|---|---|---|---|
| kotoba pin | `pin` | `bb kotobase:pin` / `actor:mesh` | 🟡 土台あり・actor 用 pin CLI 未整理 |
| murakumo reside | `reside` | `bb murakumo:deploy --node` | 🟡 lite_runner LIVE・full kotodama cell は R0 |
| aozora identify | `identify` | `bb aozora:deploy [--apply]` | ✅ shipped |
| browser-WASM 実行 | (具現化) | kotoba-clj + IdbBlockStore あり | ❌ `e7m actor build-wasm` 結線が gap |

## Consequences

- (+) 「3 substrate に deploy」が**関心事別の3登録**として整理され、aozora=compute の誤謬が消える。新 actor は `e7m actor deploy --all` で3 facet 冪等登録→実行会場を選ぶ、の一本道。
- (+) 実行会場の双対(user-WASM / fleet-kototama)が**同一 kotoba 依存**で両立できる。publish 帰属も venue で自明に定まる。
- (+) 既存 `bb e7m` consolidation(ADR-2606222000)の下に3 facet が自然に収まる。新 CLI binary 不要(bb task のリネーム)。
- (−) `pin` / `reside` facet は未整理・R0。`identify` は shipped だが child-repo actor の manifest wiring が必要。
- (−) browser-WASM 実行は部品揃いだが `e7m actor build-wasm` の結線が未完(WASM を第一級実行会場にするかは別 ADR/follow-up)。
- (−) live aozora 投稿(outward)は `LEASH`(member 鍵)と PDS への actor repo 存在に依存し、本 ADR の設計上は `identify` 実行で解決するが、鍵そのものは owner 由着(no-server-key)。

## Alternatives considered

- **3 substrate を対等な peer deploy 先とする** — 拒否。storage/compute/publish の関心事混同を生み、aozora で actor を走らせる誤謬に至る。
- **単一 substrate に統一** — 拒否。主権的 user-WASM と communal fleet 常駐の双対、および storage/compute の分離(CID 検証可能性)を失う。
- **独立 CLI binary を3つ(kotoba/murakumo/aozora)新設** — 拒否。`bb e7m` consolidation(ADR-2606222000)と衝突し、統合 CLI が乱立する(現状の e7m TS / etzhayyim-cli Go の二重化の再発)。3 substrate は `e7m actor` の下の動詞として統合。

## Notes

- 本 ADR は拓樸・関心事分離・CLI 命名を固定する。各 facet の深掘り(pin CLI 整理、full kotodama cell、`build-wasm` 結線)は各々の follow-up ADR/PR に委ねる。
- tashikame/kouhou の manifest wiring(`identify` 対応)は直近の具象実装として本 ADR の直下で進める。
