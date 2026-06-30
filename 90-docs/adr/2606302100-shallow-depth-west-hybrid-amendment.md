---
id: adr-2606302100-shallow-depth-west-hybrid-amendment
title: "ADR-2606302100: west 導入後の clone-depth 方針 — 一律 shallow を heavy-only ハイブリッドへ（分析・提案）"
status: proposed
doc_type: adr
topic: git-shallow-policy
authoritative: false
last_verified: 2026-06-30
amends:
  - 90-docs/adr/2606241600-shallow-depth1-git-default.md
authoritative_for:
  - west の pin 駆動と一律 clone-depth:1 が衝突して update を壊す機序を記録する
  - heavy(バイナリ同梱) / light(純コード) repo の仕分け基準と実測を確定する
  - 一律 shallow を「heavy のみ shallow、残りは full」へ移す提案と unshallow 手順を示す
related:
  - 90-docs/adr/2606241600-shallow-depth1-git-default.md
  - 90-docs/adr/2606280300-kotoba-rad-git-sovereign-repo.md
  - manifest/repos.edn
  - scripts/gen-west-manifest.bb
  - CLAUDE.md
supersedes: []
superseded_by: []
---

# ADR-2606302100: clone-depth 方針の west 整合（分析・提案）

**Status**: proposed（分析のみ。実装は次タスク。オーナー指示 2026-06-30）
**Date**: 2026-06-30
**Deciders**: Jun Kawasaki

## Context

ADR-2606241600 で「shallow（`--depth 1`）を git 既定」とし、`repos.edn`
`:defaults :clone-depth 1` で **全 repo 一律 shallow** にしている。submodule 時代は
これで妥当だった（superproject が gitlink を直接 checkout）。

しかし west 移行後、調整単位が **pin（特定 commit SHA）driven** に変わった。west は
各 project を `manifest/west.yml` の `revision:`（pin）へ checkout しようとする。ここで
一律 shallow が **pin と構造的に衝突する**ことが 2026-06-30 の west update で顕在化した。

### 観測された具体的破綻（2026-06-30）

1. **pin 到達失敗（7 件 update failed）。** `--fetch smart` + `clone-depth:1` は
   **branch tip と tag しか取得しない**。pin がいずれの tip でもない（＝tip より古い）
   と、その SHA は shallow boundary の外で到達不能になり checkout が失敗する。実例:
   `kotoba` / `kotoba-code` / `aero` / `aiueos` / `computer-use` / `kami-engine` /
   `kami-webgpu` の 7 project が "ERROR: update failed"。手動で
   `git fetch --depth 1 origin <pinned-sha>` を撃つと数秒で解決した＝**pin 到達のみが
   原因**で、ネットワーク/権限ではない。
2. **ancestry 誤判定（偽 force-update）。** superproject の `main` 同期時、shallow の
   graft 境界の外に merge-base があり `git merge-base` が空 → ローカル git が「1-1 で
   乖離・forced update」と誤検出。GitHub の compare API（full 履歴）では
   `status: ahead, ahead_by:23, behind_by:0`＝**純粋な fast-forward** だった。
   `--deepen` で graft を繋いで FF 解消。ADR-2606241600 が警告するとおりの罠。
3. **重 repo の pin checkout が高コスト。** `org-spirit-in-physics`（worktree 1.5–3G、
   `.git` は superproject の `.git/modules/...` を指す）は、pin への checkout だけで
   2 分でもタイムアウト。shallow でも **working tree のバイナリ実体は縮まない**ので
   depth では救えない。

要点: **shallow は「pin が tip からズレた瞬間に『遅い』でなく『失敗』に変える」。**
west の pin 駆動とは相性が悪い。

## 実測（2026-06-30、checked-out 子リポ）

総フットプリント **約 40GB**。分布は極端な二極化:

- **light（< 50MB）: 357 project** — kotoba-lang の純 `.cljc` lib 群（aero/dmn/states/
  datom/cron/ical/policy/openapi…）が大半。**ここを full にしてもコストはほぼ無。**
- **heavy（≥ 200MB）: 15 project** — バイナリ（モデル重み / wasm / 画像 / 動画 / PSD 等）
  同梱の worktree。

| heavy project | 実測 worktree+.git |
|---|---|
| `orgs/com-junkawasaki/manimani` | 16.2 GB |
| `orgs/com-junkawasaki/kotoba`, `orgs/etzhayyim/kotoba` | 各 8.98 GB |
| `orgs/com-junkawasaki/org-spirit-in-physics-comics` | 3.0 GB |
| `orgs/gftdcojp/app-aozora` | 2.4 GB |
| `orgs/com-junkawasaki/kami-engine` | 1.95 GB |
| `orgs/etzhayyim/root` | 1.77 GB |
| `orgs/com-junkawasaki/260208-spirit-in-physics` | 1.56 GB |
| `orgs/com-junkawasaki/ghosthacker` | 803 MB |
| `orgs/gftdcojp/ai-gftd-apps-gftdcojp` | 538 MB |
| `orgs/gftdcojp/m365-archive`（既に DataLad） | 453 MB |
| `orgs/com-junkawasaki/kototama` | 435 MB |
| `orgs/com-junkawasaki/webmaster` | 301 MB |
| `orgs/gftdcojp/network-isekai` | 231 MB |

heavy を **full 化すると全バージョンのバイナリが `.git` に積もり破滅的**（manimani は
full 履歴で数十 GB 級）。逆に light を full 化しても増分は無視できる。

## Decision（提案 — 未実装）

**一律 shallow をやめ、heavy だけ shallow に残すハイブリッドにする。**

1. `repos.edn` に **`:heavy`（= shallow 維持）リスト**を新設（上表の path 群）。
2. `scripts/gen-west-manifest.bb` を改修: `clone-depth: 1` を **`:heavy` に属する
   project だけ**出力する（現状は `:defaults :clone-depth` で全 project に付与）。
   light project は `clone-depth` 行を出さない＝full clone。
3. heavy は CLAUDE.md 既定方針どおり順次 **B2 + DataLad** へ移し、いずれ west からは
   pointer だけにする（`m365-archive` が先行例）。
4. 既存の shallow clone は **light だけ `git fetch --unshallow`** で深くする（heavy は
   触らない）。pin が現在 tip からズレている light repo は、unshallow 後に pin へ
   checkout できるようになる。

### 期待効果

- light（357 project）で **pin 到達失敗が構造的に消える**（pin が tip より古くても
  full 履歴に必ず居る）。今日の 7 件失敗の根治。
- ancestry 誤判定（偽 force-update）も light については解消。
- heavy はバイナリ実体の問題なので depth では救えない → **shallow 維持 + DataLad 移行**
  が正解で、本 ADR はそこを変えない。

## 代替案と却下理由

- **全 repo full（shallow 全廃）**: pin/ancestry は完全解決するが heavy が破滅
  （manimani/kotoba 等が full 履歴で更に肥大）。却下。
- **shallow 維持 + pin を常時 tip 追従**: depth を触らずに pin lag を運用規律で回避。
  ディスク最小だが、pin が少しでも遅れた瞬間に update 失敗へ戻る脆さが残る。
  ハイブリッドの方が構造的に堅い。補助策としては併用可。
- **固定 depth 増（`--depth 30` 等）**: ADR-2606241600 が「当たれば速い/外れると誤答」
  の博打と明記。却下。

## 実装時のガードレール（次タスク）

- `repos.edn`/生成器の変更は通常の superproject commit。`west.yml` 再生成は
  **pin 退行の罠**に注意（生成器はローカル working HEAD で pin する＝子が遅れていると
  黙ってロールバック）。再生成後 `bb scripts/gen-west-manifest.bb --check`。
- main 反映は `repos.edn :manifest-workflow` の API single-entry 正経路。
- unshallow は light に限定し、heavy には `--unshallow` を撃たない。
- ADR-2606241600 は撤回せず、本 ADR が「west 文脈での適用範囲」を **amend** する形にする。

## 未解決の関連項目（本 ADR の対象外、別途）

- `cloud-murakumo` の west 名衝突（com-junkawasaki と gftdcojp の二重 basename）。
  生成器が name をパス末尾だけから作るため衝突する。**本ターンでは west.yml の当該
  2 行のみローカル修正して west を一時復旧**したが、生成器の org 修飾化は未実施
  （別途）。
- `manimani` は未コミット WIP で west checkout が skip 済み（owner WIP 温存。reconcile
  待ち）。
