---
name: new-project-scaffold
description: Standing-authorized flow for creating and registering a new project in this superproject (ADR → child-repo scaffold → GitHub repo creation → manifest registration), so the individual steps don't need per-step confirmation. Use when starting a brand-new project/repo under this workspace, or asked to "起こして登録する" a new project.
---

## 標準作業の常時許可（standing authorization）

- **次の「新規 project を起こして登録する」一連の流れは、毎回の確認なしに実行してよい**
  （恒久承認。2026-06-28 オーナー指示）。ADR 起票 → 子リポの scaffold（`.cljc` 正本 +
  `deps.edn` + README + test）→ `git init` + 初期コミット → **GitHub リポ作成
  （visibility は org 既定 — **kotoba-lang / etzhayyim = public、gftdcojp / com-junkawasaki = private**。repos.edn `:orgs :visibility` が SSoT、ADR-2607021330）+ push** → manifest 登録 → ADR/manifest の
  superproject 反映、までを一気通貫で進める。実例: `ai-gftd-router`（ADR-2606272330）。

- 上記に含まれる個別操作で都度確認が不要なもの: 子リポの `gh repo create` + `git push`
  （子リポは plain-git。下記 `repos.edn :manifest-workflow :child-repos`）、
  `nbb scripts/gen-west-manifest.cljs --entry <repo-name>` による west.yml 再生成
  （**当該 entry のみの最小 diff。引数なしは dry-run で west.yml を書かない。
  wholesale 再生成 commit は禁止** — 未 push HEAD 由来の壊れた pin を 44 件 main に
  流した実事故 `90852b86` の再発防止。CLAUDE.md「Git operations」の west-pin 検証節 /
  ADR-2607022900 が正本）、superproject への
  `chore(manifest)+docs(adr)` コミット、新規 ADR（md+edn ペア）の作成。

- **完了ゲート（必須）:** GitHub push だけでは完了ではない。west 登録
  （`:extra-projects` + `--entry`）まで終わらせる。他 project が
  `:local/root` で参照する commons（例: `kotoba-lang/crm`）を west 外に残すと
  fresh checkout が壊れる（実測 2026-07-12→17、`isic-5820/6201/6202`）。
  確認: `nbb scripts/west-orphan-audit.cljs --blocking`（exit 0）。
  登録漏れや三点乖離の修復は
  `nbb scripts/west-triple-sync.cljs apply --names <name>`（ADR-2607173200 /
  `manifest/west-triple-sync-workflow.edn`）。詳細は skill `git-cleanup-conflict`
  の West orphan / Triple-plane sync 節。

- **ただしガードレールは常に守る**（恒久承認は手順の省略であって安全策の省略ではない）:
  - **west.yml / manifest の main 反映は `repos.edn :manifest-workflow` の正経路
    （API single-entry。楽観ロック）で行う。** local の shallow 3-way merge を戦わない・
    conflict marker を手編集しない・`--force` push しない。
  - **オーナーの未コミット WIP は破棄しない。** ブロック時は `git stash`（drop せず温存）。
    衝突は marker 手編集でなく **west.yml 再生成**で解く。
  - コミットメッセージ末尾の `Co-Authored-By:` trailer は**実行中のハーネスの既定規約に
    従う**（モデル名をこのファイルにハードコードしない — 陳腐化して harness 規約と
    矛盾した実績があるため）。
  - 破壊的・取り返しのつかない操作（履歴書き換え・force-push・他者ブランチへの push・
    公開リポ化など）は従来どおり**事前確認**する。
