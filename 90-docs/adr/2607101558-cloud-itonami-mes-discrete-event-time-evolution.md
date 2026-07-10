# ADR-2607101558: cloud-itonami MES — 工場プロセスの離散事象時間発展シミュレーション

- **Status**: proposed
- **Related**: ADR-2607110900（ロボット接触力学、着手順1番目）、ADR-2607101525（OpenUSD、2番目）。本ADRは3番目=cloud-itonami製造sim。ADR-2607011000（cloud-itonami CACAO — 「1 mission = 1 bounded operation, no internal loop」の設計方針の出典）

## Context

オーナーから「OpenUSD・URDFロボティクス・cloud-itonami製造simの完成度」を問われた先行調査、およびそれに基づくADR-2607110900/2607101525の完了を受け、3番目の領域に着手する。着手前に、他2領域（ロボット接触力学でエージェント報告のハルシネーション、OpenUSDで並行作業による前提の陳腐化）で得た教訓に従い、**自分で実際のソースを直接読んで**現状を検証した（`gftdcojp/cloud-itonami`を新規cloneして確認、先行調査のsummaryをそのまま信用しない）:

- `src/cloud_itonami/mes.cljc`（全141行）: `kyber-plm`の生産完了イベント（`complete-production!`の戻り値）を itonami の activity/effect/audit tx-data 語彙へ投影するだけの**純粋なデータ変換モジュール**。`work-order-artifact`/`production-activity`/`backflush-effect`/`completion-audit`/`kyber-completion->tx`/`kyber-production-ocel->activity`の6関数のみで、いずれも**1件の完了イベントを受け取って1件のtxを返す**——時間発展・スケジューリング・キューイング・工程順序・設備占有といった概念は一切存在しない。
- `src/itonami/sim.cljc`（全70行、`-main`のデモドライバ）・`src/cloud_itonami/business_loop.cljc`（`tick!`、228行）: いずれもこの組織のActor設計方針そのもの（CLAUDE.md「Actors」節、ADR-2607011000）を体現している——**「1 run = 1 bounded operation/tick」**（`business_loop.cljc`のdocstring: "1 run = 1 tick (CLAUDE.md Actors: no unbounded inner loop)"）。時間を進める「outer loop」は意図的にコード外（cron/launchd/`clojure -M:business tick-all`）に置かれ、リポジトリ内には**工程の時間発展を内部でシミュレートするループが構造的に存在しない**——これは実装漏れでなく明示的な設計方針（封じ込め+独立governor原則）。
- `deps.edn`確認: `com-nvidia-isaac-sim`/`org-openusd`と異なり、本repoは`{:local/root "../../kotoba-lang/..."}`形式で16個のsibling checkout（langchain/langgraph/mail/mailer/tayori/teian/koyomi/goyoukiki/shoko/ichiran/kaisha/denrei/org-chainagnostic-cacao/com-cloudflare/plm/product-party）を要求する——単独cloneではテストが動かせない。実装フェーズでは superproject 外の一時 worktree に `west init -l manifest` して topdir を固定した上で、これらsiblingを`west update`する必要がある（CLAUDE.md「agent 専用 worktree で west を動かすときの topdir 固定」節）。
- 結論: cloud-itonami製造の「工場プロセスの時間発展」は**文字通り何も実装されていない**（先行調査の要約通り、かつ本ADR着手前の直接検証でも同じ結論——他2領域と異なり前提の食い違いは無かった）。

## Decision

### D1. スコープは「離散事象シミュレーション（discrete-event simulation）」の最小コアに限定する — 資源制約job-shopスケジューラは作らない

ADR-2607110900のM2（ロボット接触力学を「既存PGSソルバーの配線」でなく「単一自由剛体+静的障害物」という最小スコープに絞った判断）と同型の判断をする。工場プロセスの時間発展として実装するのは、**work order（作業指示）が固定順序の少数のstation（工程）を、各stationは単一キャパシティ・FCFS（先着順）・固定処理時間で流れていく、古典的なnext-event time-advanceアルゴリズムによる離散事象シミュレーション**——確率的処理時間、設備故障/ダウンタイム、複数資源の競合、経路最適化、動的スケジューリングは一切やらない。event queue（時刻順のイベントヒープ）とイベント処理（到着→station開始→station終了→次stationへの到着 or 完了）という、シミュレーションの核だけを実装する。

### D2. シミュレーションの最終出力は既存の`cloud_itonami.mes/kyber-completion->tx`にそのまま渡せる形にする

新しいシミュレーションコアを孤立したおもちゃにしない。work orderが全stationを完了した時点のイベントから、既存の`kyber-completion->tx`が期待する引数形（`:item :completed :consumed :wip-cleared :finished-value :at :source-id`相当）を組み立てられるようにする——シミュレーション結果が実際にitonamiのactivity/effect/audit tx-dataとして記録可能であることを実際にテストで確認する（`mes.cljc`は変更しない、既存の実装・テスト済みコードをそのまま呼ぶだけ）。

### D3. 検証は閉形式で計算可能な決定論的ケースに限定する（この組織の一貫した検証手法）

ADR-2607110900のM2で採用した「実測の閉形式物理」と同じ手法。処理時間を固定値にすることで、以下が手計算/理論値と一致することをテストで確認する:
  - 単一work order・単一station: 完了時刻 = 到着時刻 + 処理時間。
  - 複数work orderが単一stationに同時到着: FCFS待ち行列の完了時刻が階段状（t=p, 2p, 3p, …）になる。
  - 2つの直列station: station 1完了前にstation 2が開始しないこと、station 2が先客で塞がっている間はstation 1完了後もblockingで待たされること。
  - 全work order完了後のmakespan（総所要時間）・各stationの稼働率が理論値と一致すること。
IsaacSim/実際のMESシステムとのライブ差分比較は行わない（この組織の一貫した非目標、D1のNon-goals参照）。

### D4. 依存関係セットアップは実装フェーズで一時worktreeを使う（superproject本体を汚さない）

`deps.edn`が要求する16 sibling checkoutをすべて揃えるため、実装時はsuperproject本体の外に一時worktreeを作り、`west init -l manifest`でtopdirを固定した上で`west update --fetch smart cloud-itonami <16 siblings>`する（CLAUDE.md標準手順）。新規モジュール自体は追加のsibling依存を増やさない（既存の`cloud_itonami.mes`/`cloud_itonami.activity`のみ利用）。

## Milestones

- **M1**: `cloud_itonami/mes/simulate.cljc`（仮称）— event queue・station定義・work order定義・next-event time-advanceの本体。D3の4パターンをテストで検証。
- **M2**: シミュレーション完了イベント → `kyber-completion->tx`互換の引数マップへの変換関数。既存`mes_test.cljc`のfixtureと同じ形のデータで実際に`kyber-completion->tx`を呼び出し、activity/effect/auditが生成されることを確認するテスト。
- **M3**: （stretch、必要性が実際に出た場合のみ）2 station超・station毎キャパシティ>1への一般化。M1/M2で十分な価値が出れば、ここで打ち切ってもよい。

## Non-goals（明示的にやらないこと）

- 確率的（stochastic）処理時間・到着過程——決定論的固定値のみ（D3、閉形式検証を維持するため）。
- 設備故障・段取り替え（changeover）・保全（maintenance）時間のモデル化。
- 複数資源（人員+設備等）の同時競合・優先順位付けディスパッチルール（SPT/EDD等）。
- 経路最適化・動的スケジューリング・強化学習ベースのディスパッチ。
- StateGraph Actor（governor付き）としてのラッピング——本ADRはシミュレーションコアのみ。Actor化が必要になれば別ADR。
- 実際のMES/ERP/SCADAシステムとのライブ統合・IoTセンサーデータ取り込み。

## Consequences

- `cloud_itonami.mes`に閉じた追加のため、既存consumerへの影響はない（`kyber-completion->tx`のシグネチャは変更しない）。
- D1のスコープにより実装リスクは小さい——event queueとFCFS単一station流れという、教科書的にも実装例が豊富な最小核。
- D4により、実装フェーズの最初の作業は「一時worktreeでの16 sibling取得」という段取りコストが発生する（ロボット接触力学/OpenUSDでは不要だった）。

## Open Questions / Follow-up

- M3（複数キャパシティ・station数の一般化）の要否は、M1/M2が実際に説得力のあるデモになるか確認してから判断する。
- 将来、実際のwork-order/routingデータ（オーナーの実際の製造現場データ等）が手に入った場合、決定論的固定値からどう橋渡しするかは、その時点で再評価する。

## Related

- ADR-2607110900（ロボット接触力学、着手順1番目 — 同型の「最小スコープ」判断の先例）
- ADR-2607101525（OpenUSD、2番目）
- ADR-2607011000（cloud-itonami CACAO — 「1 mission = 1 bounded operation」設計方針の出典）
