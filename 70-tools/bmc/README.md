# bmc — portfolio business-model canvas CLI（7 CLI / .cljc / 進化成長 ReAct loop）

ADR-2607021500 の 7 レイヤー lean canvas を CLI で扱い、進化・成長させるための
道具。**正本は datoms EDN + append-only ledger** で、md は生成物。

```
正本:   90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn   (base、書き換えない)
      + 90-docs/business/canvas-ledger.edn                      (append-only events)
生成物: 90-docs/business/<product>-business-model.md            (gftd canvas md --all)
```

## 7 CLI（同一 .cljc engine の product 束縛違い）

| bin/ | products |
|---|---|
| `itonami` | cloud-itonami (L3 business operator) |
| `manimani` | cloud-manimani (L5 personal wellbecoming OS) |
| `murakumo` | cloud-murakumo (L1 LLM 推論 infra) |
| `kotoba` | net-kotobase (L2 storage hosting) |
| `aozora` | app-aozora + app-aozora-yoro (L4 SNS + messenger) |
| `e7m` | etzhayyim (L0 artificial organism platform, 非営利) |
| `gftd` | umbrella — 全 product（ai-gftd-apex を含む） |

```bash
70-tools/bmc/bin/gftd products
70-tools/bmc/bin/itonami canvas show
70-tools/bmc/bin/gftd canvas md --all                 # 8 md を再生成
70-tools/bmc/bin/murakumo canvas add :cloud-murakumo.problem "…"
70-tools/bmc/bin/murakumo hyp pass :hyp/murakumo-tok-price --evidence "run ledger 実測 …"
70-tools/bmc/bin/murakumo react tick                  # ReAct 1 tick（有界）
70-tools/bmc/bin/aozora react loop --max-ticks 5      # dry まで反復
70-tools/bmc/bin/gftd ledger show --tail 20
bb 70-tools/bmc/run-tests.bb                          # tests
```

repo root 以外から動かすときは `GFTD_ROOT=<superproject root>`。

## 進化成長 ReAct loop（CLAUDE.md Actors パターン準拠）

- **observe** — canvas（fold 済）+ hypotheses + metrics（`90-docs/business/metrics/<product>.edn`
  または `--metrics k=v`）を読む
- **think** — advisor は *proposal のみ* 返す。既定は deterministic mock
  （untested riskiest 仮説 → gate を Key Metrics へ昇格 / refuted → UVP に pivot 検討 /
  `:signal` metric → Problem へ観測事実）。**LLM advisor（langchain.model）は
  `(fn [observation] proposals)` を `:advisor` に注入するだけで差し替わる**
- **act** — 独立 governor が検閲（重複 / 最終 item の retract / evidence 無しの
  hyp 遷移 / etzhayyim 非営利不変条件 などを拒否）。可決・拒否とも ledger に積む
- 1 run = 1 tick（有界）。`react loop` は budget（`--max-ticks`）内で dry まで反復する
  durable outer loop。**人手の `canvas add` 等も同じ governor を通る**
  （「governor が拒否する書込を actor は決して行わない」）

## 構成

```
src/gftd/canvas.cljc   # datoms index / event fold / md・text render（純 cljc）
src/gftd/ledger.cljc   # append-only ledger（1 行 1 EDN event）
src/gftd/react.cljc    # observe→think→act、advisor ⊣ governor、run-ticks
src/gftd/cli.cljc      # 共有 dispatch + 7 CLI registry
bin/{itonami,manimani,murakumo,kotoba,aozora,e7m,gftd}   # bb wrapper
test/gftd/bmc_test.cljc
```

将来分割: 三組織タクソノミ（ADR-0020）上は再利用部品 = com-junkawasaki 子リポ
（例 `bmc-clj`）へ split し、各 product repo の CLI から deps 参照する（follow-up、
ADR-2607021600）。
