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
  `chore(manifest)+docs(adr)` コミット、新規 ADR の作成（**EDN only** —
  `90-docs/adr/` は ADR-2607171600 で `.md` 廃止済み、`[{:db/id -1 :adr/id ...
  :adr/title ... :adr/status ... :adr/body ...}]` tx-data 形式の `.edn` 単体。
  `.md` を書いてから変換しない）。

- **完了ゲート（必須）:** GitHub push だけでは完了ではない。west 登録
  （`:extra-projects` + `--entry`）まで終わらせる。他 project が
  `:local/root` で参照する commons（例: `kotoba-lang/crm`）を west 外に残すと
  fresh checkout が壊れる（実測 2026-07-12→17、`isic-5820/6201/6202`）。
  確認: `nbb scripts/west-orphan-audit.cljs --blocking`（exit 0）。
  登録漏れや三点乖離の修復は
  `nbb scripts/west-triple-sync.cljs apply --names <name>`（ADR-2607173200 /
  `manifest/west-triple-sync-workflow.edn`）。詳細は skill `git-cleanup-conflict`
  の West orphan / Triple-plane sync 節。

- **etzhayyim 配下に新規 actor を起こす前に、その機能領域が既存の憲章化された
  actor（ooyake/danjo/toritate/yosoku 等）のスコープと重なっていないか必ず確認する**
  （実例: ADR-2607176000, 2026-07-17。「自治体インフラ対応の遅速を分析・効率化する
  agent」を作ろうとした際、この用途は ooyake の G11「政府をランキングしない・
  target-listを作らない」に抵触し、danjo（弾正）の「公開政府データの事実ベース非裁定
  監査」スコープと重なるが danjo 自身はまだ R0 scaffold（Council Lv6+ 批准前）で
  named-party publication は SBT 投票ゲート済み、と判明してから設計をやり直した）。
  各 etzhayyim actor の `CLAUDE.md`/`README.md` には `G1-Gn` 憲章ゲート・
  `Non-Goals`・R0→R3 activation trigger が書かれている——これは通常の
  `manifest/repos.edn :manifest-workflow` のような技術的登録手順ではなく、
  **オーナー自身が設計した統治規約**なので、新規 actor が「実在の政府主体を
  名指しする／格付けする／監査する」性質を持ちそうな場合は、この標準スキャフォールド
  フロー（技術的登録の恒久承認）をそのまま適用せず、一度オーナーに
  スコープ確認する（AskUserQuestion）。他ドメインでの類例:
  `90-docs/adr/2607021600-portfolio-bmc-lean` 系（BMC/Lean Loop の既存基盤確認）、
  `design-quality-score`（既存 EDN 確認）、coscientist（`network-isekai` 既存実装確認）
  ——「作る前に、同種の重なる仕組みが既にないか確認する」という同じパターン。

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
