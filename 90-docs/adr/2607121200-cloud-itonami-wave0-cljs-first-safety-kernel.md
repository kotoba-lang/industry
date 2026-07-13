# ADR-2607121200: Wave 0 金融 vertical は CLJS + kotoba-datomic 第一・safety kernel 抽出で進める(wasm 複製は保留)

**Status**: accepted (reference implementation landed)
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-{6411,6419,6420,6430,6491,6492,6499,6511,6512,6520,6530,6611,6612,6619,6621,6622,6629,6630}`(Wave 0 金融 18 repo)
**Amends**: ADR-2607121000(P0 の「6492/6511 wasm パターン複製」を本 ADR の経路に差し替え)

## Context

ADR-2607121000 の P0 は「6492/6511 の **wasm** パターンを Wave 0 金融残り 16 classes へ複製」
としていたが、オーナー指示(2026-07-12)により方針変更:

> wasm にこだわらずにまずは clojurescript, kotoba datomic で動くように進めて。
> ただ kotoba-lang/kotoba の safety kotoba cljc で安全な設計実装で。

事前調査(2026-07-12)で判明した実態:

- Wave 0 金融 18 repo は**全て src + テストを持つ**(skeleton ゼロ)。src は既に
  CLJS-clean な `.cljc`(JVM interop ゼロ、reader conditional 完備)。
- 欠けていたのは (1) テストハーネスが JVM 専用(cognitect runner)
  (2) governor の判定核が façade 級(keyword/map ベース)で kernel 級でない
  (3) store の Datomic seam(`langchain.db`)が CLJS 下で未検証。
- 「safety kotoba cljc」の実体は cloud-itonami 本体の
  `kernels/*.{cljc,kotoba}` 規律(ADR-0016 / ADR-2607101200 vocabulary lock):
  integer-coded・fail-closed・named combinators・battery + case-count lock・
  emit-ready subset(defn/def/nested if/=/</整数演算のみ、keyword/string/map/
  atom/interop/IO 禁止)。

## Decision

1. **Wave 0 金融 vertical の第一実行経路は ClojureScript**(runtime priority
   準拠: kotoba wasm > clojurewasm > CLJS > nbb、JVM は compat)。同一の
   portable `.cljc` suite を `cljs.main --target node` で回す
   `portable-cljs-test-runner` を各 repo に置く。JVM suite は secondary。
2. **判定核は safety kernel として抽出する**: governor / phase gate の決定部を
   `<domain>.kernels.gate`(safe-kotoba subset の integer-coded `.cljc`)に置き、
   façade は evidence 収集と keyword↔wire-code 変換のみ。fail-closed 強化
   (非 0/1 flag は violation 扱い、範囲外 confidence は escalate、範囲外 phase は
   kernel 内で write 権ゼロ)。battery + case-count lock + façade との full-matrix
   parity テストで固定。
3. **`.kotoba`/wasm emission は保留**(削除ではない): kernel を subset 内に保つ
   ことで、後日の emit は「書き直し」でなく「追加」になる。既存の 6492/6511 の
   wasm 成果物は温存。
4. **kotoba-datomic は `langchain.db` の Datomic 互換契約を境界とする**:
   store contract test(MemStore / DatomicStore 両バックエンド)を CLJS 下で
   通すことを「kotoba datomic で動く」の M1 とする。実 kotoba-server /
   kotobase.net への retarget は `langchain.kotoba-db`(portable、`:http-fn`
   注入)+ `kotobase.cacao`(CLJS で self-mint)経由の設定変更として M2 に切り出す
   (kotobase-client / kotobase-cljc-worker の in-process handler が試験経路)。

## Reference implementation(landed 2026-07-12)

`cloud-itonami-isic-6511` main `4c36925`:

- 新規 `underwriting.kernels.gate`(battery 38 cases、lock 付き)
- `underwriting.governor/check`・`underwriting.phase/gate` が kernel 委譲
  (162-combo parity matrix・confidence floor 境界 0.59/0.60・定数 drift pin)
- テスト 6 本 `.clj`→`.cljc` + `underwriting.portable-cljs-test-runner` +
  deps `:cljs` alias
- 検証: **CLJS 37 tests / 462 assertions 0 failures**(store contract の
  MemStore/DatomicStore、langgraph interrupt/resume 含む)、JVM 同値、
  clj-kondo 0 errors

## Rollout(残り 17 repo)

6511 のパターンをそのまま複製する。優先順は ADR-2607121000 の価値ランキング:
6419/6492(与信・銀行)→ 6512(損保)→ 6611/6612(市場)→ 6520/6530 → 残り。
各 repo とも (1) kernels/gate.cljc 抽出 (2) テスト .cljc 化 + CLJS runner
(3) store contract の CLJS green を完了条件とする。6499(モジュール 14 個)のみ
kernel 分割(concentration/waterfall/nav が独立 kernel 候補)を許容する。

## Consequences

- (+) 18 repo の安全判定核が「LLM にも人間にも読める 100 行の integer 関数 +
  実行可能 battery」に収束し、監査・複製・(将来の)wasm emit が同一物から出る。
- (+) CLJS green により、これらの actor は murakumo lattice の CLJS/edge 経路・
  browser cockpit へ持ち込み可能になる(wasm を待たない)。
- (−) kernel は façade より厳格(fail-closed)なので、複製時に既存テストが
  境界ケースで割れることがある。それは kernel が正:壊れたテストの期待値を直す。
- (−) M2(実 kotoba-server 接続)は本 ADR のスコープ外。kotobase-client が
  実質 CLJS-only(bare js/fetch)である点と async store protocol 化の設計判断が
  残っている。

## Addendum (2026-07-12 same-day): rollout 完了 — 18/18 repo 着地

参照実装(6511)公開の同日中に、残り 17 repo への複製を 6 並行バッチで完了した。
全 repo: origin/main 着地・working tree clean・feature branch 削除済み・
kernel + parity matrix + CLJS runner 完備・**JVM = CLJS 同値 green・kondo clean・
既存テストの挙動変化ゼロ**(fleet 共通の fail-closed 強化のみ)。独立検収:
18 repo の同期/構成確認 + 6630 CLJS 抜き打ち再実行(61/585 一致)。

| repo | battery | JVM=CLJS tests/assertions | merge |
|---|---|---|---|
| 6411 reserve | 54 | 56/630 | `02d3a94` |
| 6419 banking | 43 | 51/604 | `cc3e5b2` |
| 6420 holdco | 48 | 48/614 | `3616bf0` |
| 6430 trustfund | 43 | 52/587 | `fec5b3e` |
| 6491 leasing | 49 | 39/487 | `02157fc` |
| 6492 credit | 52 | 43/544 (CLJS 40/541 — wasm/Chicory テストのみ JVM 残置) | `2389623` |
| 6499 vcfund | 70 | 185/1581 | `f188560` |
| 6511 underwriting(参照) | 38 | 37/462 | `4c36925` |
| 6512 casualty | 44 | 47/647 | `f37f7d5` |
| 6520 reinsurance | 41 | 43/577 | `31f68df` |
| 6530 pension | 45 | 47/662 | `b29de2c` |
| 6611 marketadmin | 46 | 45/589 | `9321248` |
| 6612 brokerage | 49 | 50/652 | `9d5544f` |
| 6619 card | 51 | 44/593 | `57ef7d8` |
| 6621 adjustment | 38 | 37/467 | `1dd380f` |
| 6622 intermediation | 52 | 48/600 | `1d2133d` |
| 6629 auxiliary | 36 | 37/415 | `dac27e1` |
| 6630 fundmgmt | 56 | 61/585 | `e2b2897` |

fleet 合計: **battery 855 cases / テスト 910 / assertions 10,997** が両ランタイムで green。

数値判定の kernel 化で確立した技法(以後の wave でも踏襲):
- **整数外積比較**(6492 DTI: `100×(債務+申込) > 43×収入`、6491 担保率):丸め×100 は
  境界で façade と乖離しうるため、生整数の外積で厳密一致させる。
- **スケール整数 ±1 トレランス**(6612 cent x100、6630/6499 micro-unit x1e6):旧 float
  epsilon の整数再表現。granularity は repo 自身の宣言 noise floor に合わせる。
- **kernel 化しない判定の基準**: per-要素コレクション走査・データ対データ再計算
  (6629 の共同海損按分等)は façade に残し、スカラー比較/flag だけ kernel が所有。

要調和(軽微・実害なし): 予約 op-0 レーンの扱いが 2 規約に分かれた
(6511/6512/6520/6530/6499 は read pass-through、read-ops 空の repo は no-rights)。
どちらも façade から到達不能で各 parity oracle が pin 済み。fleet 統一は将来の
一括パスで行う(挙動差ゼロのため急がない)。

## References

- ADR-2607121000(逆トポロジーソート計画 — 本 ADR が P0 実行手段を修正)
- cloud-itonami ADR-0016(runtime priority)/ ADR-2607101200(vocabulary lock)
- `orgs/kotoba-lang/kotoba/docs/ADR-safe-capability-language.md`(subset 定義)
- 本 ADR とペアの `.edn`
