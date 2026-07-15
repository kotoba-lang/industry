# ADR-2607021600: portfolio canvas CLI（7 CLI / .cljc）と進化成長 ReAct loop

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ADR-2607021500 で 8 プロダクトの lean canvas を datoms EDN
（`90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn`）として整理した。
オーナー指示（2026-07-02）: これを md にも記載し、itonami / manimani /
murakumo / kotoba / aozora / e7m / gftd の各 CLI を .cljc で設計して canvas を
CLI で扱えるようにし、進化成長の ReAct loop を回せるようにする。

## Decision

`70-tools/bmc/` に **単一の .cljc engine + 7 つの product 束縛 wrapper** として実装:

1. **正本は event-sourced** — base datoms（ADR-2607021500、書き換え禁止）+
   append-only canvas ledger（`90-docs/business/canvas-ledger.edn`、1 行 1 EDN
   event）。有効な canvas = base に ledger events を fold したもの
   （`gftd.canvas/fold`）。md（`90-docs/business/<product>-business-model.md`）は
   `gftd canvas md --all` の生成物で手編集禁止 — west.yml と同じ「生成物」規約。

2. **7 CLI = 同一 engine の registry 束縛**（`gftd.cli/registry`）:
   itonami→cloud-itonami / manimani→cloud-manimani / murakumo→cloud-murakumo /
   kotoba→net-kotobase / aozora→app-aozora+app-aozora-yoro / e7m→etzhayyim /
   gftd→umbrella（ai-gftd-apex 含む全 product）。コマンド:
   `products` / `canvas show|md|add|retract|note` / `hyp list|pass|fail` /
   `react tick|loop` / `ledger show`。nbb wrapper（`bin/<cli>`）は classpath 追加と
   `(cli/-main-for :<cli> args)` のみ。

3. **進化成長 ReAct loop は CLAUDE.md Actors パターン準拠**（`gftd.react`）:
   - observe: fold 済 canvas + hypotheses + metrics（`90-docs/business/metrics/
     <product>.edn` ‖ `--metrics k=v`）
   - think: advisor は **proposal のみ**返す。既定 = deterministic mock advisor
     （untested riskiest 仮説→gate を Key Metrics へ昇格、refuted→UVP へ pivot
     検討、`:signal` metric→Problem へ観測事実。全 rule が現 canvas に対して
     dedupe されるため loop は dry に収束する）。LLM advisor は
     `(fn [observation] proposals)` の注入で差替（swap 境界）。
   - act: **独立 governor が全書込を検閲** — 許可 action 限定 / 重複 item /
     最終 item の retract / evidence 無しの hyp 遷移 / **etzhayyim Funding への
     営利項目（take rate・広告・carry・課金）** を拒否。可決も拒否も ledger に
     積む（`:governor/rejected`）。**人手の `canvas add` 等も同一 governor を通る**。
   - 1 run = 1 tick（有界）。`react loop --max-ticks N` は budget 有界の durable
     outer loop で、proposal が尽きたら dry 終了。

4. **検証済**: `nbb 70-tools/bmc/run-tests.cljs`（5 tests / 15 assertions green:
   fold・ledger roundtrip・governor 6 不変条件・loop 収束・md render）。実走:
   murakumo `react loop` が tick1 で gate 昇格→tick2 dry、e7m への広告収益追加が
   governor 拒否（exit 1）、`gftd canvas md --all` が 8 md を生成。

## Consequences

- (+) canvas の全変更が誰（cli:X ‖ advisor:mock ‖ 将来 LLM）によるものか
  ledger で監査可能。lean canvas 自体が actor パターンの被統治対象になった。
- (+) LLM advisor の差し込み口が確定（observation → proposals の純関数）。
- (−) 現状は superproject 内の 70-tools 実装。三組織タクソノミ（ADR-0020）上は
  再利用部品 = com-junkawasaki 子リポ（例 `bmc-clj`）へ split し、各 product
  repo から参照するのが本来形 — follow-up。
- (−) 生成 md の各 product repo（docs/BUSINESS-MODEL.md）への配信も follow-up
  （net-kotobase は既存 BUSINESS-MODEL.md が正のため追記整合が必要）。
- (−) cljs/WASM 面は io 未実装（core は純 cljc なので kotoba pod 化は容易）。

## References

- ADR-2607021500（7 レイヤー business model / lean canvas、datoms 正本）
- `70-tools/bmc/README.md`（使い方）
- CLAUDE.md「Actors」節（proposal ⊣ governor ⊣ 不変台帳 / durable outer loop）
- ai-gftd-shinshi `docs/260613-bmc-lean.datoms.edn`（canvas datoms 形式の先行例）
