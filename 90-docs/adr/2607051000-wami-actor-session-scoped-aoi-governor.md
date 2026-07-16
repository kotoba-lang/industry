# ADR-2607051000: wami-actor — Wide Area Motion Imagery を session-scoped TrackAdvisor ⊣ AOIGovernor actor として実装

**Status**: closed(設計 + 最小スケルトン実装が完了・検証済み。下記 Verification 参照)
**Date**: 2026-07-04
**Closed**: 2026-07-04
**Deciders**: Jun Kawasaki

## Context

「Wide Area Motion Imagery (WAMI) を gftdcojp として設計実装してほしい」という
依頼を受けた。WAMI は「広域を継続撮影し、多数の移動物体を同時追跡する」技術で、
本質的に **監視色の強いドメイン**（軍事 ISR 起源の Gorgon Stare / ARGUS-IS 系、
都市全域の無差別・無期限追跡は市民的自由の観点で強い懸念を伴う）。実装前にオーナーへ
用途を確認したところ:

- **想定用途**: (2) 防災・捜索救助の解析、(3) 施設内の防犯・監視（gftdcojp
  顧客向け、`ai-gftd-mamori` 系隣接）の **2 用途**。いずれも「限定された区画・
  期間・目的」で境界を持つ用途であり、「都市全域を無期限・無目的に追跡する」
  という WAMI の最も懸念される用法ではない。
- **実装スコープ**: 設計（ADR）+ 実装は最小スケルトン。実際の空撮モザイキング/
  移動物体検出アルゴリズムは対象外（既存 3 actor と同じく decision advisor は
  決定的 mock、real backend は port 差し替えの follow-up）。

actor の作法は本 workspace の同型例に従う: `gftd-talent-actor`
（HR-LLM ⊣ PolicyGovernor）、`ai-gftd-newscaster`（anchor-LLM ⊣
EditorialGovernor、ingest/produce の二流路 StateGraph + ports.cljc の
Sensor/Notifier/Publisher 分離）、`cloud-itonami`（ops-LLM ⊣ CertGovernor）。
封じ込めた知能ノードは proposal のみ、独立 governor が検閲、append-only 台帳、
Store/Advisor/Phase 注入、langgraph-clj StateGraph、1 run = 1 操作。

## Decision

**新規 actor repo `gftdcojp/wami-actor`（west path `orgs/gftdcojp/wami-actor`）
を起こし、TrackAdvisor（封じ込めた視覚モデル）⊣ AOIGovernor 型の actor として
実装する。** WAMI 固有の懸念（無期限・無境界の追跡）に対する構造的な防御を
**AOIGovernor の HARD 不変条件**として actor の型に埋め込むことで、"都市全体を
無差別に監視するダッシュボード" には構造的になり得ないようにする。

1. **すべての操作は `session` にスコープされる（監視の単位 = session）**。
   session は data（`wami.store` の `:sessions`）: `:purpose`
   （`:facility-security` | `:disaster-response` の 2 値のみ — それ以外の
   用途を追加するには別途 ADR + governor 拡張が要る）、`:aoi`（対象区画）、
   `:retention-ticks`（保存上限）、`:legal-basis`（契約/災害対策本部指示等の
   根拠文字列）、`:operator-id`、`:status`。**session を経由しない検出/照会/
   通報/公開は構造的に存在しない。**
2. **二流路の StateGraph（`wami.operation`）** — newscaster と同型:
   - ingest（record-op、常時 ON・LLM 無し）: `:session/register`、
     `:frame/ingest`（撮影フレームの ground datom 記録のみ。モザイキング/
     実センサ結線は mock port の follow-up）。
   - produce（assess 経路）: `:track/detect`（匿名 track 提案）→
     `:track/query`（AOI 内既存 track の照会）/ `:alert/propose`
     （facility-security 専用の境界侵入等アラート提案）/ `:report/compose`
     （disaster-response 専用のインシデント要約提案）。TrackAdvisor（封じ込め）
     が proposal を返し、AOIGovernor が検閲、phase gate、**alert 配信 /
     report 公開は常に人間承認**（`interrupt-before`）。
3. **AOIGovernor の HARD 不変条件**（人間でも上書き不可）:
   - **session-scope** — 対象 session が存在し `:open` かつ `:legal-basis`
     が空でないこと。
   - **purpose-scope** — op の種類は session の宣言済み `:purpose` にのみ
     許可（`:alert/propose` は facility-security 専用、`:report/compose` は
     disaster-response 専用 — **防犯データを防災目的に、防災データを防犯目的に
     転用できない**）。
   - **anonymization** — track 提案は `:identity` を持ってはならない
     （顔/ナンバープレート/氏名の個体識別を構造的に禁止。phase ≤3 では
     個体識別機能そのものを実装しない）。
   - **no-cross-session-reference** — 提案が引用する frame/track は必ず
     **同一 session** に属する（他 session の frame/track を引用不可 =
     複数拠点を横断する永続追跡グラフを構造的に阻止）。
   - **retention-ttl** — session の `:retention-ticks` を超えた frame/track
     への参照は拒否（**無期限アーカイブの禁止** = 移動履歴の永久保存を防ぐ）。
   - **no-actuation** — proposal の `:effect` は必ず `:proposal`。actor 自身は
     一切 dispatch/publish しない — 人間承認後に Notifier/Responder port
     だけが行う。
   SOFT: confidence floor(0.6) → escalate。`:alert/propose` /
   `:report/compose` は **常に high-stakes**（confidence/phase に関わらず
   必ず人間承認 — newscaster の `:episode/publish` と同型）。
4. **Phase 0→3**: 0 = ingest-only（記録のみ、検出は shadow）/ 1 = assisted
   （検出/照会も含め全て人間承認）/ 2 = assisted-detect（`:track/detect` のみ
   auto-commit）/ 3 = supervised（`:track/detect` + `:track/query` が
   auto-commit。**alert/report は phase に関わらず常に人間承認**）。
5. **注入 port（swap）**: Store（`MemStore` ‖ `DatomicStore`、langchain.db
   `:db-api`）/ Advisor（`mock-advisor` ‖ 実視覚モデル、`langchain.model`）/
   Sensor（合成フレーム mock ‖ 実カメラ/UAV フィード、将来）/ Notifier
   （mock ‖ 実際の警備オペレータ通報経路、承認後のみ）/ Responder（mock ‖
   実際の被災対応機関 API、承認後のみ）。
6. **台帳 = WAMI 監査台帳（append-only）**。session 登録/フレーム記録/検出/
   照会/hold/人間承認/配信/公開の全 disposition を積み、「いつ・どの区画で・
   何を根拠に・誰が承認して・誰に配信/公開したか」を不変に残す（監視範囲の
   説明責任そのものが台帳の存在理由）。

## Verification (closing, 2026-07-04)

| 項目 | 状態 | 備考 |
|---|---|---|
| repo `gftdcojp/wami-actor` 作成 + push | ✅ 完了 | private、`gh repo create --source=. --push`（`https://github.com/gftdcojp/wami-actor`, commit `4750b24`） |
| `src/wami/{store,trackllm,governor,phase,ports,operation,sim}.cljc` + `test/wami/*_test.clj` | ✅ 完了 | Store(MemStore‖DatomicStore)/Advisor/AOIGovernor/Phase/Ports/StateGraph/demo をすべて実装 |
| `clojure -M:dev:test` | ✅ 完了 | **20 tests / 54 assertions / 0 failures** — session-scope・purpose-scope・anonymization・no-cross-session-reference・retention-ttl・no-actuation の6 HARD 不変条件、alert常時human-signoff、Mem≡Datomic parity、phase 0→3 rolloutをすべて実行時に確認 |
| `clojure -M:dev:run`（offline demo） | ✅ 完了 | ingest→detect(auto-commit)→alert propose(interrupt)→human signoff→dispatch→wrong-purpose report attempt(hold: purpose-scope)を実行し監査台帳を出力、設計どおりの挙動を目視確認 |
| `clojure -M:lint`（clj-kondo） | ✅ 完了 | errors: 0, warnings: 0 |
| `manifest/repos.edn` `:extra-projects` 登録 + `nbb scripts/gen-west-manifest.cljs --entry wami-actor` | ✅ 完了 | 新規 entry、pin `4750b24cbfa0` は main から到達可能と検証（`verify-west-pins` OK）。diff は当該 entry のみ（west.yml +5 行） |
| superproject `com-junkawasaki/root` main へ反映 | ✅ 完了 | ADR pair + manifest 変更を commit `aaf5e0b69d14` として push（事前に `origin/main` との乖離なしを確認済み） |

残作業（本 ADR が明示的に scope 外とした follow-up、closing のブロッカーではない）: 実カメラ/UAV
Sensor 結線、実モザイキング/検出アルゴリズム（現状は決定的 mock）、実 Datomic Local /
kotoba-server pod への `DatomicStore` 接続確認。個体識別（顔/ナンバープレート認識）は
意図的に未実装のまま — 追加するなら本 ADR とは別に governor の型を変える重い変更として
レビューする。

## Consequences

- (+) session-scope（purpose/AOI/retention/legal-basis）を governor の HARD
  条件にすることで、「境界の無いダッシュボードで都市全体を無差別に追跡する」
  という WAMI の最も懸念される用法が、上書き不可能な形で構造的に排除される。
  施設警備と防災解析という 2 つの限定用途以外の用途（例: 一般監視・行動追跡
  ビジネス）を追加するには、別 ADR + governor 拡張 + オーナー承認が必要。
- (+) anonymization / no-cross-session-reference により、顔認識・複数拠点
  横断の persistent tracking graph は **今回の phase では実装すらしない**
  （将来これを追加するなら、それ自体が governor の型を変える重い変更として
  別途レビューされる）。
- (+) alert 配信・report 公開は always high-stakes で、外部への影響
  （警備オペレータへの通報・被災対応機関への公開）は必ず人間の承認を経る。
- (−) 実際のモザイキング/移動物体検出/追跡アルゴリズムは未実装（決定的 mock
  のみ）。Sensor port は合成フレームを返すだけで、実カメラ/UAV 結線は
  follow-up。
- (−) DatomicStore は `langchain.db` の in-process EAVT backend で検証、
  実 Datomic Local / kotoba-server pod への接続は follow-up（既存 3 actor と
  同じ制約）。

## References

- `orgs/gftdcojp/gftd-talent-actor`（Store/Advisor/Governor/Phase/StateGraph
  の一次テンプレート）
- `orgs/gftdcojp/ai-gftd-newscaster`（ingest/produce 二流路 StateGraph、
  ports.cljc の Sensor/Notifier/Publisher 分離、ADR-2607020910）
- `orgs/gftdcojp/cloud-itonami`（ops-LLM ⊣ CertGovernor、robotaxi-actor 系譜の
  現行実体）
- ADR-2606272330（新規 project 一気通貫登録の実例・恒久承認）
- CLAUDE.md「gftdcojp/com-junkawasaki = private」visibility 既定
- 本 ADR とペアの .edn
