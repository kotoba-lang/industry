---
id: adr-2607161801-kotoba-vcs-suite-tree-motif-naming
title: "ADR-2607161801: kotoba-lang VCS suite — 一本の木をモチーフにした命名規約（tane から）"
status: accepted (naming convention); kotoba-git の名は pending（部位案 miki / 主体・行為案 kikori 系）
doc_type: adr
topic: kotoba-vcs-suite-tree-motif-naming
authoritative: true
last_verified: 2026-07-16
authoritative_for:
  - "kotoba-lang の VCS suite を『一本の木の一生』で読ませる命名規約（git エコシステム機能 ↔ 木の相 ↔ 和語名の対応表）"
  - "確定済みの改称: kotoba-fleet-vcs → kagami（鏡）、kotoba-rad → nekko（根）"
  - "未確定スロット: kotoba-git（部位 miki か、主体・行為 kikori/teire/soma/kukunochi か）"
related:
  - 90-docs/adr/2607160005-kotoba-fleet-agent-vcs-west-successor.md
  - 90-docs/adr/2607072200-kotoba-git-kotoba-rad-content-addressed-vcs.md
  - 90-docs/adr/2607141700-cloud-itonami-git-native-business-cdci.md
  - 90-docs/adr/2607102200-kami-render-stack-deps-authority-rename.md
supersedes: []
superseded_by: []
---

# ADR-2607161801: kotoba-lang VCS suite — 一本の木をモチーフにした命名規約

**Status**: accepted（命名規約として）。kotoba-git の名のみ pending。
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki（「kagami, nekko, git を radicle/git/github/cdci/pr など
git の機能に分解して、木をモチーフに kotoba で対応表を。tane から」）

## Context

fleet-vcs スタックの改称の中で、単発の良い名（kagami / nekko）を超えて、
**kotoba-lang の VCS suite 全体を「一本の木の一生」として一貫読みさせる**方針に至った。
git エコシステムの各機能（radicle / git / github / CD/CI / PR、および commit /
branch / merge / fork / HEAD 等の下位プリミティブ）を、木の相（種→根→幹→枝→
蕾→花→実→梢→森…）に和語で対応づける。既に着地済みの **kagami（鏡）**・
**nekko（根）** はこの木の中に無改造で収まる。

## Decision — 木の一生 × git 機能 対応表（tane から）

| 木の相（和語） | git / エコシステム機能 | 名前 | 一言 | 状態 |
|---|---|---|---|---|
| 種 tane | init / genesis commit / RID = genesis の CID | tane | すべての起点 | 新規案 |
| 苗 nae（芽 mebae） | new-project scaffold / 雛形 | nae | 種から苗へ（既存 new-project-scaffold） | 新規案 |
| 根 nekko | **radicle**: 主権 identity・RID・delegate・signed refs | **nekko** | 地中の信頼の根 | ✅ 着地済（旧 kotoba-rad） |
| 幹 miki | **git**: object model（blob/tree/commit）・履歴の本体 | miki *or* 下記主体案 | 根から立つ本体 | ⏳ pending（旧 kotoba-git） |
| 枝 eda | branch | eda | git branch = 枝 | 新規案 |
| 小枝 koeda | feature / topic branch | koeda | 作業枝 | 新規案 |
| 股 mata | fork | mata | 幹の分かれ | 新規案 |
| 接ぎ木 tsugiki | **merge**（枝を幹に接ぐ） | tsugiki | 名が体を表す | 新規案 |
| 蕾 tsubomi | **PR** proposal（提案） | tsubomi | 開く前 | 新規案 |
| 花 hana | **PR** review / approve（審査） | hana | 披いて quorum 審査 | 新規案 |
| 実 minori | **CD/CI**: build・test・artifact・deploy | minori | 木が実を結ぶ。gate=熟すか | 新規案 |
| 葉 ha | file / blob | ha | object graph の葉 | 新規案 |
| 梢 kozue | HEAD / tip / release tag | kozue | 最新の枝先 | 新規案 |
| 年輪 nenrin | commit history / append-only 監査台帳 | nenrin | 不変の履歴（※漢語寄り。和語は「重ね kasane」） | 新規案 |
| 森 mori | **radicle** p2p network（自生する森） | mori | 中央でなく森で複製 | 新規案 |
| 庭 niwa | **github** hosting / mirror（見せる庭） | niwa | 中央 host は mirror に降格 | 新規案 |
| 鏡 kagami | fleet **manifest**（森を映す projection） | **kagami** | fleet-db を映す鏡 | ✅ 着地済（旧 kotoba-fleet-vcs） |
| 土 tsuchi | storage substrate（kotobase / B2 / DataLad） | tsuchi | object を養う土壌＝datom plane | 新規案 |

### 五大機能の対応（オーナー指定の分解）

- **radicle** → 根 **nekko**（identity）+ 森 **mori**（p2p 複製網）
- **git** → 幹 **miki**（部位）*or* 主体・行為案（下記「未決定」）
- **github** → 庭 **niwa**（見せる庭＝中央 host、mirror に降格）
- **cd/ci** → 実 **minori**（実り。execution-receipt / gate = 熟れるか）
- **pr** → 蕾 **tsubomi**（提案）→ 花 **hana**（審査/quorum）→ 接ぎ木 **tsugiki**（merge で幹に接ぐ）

PR が「蕾→花→接ぎ木」の三相に開くのは、現行 fleet の propose → govern →
canonical 前進とそのまま重なる。森 vs 庭の対比が radicle（自生する主権の森）と
github（手入れされた見せる庭＝中央 host）の本質を言い当てる。

## 未決定: kotoba-git の名 — 「部位」か「主体・行為」か

git は木そのものでなく **人が木に手を加える command-line tool**（主体・作用・
行為）である、というオーナー指摘により、幹＝miki（部位）に加えて**手入れする
主体／行為**からの候補を並記する。決定は本 ADR の addendum で行う。

- **部位案**: `miki`（幹）— 根 nekko と対をなす本体。systematic には最も素直。
- **主体・行為案**（git = 木に手を入れる道具・営み）:
  - `teire`（手入れ）— 「木に手を加える」の直訳。道具＝手入れの営み。最も語義一致
  - `kikori`（樵）— 樵＝木を伐り手入れする者（斧＝道具の含意）。具体的・力強い和語
  - `soma`（杣）— 杣＝用材の山、かつ杣人＝そこで働く者（万葉語）。場＋主体の二義。mori と韻
  - `komori`（木守）— 木を守り手入れする者（木守柿）。穏やか
  - `ueki`（植木）— 植え・仕立てる craft（植木屋）
  - `kukunochi`（久久能智神）— 古事記の**木の神**。木を司る神格（道具でなく霊格）

## 命名 rename 移行ポリシー（確立済みフロー）

改称は blast radius に応じて段階実施する（ADR-2607160005 実測）。**確立フロー
（nekko 実績）**: ① GitHub repo rename（redirect 保持）② **実 dep 消費者のみ**
deps.edn の `:local/root` パス + coordinate を更新（コメント言及だけの偽陽性は
除外）③ namespace は当面維持（repo 名 ≠ namespace。kagami=`fleet.*`、
nekko=`kotoba-rad.*` の前例）④ superproject の repos.edn / west.yml / fleet-db
（byte 一致で再 import）/ checkout 更新 ⑤ head 署名。**大規模消費者（kotoba-git
= 59 deps + 本番 CD/CI）は batched deps.edn 更新 + 回帰確認を付けた専用移行**とする。

## 状態

- ✅ **kagami**（旧 kotoba-fleet-vcs、鏡）着地済 — fleet manifest projection
- ✅ **nekko**（旧 kotoba-rad、根）着地済 — 主権 identity
- ⏳ **kotoba-git** → 名 pending（miki / teire / kikori / soma / komori / ueki /
  kukunochi のいずれか）。決定後、59-consumer 用 batched 移行で実施
- ▫️ 表内の他の名（tane / nae / eda / tsugiki / tsubomi / hana / minori / kozue /
  mori / niwa / tsuchi）は**規約上の予約語**。新規に該当機能の tool/repo を起こす
  ときはこの対応表に従って命名する（既存 repo の一括改称は求めない）

## Consequences

- kotoba-lang の VCS suite が「一本の木」として一貫読みできる命名体系になる。
  新規 VCS 系 tool は表の予約語から採る（例: p2p seeding tool → `mori`、
  merge driver → `tsugiki`）。
- 既存 repo の遡及改称は blast radius 順に段階実施（kagami→nekko 済、kotoba-git
  は名確定後）。表内の未使用語は将来用の予約であり、一斉改称は非目標。
