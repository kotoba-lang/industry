---
id: adr-2606272237-west-manifest-api-single-entry
title: "ADR-2606272237: superproject の west manifest 変更は GitHub API のサーバ側 single-entry commit を正経路にする（生成物の衝突は merge せず再生成で解決）"
status: active
doc_type: adr
topic: git-operations
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - manifest/west.yml への変更（project 登録 / rename / pin 前進）の起こし方
  - west.yml の merge conflict 解決方針（marker 手編集の禁止と再生成での解決）
  - manifest 変更時に ancestry 判定が要る場合の手順（merge-base 狙い撃ち）
related:
  - CLAUDE.md "## Git operations"
  - ADR-2606241600 (shallow --depth 1 を git 既定にする)
  - scripts/gen-west-manifest.cljs (west.yml 生成器 / --check)
  - manifest/repos.edn ":manifest-workflow"
supersedes: []
superseded_by: []
---

# ADR-2606272237: west manifest 変更は API のサーバ側 single-entry commit を正経路にする

**Status**: accepted
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

本リポジトリは west superproject であり、子repo群は submodule ではなく west
manifest で管理する。superproject の git が manifest について commit するのは
**`manifest/west.yml` の per-project pin（revision SHA）だけ**で、その west.yml は
`scripts/gen-west-manifest.cljs` が `manifest/repos.edn`（ポリシー SoT）＋各子repo の
working HEAD から**生成する（手書き禁止 / CI は `--check`）**。

ここに、これまで暗黙だった摩擦が二つある:

1. **superproject も shallow（ADR-2606241600）**。`--depth 1` 既定なので共通祖先が
   graft 境界の外に出やすい。実際、本セッションで kekkai-actor 登録のために
   `git merge origin/main` を試みると **`refusing to merge unrelated histories`**
   で失敗した。`rev-list --left-right --count` の「1 behind」も shallow 由来の
   **ancestry 誤判定**でありうる（push は実際には通った）。local 3-way merge は
   この superproject では信頼できない。

2. **west.yml は生成物**。中身は project ごとの行指向 pin であり、二つのブランチが
   同じ project の pin を別々に進めれば textual conflict になる。これを `<<<<<<<`
   marker の手編集で解こうとするのは「生成物を手で 3-way merge する」アンチパターン
   で、手書き禁止に反し、pin を静かに壊す。さらに並行作業で working tree が流動する
   と（再生成で west.yml が動く、dir が rename される）ローカルの merge 解決は一層
   不安定になる。

CLAUDE.md は既に「ローカル shallow 編集をしない／API でサーバ側にクリーン commit
／merge-base 狙い撃ち／pin == repo HEAD を検証」を断片的に述べていた（PR #61/#62/
#86 の実績）。本 ADR はそれを **manifest 変更の唯一の正経路**として確定し、衝突解決
の方針まで含めて成文化する。

## Decision

### 1. manifest 変更は GitHub API のサーバ側 single-entry commit を正経路にする

project の **登録 / rename / pin 前進のすべて**を、ローカル shallow 編集ではなく
**ブランチ tip 上の API single-file commit** で起こす:

1. tip の `manifest/west.yml` と blob SHA を取得（dir listing から SHA を採ると
   巨大 base64 を避けられる）。
2. **当該 entry の行だけ**を編集（diff は最小）。
3. blob SHA 一致で PUT（`PUT /repos/:o/:r/contents/manifest/west.yml`、`branch=`、
   `sha=`）。**tip がずれていれば 409 で弾かれる** → 取得し直してリトライ。
4. **pin == 子repo HEAD を検証**してから commit する。

これで **conflict が構造的に発生しない**（楽観ロックの 409 retry が marker 解決の
代わりになる）。shallow にも触れない（ancestry はサーバが full 履歴で計算）。実例:
PR #61/#62/#86、本セッションの kenchi-actor → kenchi-clj rename（`34988dd`、diff は
3 add / 3 del、当該 entry のみ）。

### 2. west.yml の衝突は marker 手編集せず「再生成」で解決する

やむを得ずローカル merge する場合、west.yml の衝突は **`nbb scripts/gen-west-manifest.cljs`
で解決する**。手順: project 集合の **superset 側**を採用 → `west update` で子を目的の
pin に揃える → 再生成 → **`--check` が通れば canonical**。生成器が正準の衝突解決器で
あり、conflict marker を手で編集してはならない。

### 3. 「pin 退行」の罠 — 再生成の前に必ず `west update`

再生成は**ローカルの working HEAD** で pin する。子repo が目的 pin より遅れていると、
再生成は黙って pin をロールバックさせる。したがって再生成の前に必ず `west update`
（または対象 project の `git -C <path> fetch/checkout`）で子を目的 pin に揃える。
あるいは API 経路（§1）を使えばこの罠自体に触れない。

### 4. ancestry が要るときは merge-base 狙い撃ち（固定 depth で賭けない）

判定が必要なら ADR-2606241600 の通り
`gh api repos/<o>/<r>/compare/main...<branch> --jq .merge_base_commit.sha` で base を
サーバに計算させ、その SHA だけ `git fetch --depth 1` する。`--depth 30` 等の当て推量
はしない（外れると誤答 or 失敗）。

### 5. 子repo は普通の git

子repo（例: `kekkai-actor` / `kenchi-clj`）は独立した通常の git repo として
branch/PR/push する。west 固有の作法は無く、superproject はその pin を記録するだけ。

### 6. API hand-edit は再生成器を通らない → 後で `--check` で canonical 一致を確認

§1 の PUT は生成器を経由しない手編集である。変更が repo＋HEAD の実態を写しただけ
なら canonical と一致するが、ローカルが落ち着いたら一度
`nbb scripts/gen-west-manifest.cljs --check` を通し「west.yml == 生成器出力」を確認する
（CI の `--check` 担保のため）。

## Consequences

- **conflict が構造的に起きない**。push/merge/衝突解決が shallow superproject 前提で
  破綻しない。並行 working-tree flux にも触れない。
- CI の `--check` が SoT（repos.edn + 子 HEAD）との一致を担保し続ける。
- トレードオフ: API 経路は手編集なので canonical drift の余地がある（§6 の `--check`
  で回収）。再生成ベースの git merge driver も理屈上は可能だが、§3 の pin 退行のため
  単体では不十分（`west update` 前提が要る）→ **API 単一 entry 統一の方が確実**。
- 本 ADR は CLAUDE.md「## Git operations」/「Actors の west 登録」と整合し、それらを
  manifest 変更全般へ一般化する。
