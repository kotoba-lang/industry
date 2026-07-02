# ADR-2607020100: kotoba-lang/dcs 新設 + kotoba-lang/plm の west 登録

**Status**: accepted
**Date**: 2026-07-01
**Deciders**: Jun Kawasaki

## Context

オーナーから「kotoba-lang org に dcs, plm はあるか」と問われ調査した結果:

- **`kotoba-lang/plm`** は GitHub 上に既存（public, `Open-source PLM
  workbench as EDN data + portable CLJC lifecycle engine.`）。
  `resources/plm/domain.edn`（データレジストリ）+
  `src/kotoba/plm/core.cljc`（純 CLJC ドメインエンジン）+
  `src/kotoba/plm/runner.clj`（保守的 host dry-run runner）+
  `docs/index.html`（GitHub Pages ワークベンチ、reagent/re-frame）という
  「kotoba industrial-app pattern」で既に実装済みだったが、
  `manifest/repos.edn`（west の SSoT）には未登録だった。
- **`kotoba-lang/dcs`** は GitHub 上に存在しなかった（DCS = Distributed
  Control System。plant/tag/loop/alarm の分散制御系ドメインは
  `kotoba-lang/ddl`/`dmn`/`bpmn`/`cmmn` 等の既存ドメインモデル群と
  重複しておらず、`kotoba-lang/robotics`（mission/action/safety-governor、
  ADR-2607011000）とも別レイヤ — DCS は plant の regulatory control
  （BPC）、robotics は actor の行動ガバナンスで責務が異なる）。

## Decision

1. **`kotoba-lang/plm` を west manifest に登録**。実体は変更しない
   （既存実装をそのまま活かす）。

2. **新規 `kotoba-lang/dcs`**（public, Apache-2.0）を、`kotoba-lang/ddl` /
   `dmn` と同じ規約（フラット namespace `dcs.*`、サードパーティ実行時 dep
   ゼロ、`.cljc` コア + host-injected ports、`cognitect.test-runner` +
   `clj-kondo`、GitHub Actions CI）で作成:
   - `dcs.model` — plant/area 階層 + I/O tag（`:ai`/`:ao`/`:di`/`:do`）+
     PID 制御ループ + アラームの EDN レジストリとビルダー。
   - `dcs.validate` — 構造検証（タグ参照切れ、種別不一致、setpoint/output
     limits の range 逸脱、負のチューニング、アラーム閾値順序
     `hi-hi > hi` / `lo-lo < lo`）。例外を投げず問題リストを返す。
   - `dcs.pid` — 純関数 PID（位置形、クランプ付きアンチワインドアップ、
     manual→auto のバンプレス転送）。
   - `dcs.alarm` — ISA-18.2 準拠のアラーム状態機械
     （`:normal→:unacked→:acked→:normal`、`:rtn-unacked` 分岐、
     deadband ヒステリシス）。
   - `dcs.execute` + `dcs.ports` — host-injected `IFieldIO` 越しの純粋な
     scan-cycle エンジン（`dmn.execute`/`dmn.ports` と同型のポート注入
     パターン）。ライブラリ自体は I/O を一切行わない。
   - `dcs.runner`（JVM `.clj`）— 保守的な host dry-run runner。system を
     検証し、有効なら in-memory `IFieldIO` に対して N scan cycle を
     シミュレートする（実 fieldbus/ネットワーク I/O は行わない）。
   - Scope は BPC（基本プロセス制御）のみ。SIS（安全計装システム）の
     interlock/trip は別ライセンス/認証が要る独立領域として対象外。
   - 29 test / 56 assertion 全て green、`clj-kondo` 0 error/warning を
     初回コミット時点で確認。

3. **`manifest/repos.edn` の `:extra-projects` に両方を追加**し
   `bb scripts/gen-west-manifest.bb` で `manifest/west.yml` を再生成、
   `--check` で canonical 一致を確認。

## Consequences

- (+) DCS ドメイン（tag/loop/alarm/PID/scan-cycle）が
  `kotoba-lang/robotics` の mission/action/governor 契約と分離される
  （前者は plant の regulatory control、後者は actor の行動ガバナンス）。
- (+) PLM の既存実装が west 管理下に入り、他 project と同じ
  `west update` 経路で同期できるようになる。
- (−) `kotoba-lang/plm` は `kotoba.plm.*` namespace + shadow-cljs/reagent
  UI という、`dcs`/`ddl`/`dmn` とは異なる（より新しい「kotoba
  industrial-app pattern」）規約のままで、本 ADR では統一しない
  （既存実装の書き換えは本 ADR の範囲外）。
- (−) `dcs` は SIS/interlock を持たないため、実プラントの安全計装要件を
  カバーしない。将来必要になれば別 repo（`kotoba-lang/sis` 等）に分離する
  想定（`kotoba-lang/robotics` との既存の責務分離パターンを踏襲）。

## References

- ADR-2607020000 — Giemon 製品ライン（同型の「新規 kotoba-lang repo 作成
  + manifest 登録」ワークフローの直近の先例）
- `orgs/kotoba-lang/dcs`（本 ADR で新設）
- `orgs/kotoba-lang/plm`（既存実装、本 ADR で west 登録）
- `orgs/kotoba-lang/ddl` / `orgs/kotoba-lang/dmn`（namespace/deps.edn/CI の
  規約を踏襲した先例）

## Addendum (2026-07-02): kyber-plm ドメインの kotoba-lang/plm への統合

オーナー指示「kyber plm の話は古い。kotoba-lang/plm に lib、ビジネスとしては
itonami の repo に」に基づき、ADR-2607023000 の3層分解を PLM にも適用した:

- **コード (kotoba-lang/plm、merge a3d59bf)**: cloud-itonami の `kyber-plm.*`
  純ドメイン 10 ns を `kotoba.plm.*` として統合（`kyber-plm.plm` →
  `kotoba.plm.item`）。store は zero-dep の protocol/KotobaStore と
  `store-datomic`（Datomic Local、`:datomic`/`:test` alias opt-in）に分割。
  既存の lifecycle engine (`kotoba.plm.core`) / workbench とは共存し、
  reagent 系 UI deps は `:cljs` alias に隔離（shadow-cljs は
  `:deps {:aliases [:cljs]}`）。phase2/thread テストも lib へ移管
  （9 tests / 40 assertions green）。
- **商売 (gftdcojp/cloud-itonami、merge 5093a48)**: kotobase kg.ingest
  projection を `cloud-itonami.kotobase-kg`、live 運用 CLI を
  `cloud-itonami.plm-export` として残置し、`src/kyber_plm` は消滅
  （kyber 名の退役完了）。lib は `:local/root` で消費。230 tests /
  2234 assertions、0 failures（4 errors は pristine main と同一の
  live-credential 依存テスト = 既存問題）。
- 併せて kotoba-lang/mail の pin 遅れ（`mail.inbound` 欠落で cloud-itonami
  の test suite が classpath 段階で壊れていた既存問題）を upstream main
  (77508c8) へ前進して解消。
