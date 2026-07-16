# ADR-2607071900: murakumo reconcile — cross-node apply を cljc だけで閉じる（Rust 非拡張）

**Status**: accepted (implemented)
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki（指示: 「rust はもう deprecated, prune. cljc です」—
cross-node auction の配線は Rust(kotoba-lattice) 側を拡張せず、murakumo の
control plane(clj/cljc)側だけで実装する）
**Scope**: `orgs/kotoba-lang/murakumo`

## Context

ADR-2606271600 は「fleet 横断の単一 lattice auction（cross-node peering）は
未配線」を既知ギャップとして明記していた。ADR-2607071500 追記3で、kenchi の
2nd replica を `nbb reconcile --apply` では収束できず、`nbb deploy … zebulun`
の imperative 経路で手動充足した実地確認が取れていた。

原因は `reconcile.clj` の apply 実装: `apply-app!` は app ごとに **1回だけ**
`deploy-fn`(= `cmd-deploy` を publish-node 無指定で呼ぶ)を実行し、
「gossipsub auction が他の desired ノードにも配置してくれる」ことに依存して
いた。ところがそのノード横断 auction 自体が実装されていない（ADR-2606271600）
ため、desired replica が 2 以上の app は `--apply` を何度実行しても
`:place`（不足)のまま収束しない。

一方で `reconcile/plan.cljc` の pure planner（`reconcile-app`）は、`pick-targets`
で **どのノードに置くべきか（least-loaded 優先）を既に計算済み**で、
plan の `:targets` に載せている。この情報が impure 側で単に捨てられていた
だけ、というのが実態だった。

オーナー指示は、この gap を埋める実装先として **Rust(kotoba-lattice) は
非推奨・整理対象であり、cljc（murakumo の既存 control plane）で実装せよ**、
というもの。

## Decision

**cross-node auction を Rust 側に実装しない。** 代わりに murakumo の
control plane が、既に pure core が選んだ `:targets` へ**自分で imperative
に deploy する**ことで、宣言的収束を成立させる。実行系（kotoba-server が
WASM を host する部分、`kotoba deploy`/`component build` の Rust CLI 呼び出し
自体）は変更しない — 変わるのは「誰が multi-node 配置の意思決定と実行を
担うか」で、答えは「murakumo（cljc/clj)」に一本化する。

### 実装（`kotoba-lang/murakumo`、merge `e2de4346`）

- **`reconcile/plan.cljc`**: 新設 pure `apply-targets` — `apply-apps`
  （`:place` な app の集合）を `(app, target)` ペアに **target ごとに展開**
  する。`pick-targets` が選んだ least-loaded ノードをそのまま使う。
  ```clj
  (defn apply-targets [plan]
    (vec (for [a (apply-apps plan) target (:targets a)]
           {:app a :target target})))
  ```
- **`reconcile.clj`**: `apply-app!`(1 app = 1 publish) を `apply-target!`
  （1 pair = 1 deploy）に置換。`(doseq [{:keys [app target]} (plan/apply-targets plan)] …)`
  で target ごとに `deploy-fn` を呼ぶ。
- **`core.clj`**: `deploy-fn` が固定 `pubsel=nil` だったのを `target` を
  渡すよう変更。`cmd-deploy` 自体（Rust `kotoba` CLI 呼び出し）は無変更 —
  既存の publish-node 引数をそのまま使う。
- **`report.cljc`**: 誤解を招く「auction will place」という文言の
  `apply-app-line` を、実態（murakumo 自身が target を選び deploy する）を
  表す `apply-target-line` に置換。
- **tests**: pure `apply-targets` の unit test 追加（heartbeat deficit=2 →
  2 pairs、relay → 1 pair）。`nbb test` **177 tests / 811 assertions green**。
  本番 fleet に対する `--dry-run` で既存 `satisfied`（kenchi 2/2、
  minidrama 1/1）が壊れていないことも確認。

## Rationale

- pure planner は既に正しい配置先を計算済みだった。欠けていたのは
  「計算した targets を使って実際に deploy する」たった一手— Rust 側の
  gossipsub プロトコル実装（cross-node peering、bidding sync 等）という
  大きなスコープの新機能は不要だった。
- murakumo は既に「1 ノード指定で deploy する」経路（`cmd-deploy`
  の publish-node 引数）を持っており、これは kenchi の手動収束
  （`nbb deploy … zebulun`）で実証済みだった。今回の変更はその実証済み経路を
  reconcile ループに繋いだだけで、新しい実行系は増えていない。
- Rust(kotoba-lattice) 側に real cross-node auction（gossipsub 越しの
  bidding 同期）を実装する案は、オーナー方針（Rust 非推奨・prune 対象）に
  反する上、スコープも実装コストも本 ADR の cljc 実装よりはるかに大きい。
  wadm 的な「宣言 → 収束」の意味論としては、murakumo 自身が placement
  decision の実行者になることは正当（ADR-2606271600 の
  `murakumo ≅ wash+wadm` 等価対応とも整合— wadm も実際には自身の lattice
  ではなくホストへの直接指示で収束させる）。

## Consequences

- (+) `nbb reconcile --apply` / `--watch` が、cross-node の multi-replica
  app を実際に収束できるようになった（Rust 側の変更ゼロ）。
- (+) `apply-target-line` の文言が実態を正確に表すようになった
  （運用者が「auction が勝手にやってくれる」と誤解しなくなる）。
- (−) 未解決のまま残るのは「observed → desired の差分が生じてから apply
  するまでのタイムラグ」と「`:over`（過剰配置）の auto-evict 非対応」——
  これは本 ADR のスコープ外（reconcile-app の既存の意図的な非対応、
  ADR-2607071400 の等価対応表に記載済み）。
- 今後 real cross-node lattice auction（gossipsub 上の分散 bidding）が
  必要になった場合も、Rust 側を拡張する前に「murakumo 側で imperative に
  やれないか」を先に検討する（本 ADR の方針を踏襲）。

## Related

- ADR-2606271600（kotoba-stack-equivalences）: cross-node auction 未配線の
  原典記録。
- ADR-2607071400（murakumo family k8s/wasmCloud+wadm positioning）: 本修正で
  「scheduler / pure core」行の "fleet 横断 auction は未配線" ギャップが
  一部解消（reconcile の apply 経路に限る）。
- ADR-2607071500（minidrama mesh reside 配線・追記1〜3）: kenchi 2nd replica
  の手動収束が、この gap の実地発見・実証になった。
