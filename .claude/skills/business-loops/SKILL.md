---
name: business-loops
description: 事業側の反復（BMC / Lean Loop の反復トラッキング、system dynamics loop 分析、direct-first 調達）を触るときの正本。`70-tools/bmc/` の既存共有システムを確認してから作る規則、standalone パターン（repo 側 `docs/bmc-lean-loop-log.md`）、sparse-checkout で見えない話、`kotoba-lang/dynamics` / `loop-system-dynamics` の使い方と「全 entity 対象・捏造しない」規則、仲介プレミアムの計算と直接性の確認。「BMC」「Lean Loop」「build-measure-learn」「成熟度スコア」「system dynamics」「leverage point」「調達」「見積」「仲介」で発火。CLAUDE.md の 3 節から 2026-09-11 に切り出した正本（ADR-2609112300）。
---

# business-loops — 既存の loop を確認してから作る

**CLAUDE.md の「調達経路は direct-first」「System dynamics loop 分析」「BMC / Lean Loop
反復トラッキング」の 3 節はここへ委譲している。** CLAUDE.md 側には不変条件だけが
残っており、手順・実例・コマンドはこの文書が正本。

---

# CLAUDE.md に 2026-09-11 まで残っていた本文（逐語、ADR-2609112300）

以下は CLAUDE.md から**逐語で**移した本文である（2026-09-11、ADR-2609112300。AGENTS.md の
読み込み上限 31,457 字に合わせて CLAUDE.md を不変条件だけに絞った）。CLAUDE.md 側には
skill を読まなくても効く規則だけが残っている。ここが理由・実測・罠の正本。

## 調達経路は direct-first（repo-wide mandatory、2026-08-25）

**メーカー、運営主体、公式販売主体との直接取引を既定とし、検証済みの
付加価値がある場合だけ中間者を使う。** 詳細な機械可読正本は
`90-docs/business/direct-procurement-rule.edn`。

- 比較単位は `調達経路 × 製品構成 × 数量 × 時点`。表示単価ではなく、税、送料、
  通関、検品、不良/RMA、停止損失、管理費を含むリスク込み総調達原価で比べる。
- `仲介プレミアム = 中間経路の総原価 - 直接経路の総原価`。そこから、国内交換在庫、
  法令適合証跡、SLA、与信、物流、保証等の**実測または契約化された**付加価値を引き、
  残りを説明のない仲介レントとして扱う。
- 付加価値が不明、未確認、又は価格差を下回るなら中間経路を選ばない。直接経路が
  不可能、法令ゲートを通らない、必要数を供給できない、または検証済み付加価値が
  プレミアムを上回る場合だけ、根拠付きで例外にできる。
- 言語で直接性を推測しない。送信先endpoint、担当者の決裁権、契約・請求主体、
  依頼種別で直接性を確認する。直接調達のために仕向地、再販売目的、法令上の責任を
  隠したり、販売・地域制限を回避したりしない。
- 見積、価格観測、契約主体、調達経路、付加価値、例外理由、判断時点を記録する。
  仲介者の存在や見積取得だけで、付加価値が実証されたと扱わない。


## System dynamics loop 分析 — 全 entity 対象・kotoba-lang/dynamics（repo-wide mandatory rule、2026-07-20、ADR-2607203000）

- **system dynamics（stock-flow-loop / Meadows leverage-point）分析において、
  いかなる entity・組織も「対象外」として categorical に除外しない。** モデルの
  スキーマは常にどんな entity も受け入れられるよう設計し、**「計算済み」と称する
  数値は必ず実データ（日付・出典付き）に基づく** — 捏造したグローバル総計を
  測定値として提示しない。今日数値を持たない entity は「カバレッジが未達」で
  あって「対象外」ではない。「全世界の全組織を文字通り列挙する」ことと
  「どの entity も原理上排除しないモデルを作り、持っているデータで誠実に計算する」
  ことは別物であり、後者を常に行う。
- **計算そのものは `kotoba-lang/dynamics`（stock/flow/loop primitives + Meadows
  leverage-point scoring、pure `.cljc`、no-prefix library）を使う。ゼロから
  再発明しない。** pool-tap 型の介入（外部 pool の規模に依存する打ち手）は、
  conversion-rate が未計測なら `:expected-yield` を
  `:uncomputable-until-measured` として明示する — 大きな pool に未計測の
  変換率を掛けて期待値を捏造しない。
- **実 entity データに対して継続的に回す orchestrator は
  `kotoba-lang/loop-system-dynamics`（`loop-*` prefix、`manifest/repository-rules.edn`
  の `:name-prefix` taxonomy 準拠: observe → evaluate → decide → act →
  record-evidence、domain scoring truth は `dynamics` に委譲し自前で持たない）を
  使う。** 新しい `loop-*` repo を作る前に、必ず**この superproject の
  `manifest/repository-rules.edn`**（`loop-*` は continuous orchestrator、prefix 無しは
  reusable library、`skill-*`/`action-*` は別役割）を確認してから命名する。
  **taxonomy は 2026-07-29 に `kotoba-lang/loop-ux-kaizen` から superproject へ移した**
  （ADR-2607299000。leaf repo に置かれた workspace 規約を他 3 repo が
  docstring で参照しており、適合を検査するものが無かった）— リーフ側の
  `resources/repository-rules.edn` は「その repo 自身がどの契約を主張するか」の
  宣言だけを残す。
- entity の追加は `kotoba-lang/loop-system-dynamics` の
  `resources/entities-seed.edn` に日付・出典付きの map を 1 つ足すだけでよい
  設計になっている——コードの再設計は不要。詳細・実例（etzhayyim/kotoba-lang/
  cloud-itonami/gftdcojp + 外部参照 6 組織の第1回計算、「なぜ資本主義・投機・
  搾取的構造が実際に強いか」の構造的分析）は ADR-2607203000 を参照。


## BMC / Lean Loop 反復トラッキング（business loop、2026-07-12）

**新しく BMC (Business Model Canvas) / Lean Loop (build-measure-learn) の反復トラッキングを
作ろうとする前に、`70-tools/bmc/` に既に本番稼働中の共有システムが無いか必ず確認する。**
実測: 2026-07-12、9 プロダクト分の BMC/Lean Loop スケジューラを「ゼロから設計」しようとして
調査した結果、うち 5 つ（cloud-itonami・cloud-manimani・cloud-murakumo・net-kotobase・
app-aozora）は既にクラウド常駐 routine（`itonami-react-growth-hourly` 毎時 /
`bmc-business-operate-daily` 毎日）で自動運転中、さらに 3 つ（network-isekai・
ai-gftd-yukkuri・club-shinshi）も base datoms / canvas-ledger / metrics には既に完全登録
済みで、routine 側の `--product` ハードコードリストへの反映漏れがあっただけだった。この
確認を怠ると、既存システムと衝突・重複する独自ログを 9 個作りかねない実害があった
（ADR-2607124500）。

- **正本は `90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn`（base、書き換え禁止）+
  `90-docs/business/canvas-ledger.edn`（append-only events）。** md
  （`90-docs/business/<product>-business-model.edn`）・`maturity-scores.edn` は生成物、
  手編集禁止（`gftd canvas md --all` / `gftd score md` で再生成）。設計 ADR:
  2607021600（CLI/ReAct loop）・2607021700（成熟度スコア）・2607021800（collect/運転）・
  2607022100（gate 評価器）・2607022200（per-product gate 計器）。使い方は
  `70-tools/bmc/README.md`。
- **`70-tools/bmc/` と `90-docs/business/` はこの superproject の既定 sparse-checkout から
  除外されている。** 通常の checkout では存在自体が `find`/`ls` に映らない（実測でこれが
  上記の見落としの一因になった）。触る前に
  `git sparse-checkout add 70-tools/bmc 70-tools/scripts scripts 90-docs/business 90-docs/adr`
  で明示的に取得する（既に含まれていれば no-op）。
- **ツールチェーンは 2026-07-10 に babashka(bb) → nbb(cljs) へ移行済み**（commit
  `b073ea7da12`）。`bb 70-tools/bmc/collect.bb` のような古い記法は存在しない — 正しくは
  `kbb --backend sci 70-tools/bmc/collect.cljk` / `kbb --backend sci 70-tools/bmc/bin/gftd.cljk <args>` /
  `kbb --backend sci 70-tools/bmc/run-tests.cljk`。稼働中の cloud routine の中にもこの移行前の古い
  記法が残っているものがある（`itonami-react-growth-hourly` は 2026-07-12 時点で未修正、
  follow-up）— CCR agent が実行時に自己修復して動いてしまうため気付きにくい。routine の
  prompt を編集する機会があれば直す。
- **既存 canvas/仮説の有無は `gftd products` / `gftd canvas show --product <p>` /
  `90-docs/business/maturity-scores.edn` で確認できる**（`COM_JUNKAWASAKI_ROOT=<superproject root>
  kbb --backend sci 70-tools/bmc/bin/gftd.cljk products` 等）。登録済みなのに daily routine の
  `--product` ループに載っていないだけ、というギャップが起点になりやすい —
  その場合は新規登録でなく routine の対象リスト追加で足りる。
- **この共有システムのスコープは `gftdcojp` org の 11 プロダクト**（`gftd products` の
  出力が正）。**別 org（`jk-luxury` の `club-shinshi`/`net-babiniku` 等）や、対象外の
  プロダクト（`local-murakumo` 等）は意図的にこのシステムに登録しない** — base datoms
  への新規登録は人間レビューを要する大きな決定で routine が自動でやることではない
  （`90-docs/adr/2607021600` 「書き換え禁止」）。これらは代わりに **standalone パターン**
  （`local-murakumo`: ADR-2607121600、`net-babiniku`: ADR-2607122300 が先例）を使う:
  - 対象 repo 自身に `docs/bmc-lean-loop-log.md` を作り、`## Iteration N — <date>` を
    積む。**過去 iteration の記述が誤っていた／陳腐化したと分かったらその場で直す**
    （2026-07-25 のオーナー判断で append-only を撤回。ADR-2607257000。変更の経緯は
    `git log -p docs/bmc-lean-loop-log.md` が持つ）。ただし後知恵で「当時こう見えていた」
    という観測記録を書き換えて成功譚に整形しない — 誤りは誤りとして直し、
    捏造はしない。
  - superproject（`com-junkawasaki/root`）側に、その反復トラッキングを開始する決定を
    記録する ADR（md+edn ペア）を作る。
  - 対象 repo が独自の `90-docs/adr/` 番号体系を持つ場合（`jk-luxury` 系リポジトリの
    慣習。`club-shinshi`/`net-babiniku` とも `90-docs/adr/0001…` から始まる連番）は、
    superproject 側 ADR への **local mirror**（短いポインタ ADR、既存 `0001` が
    superproject 側の設計 ADR を mirror する形に揃える）をそのリポジトリ側にも追加する。
  - claude.ai routine（`RemoteTrigger`）で日次反復させる場合、捏造ゼロ（不明な値は
    「unknown」と明記）を prompt に明記し、その repo に無関係な既存の反復ログ
    （例: `club-shinshi` 自身の repo-local な H1/H2 kaizen loop
    `60-apps/ai-gftd-project-shinshi/docs/260613-*.datoms.edn`、これは telemetry
    配線待ちで長期 untested、outcome/metric を LLM が捏造することを明示的に禁止する
    固有の不変条件を持つ）には触れないことを明記する。

