# ADR-2607023000: creative -ka のレイヤ分解 — コードは kotoba-lang、職能は cloud-itonami-isco、商売は gftdcojp、manimani は配信面

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

gftdcojp に creative 系 -ka（職能名）アセットが5つある: `ai-gftd-animeka`
(アニメ) / `ai-gftd-mangaka` (漫画) / `ongakuka` (音楽) / `ai-gftd-syosetsuka`
(小説) / `ai-gftd-dougaka` (動画)。これらを kotoba-lang に公開するか、gftdcojp
private のままにするか、cloud-itonami で ISIC/ISCO のように産業化するか、
manimani の actor にするかが論点だった。

実態調査（2026-07-02）:

- 5つとも langgraph 型 governor actor ではなく **NSID→handler の HTTP actor**
  （leaf app）。どこからも dep として消費されていない（依存方向は
  -ka → kotoba-lang libs の一方通行）。
- `ai-gftd-mangaka`（5.8k LOC）は汎用部分の kami-mangaka-\*-clj / kami-genko
  分離が既に先行（ADR-2607010930 / ADR-2607020300）。data/ が 175MB。
- `ai-gftd-animeka`（1.9k LOC）: `domain.cljc`（cut ヒエラルキー・12工程・
  schema）と `graphs/generation.cljc` の layer-specs は作品非依存の語彙。
- `ongakuka`（150 LOC）: **server の無い純 lib**（compose/policy/catalog）。
  独立 repo ではなく superproject 直下に plain tree として tracked。カタログ
  実体（DOVA/incompetech 由来、チャンネル roster 付き）は事業データ。
- `ai-gftd-dougaka`（400 LOC）: 汎用は `ffmpeg_plan.cljc`（純関数の
  timeline→render plan + ffmpeg command builders）のみ。
- `ai-gftd-syosetsuka`: handler 全部 scaffold stub。抽出対象がまだ code に無い。
- 別途、ほぼ空の `ai-gftd-dogaka`（CLAUDE.md のみ）が dougaka と並存。

## Decision

3択ではなく **1 repo を 3 レイヤに分解**する。org taxonomy
（ADR-2606302300）と visibility SSoT（ADR-2607021330）にそのまま乗る:

| レイヤ | 置き場所 | 内容 |
|---|---|---|
| 技芸 (craft) | kotoba-lang (public) | 作品非依存の汎用 CLJC lib |
| 職能 (occupation) | cloud-itonami-isco-{code} (public, AGPL) | ISCO-08 blueprint + occupation registry entry |
| 商売 (business) | gftdcojp (private) | actor 本体・プロンプト・カタログ・serving・データ |

**manimani はホスト候補から除外**: manimani / cloud-manimani はモバイル app +
triage Decision Ledger Worker であり、これら actor の配信面（クライアント）で
あってホストではない。

**命名規約: 職能名（-ka）は actor に残し、技芸名を lib 名にする。**
animeka→`anime`、ongakuka→`ongaku`、dougaka→`douga`（mangaka の技芸は既存の
kami-genko / kami-mangaka-\* が該当。syosetsuka の技芸 lib は将来 `shousetsu`
等で分離）。

### Per-repo mapping

| -ka (職能, gftdcojp) | craft lib (kotoba-lang) | ISCO-08 (cloud-itonami-isco) |
|---|---|---|
| ai-gftd-animeka | `anime` — cut ヒエラルキー(work→episode→scene→cut)、12工程、layer-specs、schema、DID/NSID をパラメタ化した identity helpers | 2166 Graphic and Multimedia Designers（既存 repo。animation を包含） |
| ai-gftd-mangaka | 既存 `kami-genko` + `kami-mangaka-{page,text,scene,render,reader}-clj`（新規 lib は作らず既存に寄せる） | 2651 Visual Artists（漫画家を包含） |
| ongakuka | `ongaku` — BGM 選定スコアリング + ライセンスポリシー gate（render-only / no raw / no AI-training / no Content-ID）。カタログ実体は private に残す | 2652 Musicians, Singers and Composers |
| ai-gftd-syosetsuka | （将来。まだ code に domain が無い） | 2641 Authors and Related Writers |
| ai-gftd-dougaka | `douga` — timeline→ffmpeg render-plan 純関数 + command builders | 2654 Film, Stage and Related Directors and Producers |

## What was done (2026-07-02)

1. **kotoba-lang/{anime,ongaku,douga} を新規作成**（public, Apache-2.0,
   deps.edn + .cljc + cognitect test-runner。テスト green: anime 66 /
   douga 23 / ongaku 15 assertions）。ongaku の catalog reader は legacy
   `:ongakuka.catalog/assets` キーも読む（移行互換）。west 登録済み
   （repos.edn :extra-projects + `gen-west-manifest.bb --entry` 最小 diff、
   ADR-2607022900 のサーバ側 pin 検証経由）。
2. **cloud-itonami-isco-{2641,2654} を新規公開**（2166 テンプレ同型:
   README / blueprint.edn / business-model / operator-guide / AGPL +
   governance 一式。`:itonami.blueprint/craft-libraries` キーを追加し
   craft lib を逆参照）。**2651 / 2652 は scaffold + local commit 済みだが
   公開は権限ゲートで保留**（公開リポ化の事前確認ガードレール。オーナーの
   `gh repo create` 一発で公開可能な状態）。
3. **occupation registry v26**（gftdcojp/cloud-itonami `docs/open-occupation-registry.{edn,md}`）:
   2641 + 2654 を追加（2166 は v25 時点で登録済みと判明）。2651 / 2652 は
   repo 公開後に追記する。
4. cloud-itonami の west pin を registry v26 merge commit へ前進。

## Follow-ups

- ai-gftd-animeka / ai-gftd-dougaka を kotoba-lang/{anime,douga} 消費に
  rewire（deps.edn :git/url pin + ns 差し替え。動作は同一のはず）。
- ongakuka を superproject tree から分離: private カタログ repo
  （ai-gftd-ongakuka）として child repo 化し kotoba-lang/ongaku を消費、
  superproject の plain tree は削除。
- mangaka の domain.cljc / komawari 合成の kami-genko / kami-mangaka-\* への
  統合継続、data/ 175MB の B2+DataLad 移行。
- 2651 / 2652 の公開 + registry 追記。
- ai-gftd-dogaka（空 stub）の削除。
- syosetsuka の domain が実装されたら `shousetsu` craft lib を分離。

## Consequences

- 「-ka をどこに置くか」の再発質問が消える: 新しい creative -ka は
  職能 actor (gftdcojp) + 技芸 lib (kotoba-lang) + ISCO blueprint
  (cloud-itonami-isco) の3点セットで起こす。
- kotoba-lang の craft lib は全 org から消費可能になり、-ka actor は
  プロンプト・カタログ・serving という事業差分だけを持つ薄い層に近づく。
- occupation registry が creative 職能（major group 2 の芸術系）をカバー
  し始める（86/436）。
- registry (21→84 entries) と org 実態 (110 repos) の乖離という既存ギャップ
  は本 ADR では解消しない（pre-existing gap）。

## References

- ADR-2606302300 (org taxonomy) / ADR-2607021330 (visibility SSoT)
- ADR-2607011000 (ISIC vertical capability libs) / ADR-2607012000
  (cloud-itonami-isco) / ADR-2607012100 (cloud-itonami org transfer)
- ADR-2607010930 / ADR-2607020300 (kami-mangaka-\* / kami-genko 分離の前例)
- ADR-2607022900 (west pin server-side verification / --entry 最小 diff)
- ADR-2606301900 (ISCO/COFOG organism actors — etzhayyim 側の分類 organism)

## Addendum (2026-07-02, follow-ups executed)

- ai-gftd-dougaka → kotoba-lang/douga 消費に rewire（merge 895223e、46 assertions green）。
- ai-gftd-animeka → kotoba-lang/anime 消費に rewire（animeka.domain を facade 化、
  merge d692972、32 assertions green）。
- ongakuka を child repo 化: private `gftdcojp/ai-gftd-ongakuka`（カタログ+docs、
  kotoba-lang/ongaku を消費、smoke 8 assertions green）。superproject の plain tree
  `orgs/gftdcojp/ongakuka` は削除、west 登録に置換。
- mangaka data/（180 files、うち ghosthacker 53M）→ DataLad dataset
  `gftdcojp/mangaka-data`（git-annex + B2、fileprefix=mangaka-data/、44 keys /
  54MB uploaded、text2git）。import-ghosthacker は env → sibling checkout →
  旧 path の順で解決（merge 9ee973e、85 tests / 572 assertions green）。
- ai-gftd-dogaka（空 stub）を GitHub / west / local checkout とも削除。
- 未了: mangaka の kami-genko への domain 統合（別途）、2651/2652 公開（権限ゲート）、
  syosetsuka の shousetsu lib 分離（domain 実装後）。
