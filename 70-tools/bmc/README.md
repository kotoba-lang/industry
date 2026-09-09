# bmc — portfolio business-model canvas CLI（7 CLI / .cljc / 進化成長 ReAct loop）

ADR-2607021500 の 7 レイヤー lean canvas を CLI で扱い、進化・成長させるための
道具。**正本は datoms EDN + append-only ledger** で、md は生成物。

```
正本:   90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn   (base、書き換えない)
      + 90-docs/business/canvas-ledger.edn                      (append-only events)
生成物: 90-docs/business/<product>-business-model.edn           (gftd canvas md --all; EDN projection, ADR-2607171600)
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
70-tools/bmc/bin/itonami react tick --advisor auto    # gate + murakumo LLM (default)
70-tools/bmc/bin/itonami react tick --advisor gate    # deterministic only
70-tools/bmc/bin/itonami react tick --no-kotobase     # skip kotobase dual-write
70-tools/bmc/bin/gftd score                           # BMC/YC bench 成熟度スコア表
70-tools/bmc/bin/gftd score md                        # maturity-scores.edn 再生成
70-tools/bmc/bin/gftd allocate                        # OT (Sinkhorn) 予算配分表（ADR-2607194500）
70-tools/bmc/bin/gftd allocate md                     # portfolio-allocation.edn 再生成
70-tools/bmc/bin/gftd allocate write                  # 配分結果を governor 経由で ledger へ記録（任意）
70-tools/bmc/bin/gftd ledger show --tail 20
nbb 70-tools/bmc/run-tests.cljs                       # tests
```

スコア（ADR-2607021700）: BMC 成熟度 = completeness/hypothesis/validation
（自動、validation は ledger の hyp status）+ pricing/grounding（facts）。
YC bench 成熟度 = design 6 次元（YCBench 基準）50% + traction 3 次元 50%。
主観入力は `90-docs/business/maturity-facts.edn`、`hyp pass|fail` で検証が
進むと validation → スコアが自動で動く。

repo root 以外から動かすときは `COM_JUNKAWASAKI_ROOT=<superproject root>`（`GFTD_ROOT` も当面読むが、`gftd` は退役済み）。

## 進化成長 ReAct loop（CLAUDE.md Actors パターン準拠）

- **observe** — canvas（fold 済）+ hypotheses + metrics（`90-docs/business/metrics/<product>.edn`
  または `--metrics k=v`）を読む
- **think** — advisor は *proposal のみ* 返す。**既定 `auto`（ADR-2607180400）**:
  `gate-aware-advisor`（決定論 gate 進行）+ **cloud-murakumo** LLM
  (`POST https://api.murakumo.cloud/v1/chat/completions`、`enable_thinking` off)。
  LLM 失敗時は gate のみで続行（fail-soft）。`--advisor gate` で決定論のみ、
  `--advisor murakumo` / `BMC_ADVISOR=murakumo` でも同じ compose（LLM 必須意図を
  actor 名に残す）。
- **act** — 独立 governor が検閲（重複 / 最終 item の retract / evidence 無しの
  hyp 遷移 / etzhayyim 非営利不変条件 などを拒否）。可決・拒否とも ledger に積む
- **persist** — ① local SSoT `90-docs/business/canvas-ledger.edn` へ append
  ② **net-kotobase dual-write**（best-effort、CACAO via
  `70-tools/bmc/bin/kotobase-dual-write.cljs` + `kotobase-client`、db
  `portfolio-bmc-ledger`）。`--no-kotobase` / `BMC_KOTOBASE_DUAL_WRITE=0` で無効。
  identity: `70-tools/bmc/.bmc-kotobase-identity.hex`（gitignore、初回 mint）
- 1 run = 1 tick（有界）。`react loop` は budget（`--max-ticks`）内で dry まで反復する
  durable outer loop。**人手の `canvas add` 等も同じ governor を通る**
  （「governor が拒否する書込を actor は決して行わない」）

## 構成

```
src/gftd/canvas.cljc   # datoms index / event fold / md・text render（純 cljc）
src/gftd/ledger.cljc   # append-only ledger（1 行 1 EDN event）
src/gftd/react.cljc    # observe→think→act、advisor ⊣ governor、run-ticks
src/gftd/score.cljc    # BMC/YC bench 成熟度スコア（demand 側の入力にもなる）
src/gftd/allocate.cljc # OT (Sinkhorn) 予算配分（ADR-2607194500）
src/gftd/cli.cljc      # 共有 dispatch + 7 CLI registry
bin/{itonami,manimani,murakumo,kotoba,aozora,e7m,gftd}   # nbb wrapper
test/gftd/bmc_test.cljc
test/gftd/allocate_test.cljc
```

将来分割: 三組織タクソノミ（ADR-0020）上は再利用部品 = com-junkawasaki 子リポ
（例 `bmc-clj`）へ split し、各 product repo の CLI から deps 参照する（follow-up、
ADR-2607021600）。

## 実測収集（collect.cljs, ADR-2607021800）

```bash
nbb 70-tools/bmc/collect.cljs      # Cloudflare/Stripe/health → 90-docs/business/metrics/*.edn
# creds: env CF_API_TOKEN / STRIPE_SECRET_KEY → Keychain gftd.cf / 1Password
```

`collect.cljs` requires `gftd.traffic` (see below), so it needs `70-tools/bmc/src`
on the nbb classpath. The superproject root `nbb.edn` already lists it
(`{:paths ["." "scripts/nbb_compat" "70-tools/bmc/src" "70-tools/bmc/test"]}`),
so invoking `nbb 70-tools/bmc/collect.cljs` from the **superproject root** works
with no extra flags. If you invoke it from anywhere else (a different cwd, a
cron wrapper, a LaunchAgent `WorkingDirectory` that isn't the repo root), pass
the classpath explicitly:

```bash
nbb --classpath "70-tools/bmc/src:70-tools/bmc/test:." 70-tools/bmc/collect.cljs
```

business を回す 1 運転 = `collect.cljs` → 各 product `react loop` → `canvas md --all` → `score md` → commit。

### 導線計測の bot-probe 分離（gftd.traffic, ADR-2607231800）

`zone-top-paths` は Cloudflare の `edgeResponseStatus` で「実配信 page (2xx/3xx)」
とスキャナ probe (4xx/5xx) をまず分離する。それだけでは不十分な product
（network-isekai — Cloudflare Pages が unmatched path にも 2xx/3xx を返すため、
2xx/3xx bucket 自体が `/mailer.php` 等の bot probe で汚染される）向けに、
`70-tools/bmc/src/gftd/traffic.cljc` の `classify-path` が第二段の分類を行う:

- **`:channel`** — `channel-allowlist`（product ごとの既知 real route）に一致 → top-paths に採用。
- **`:probe`** — `probe-path-re`（product-agnostic スキャナ signature、`.php`/`wp-admin`/`.git`/`Dockerfile` 等）に一致 → top-paths から除外、率だけ計上。
- **`:unclassified`** — どちらにも一致しない → top-paths からは除外するが、率を surface する（捏造ゼロ — allowlist 未登録の新規 real route かもしれないので黙って捨てない）。

`channel-allowlist` に product が登録されていれば `top-paths-summary` の出力に
`probe(200-fallback) N%` / `unclassified N%` が追記される。未登録 product は
従来どおり status-only（`:ok`/`:status-mix` のみ）。新しい product / 新しい route
を追加するときは `gftd.traffic/channel-allowlist` を更新する（生成物ではなく
手で保守する real-route 一覧）。

## gate 評価器 + LLM advisor (ADR-2607022100)

```bash
70-tools/bmc/bin/gftd gate                 # 全 product の gate 状態 (validated/measuring/blocked + 不足計器)
```

ReAct loop のデフォルト advisor は `gate-aware-advisor` (mock + gate 評価)。gate が機械測定可能
(`{:metric :op :threshold}` / `:all`) で満たされれば hyp を validated に自動昇格、計器不足なら
`{:needs [...]}` を Solution ブロックに「準備:」to-do として提案する。これで schedule は dry でなく
毎朝「gate 測定 → 昇格 or 不足計器 surface」する kaizen サイクルになる。LLM advisor は
`react/llm-advisor` に `(fn [prompt]->string)` (langchain.model / murakumo text) を注入して差し替え。

## portfolio 予算配分 — entropic OT / Sinkhorn (ADR-2607194500)

```bash
70-tools/bmc/bin/gftd allocate                          # 端末表示（budget-supply.edn の総額を使用）
70-tools/bmc/bin/gftd allocate --budget 5000000          # 総額を上書き
70-tools/bmc/bin/gftd allocate --epsilon 0.1 --iters 100 # sinkhorn の epsilon / max-iters を上書き
70-tools/bmc/bin/gftd allocate --score-key bmc           # demand を BMC 成熟度スコアに差し替え（既定は yc）
70-tools/bmc/bin/gftd allocate md                        # portfolio-allocation.edn 再生成
70-tools/bmc/bin/gftd allocate write                     # 配分結果を <product>.metrics へ governor 経由で記録（任意）
```

正直な立ち位置（誇張しない）: 供給側は単一の予算プール（`90-docs/business/
budget-supply.edn` の `:supply/total-amount` 1 個）しか無いので、これは
optimal transport としては**退化ケース**（supply node が 1 個）— 数学的には
唯一の実行可能解が需要（`maturity-scores.edn` の `:yc`/`:bmc` スコア）に比例した
配分に一致し、cost 行列や epsilon は最終結果に影響しない。それでも
`gftd.allocate/sinkhorn` は log-domain stabilized な**一般 n×m Sinkhorn 解法**として
実装してあり（n=1 専用のハックではない）、floor/cap 制約は
water-filling（制約に触れた product を fix → 残りを再配分…を収束するまで
繰り返す）で解く。将来、予算を tranche（growth/infra/runway 等）に分けて複数
supply node にすれば非自明な輸送構造にそのまま拡張できる — **2026-08-06 に
`gftd allocate pools` として実装した**（下記、ADR-2608062300）。

demand 側の入力（`--score-key`）は `gftd.score` の成熟度スコアを流用している —
これは「現状手に入る中で最良の数値プロキシ」であって opportunity size（市場機会の
大きさ）の直接計測ではない。YC bench スコアは de-risked-ness（検証の進み具合）と
opportunity size を混同しうる注意点があり、より良い需要指標が出てきたら
`--score-key` を差し替えるだけで良いように設計してある。

正本:
```
90-docs/business/budget-supply.edn        (supply 側、maturity-facts.edn と同じ
                                            オーナー手編集ファイル。手で更新する)
90-docs/business/maturity-scores.edn      (demand 側、gftd score md の生成物)
生成物: 90-docs/business/portfolio-allocation.edn  (gftd allocate md; 手編集禁止)
```

`gftd allocate write` は任意の follow-up: 配分結果を funnel/proposals の
スナップショット方式と同じ dedup 挙動で `<product>.metrics` block へ
`:canvas/add-item` として記録する（governor 側の変更は不要 — `:canvas/add-item`
は既に allowed-actions に含まれている）。

## capital pools — released tranche だけを配分する (ADR-2608062300)

```bash
70-tools/bmc/bin/gftd allocate pools                     # 端末表示
70-tools/bmc/bin/gftd allocate pools md                  # capital-pools.edn 再生成
```

**`allocate` と `allocate pools` は答えている問いが違う。**

| | `gftd allocate` | `gftd allocate pools` |
|---|---|---|
| 問い | 予算があるとしたら需要比でどう割るか | **今いくら配れて、それはどこへ出せるか** |
| 入力 | `:supply/total-amount`（owner 未確認の placeholder） | `:supply/pools`（owner が ADR で決めた実額のみ） |
| supply node | 1 個（退化ケース） | tranche ごと（masked n×m） |
| 割り先 | 全 product、需要比 | ADR が名指しした eligible product だけ |
| 2026-08-06 実測 | 10,000,000 を 12 product へ | **300,000 を 1 product へ**（残り 2,700,000 は held） |

`--budget` は受け付けない。pool の総額は正本 ADR が決めるもので、CLI flag が
決めるものではない（ADR-2608062200 決定 1）。

**配れない金を配れる金と同じ数値に潰さない**のがこの経路の目的で、tranche を
4 クラスに分けて全部印字する:

| class | 意味 |
|---|---|
| `:allocating` | released・残額 > 0・行き先が宣言されている |
| `:stranded` | released・残額 > 0 だが行き先が ADR に書かれていない（`:undetermined`） |
| `:exhausted` | released だが `max - committed - spent ≤ 0` |
| `:held` | release 条件未達。**未割当ではなく意図された hold** |

`:undetermined` を「全 product に出せる」と読み替えない — そう読むと、ADR が
決めていない配分を allocator が勝手に決めることになる。

sinkhorn に渡すのは `:allocating` だけ。残りを質量ゼロの supply node として
渡さないのは意味論と数値の両方の理由による — 質量 0 の行は
`log(0) - logsumexp(全マスク行)` = `-Inf - (-Inf)` = **NaN** を作る
（`allocate_test.cljc` の BREAK 3 相当の検査で実際に NaN が出ることを確認済み）。

eligibility が分断されている場合（T1 は cloud-itonami にしか出せない、T2 は
別の product にしか出せない等）、需要比例の正規化は**連結成分ごと**に行う。
全体比例にすると transport 自体が実行不能になり、質量が消える（テスト
`pool-disjoint-eligibility-keeps-money-in-its-component` が、成分分割を外すと
100,000 が 7,317 に化けることを実測している）。

正本:
```
90-docs/business/budget-supply.edn の :supply/pools   (owner 手編集。各 pool は
                                                       id と正本 ADR を 1 つずつ持つ)
生成物: 90-docs/business/capital-pools.edn            (gftd allocate pools md; 手編集禁止)
```
