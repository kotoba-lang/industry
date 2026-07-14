# ADR-2607141550: persona ベースの visual / user test と feedback react loop

**Status**: accepted, implemented (closing 2026-07-14 — persona layer 稼働、telemetry 較正は follow-up)
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki

## Context

itad.gftd.ai（ADR-2607141446）のような CV 特化 LP は「デザインシステム適合」
（design-quality の決定論 audit、現在 100.00）だけでは品質を測りきれない —
**「ターゲット顧客がこのページを見て信頼し、フォームを送りたくなるか」**は
別の計測が要る。実ユーザーテストは遅くて高いので、まず persona-conditioned な
合成ユーザーテスト（LLM が persona を演じてスクリーンショットを採点・自由記述
feedback）を高速な内側ループにし、実テレメトリ（lead / CVR / チャネル別 CPA）を
遅い外側ループとして合流させる react loop を設計する。

既存資産（ゼロから作らない — CLAUDE.md の再発防止則）:
- `kotoba-lang/design-quality`: 決定論 audit + 3-judge LLM panel + append-only
  ledger（`:eval/*` イベント、DataScript/Datomic transactable）+ Co-Scientist
  kaizen loop（`coscientist.cljc`、Generate→Reflect→Rank→Evolve→Meta。
  原典 isekai ADR-0007 / ADR-2606141500 keiei-arbor）。
- `cloud-itonami`: business ops actor（propose→govern）+ 稼働中の
  `itonami-react-growth-hourly` routine + BMC/lean loop 計測。
- `ai-gftd-itad`: ITAD_LEDGER（実 lead イベント）+ UTM 永続化（チャネル別 CPA が
  台帳から集計可能）。

## Decision — 配置は「kotoba-lang か cloud-itonami か」ではなく 3 層分担

**mechanism（汎用・再利用可能な仕組み）= `kotoba-lang/design-quality`**。
persona スキーマ・persona-conditioned visual test の rubric・ledger イベント
ビルダを `design_quality/persona.cljc`（純 cljc）として追加する。design-quality は
既に「UI 品質を queryable EDN に落とす」ライブラリで ledger 形式と coscientist
loop を持つ — persona visual test はその **第4層**（`:lint` / `:llm-judge` /
`:sample-visual` / **`:persona-visual`**）に過ぎない。新リポは作らない。

**catalog（プロダクト固有の persona と対象ページ）= 各 product repo**。
itad の 5 persona と対象ページ（URL・viewport）は `ai-gftd-itad/resources/
personas.edn`。スコアと feedback は `ai-gftd-itad/docs/persona-visual-ledger.edn`
（append-only、design-quality ledger と同一イベント形式 + `:eval/persona`
`:eval/page` 拡張キー）に積む。**LP のコピー（itad.edn）と同じ repo に住む**ので、
Evolve（修正適用）が同一 PR で閉じる。

**operation（定期運転・実テレメトリ合流・改善の gate）= `cloud-itonami`**。
synthetic panel の定期実行、ITAD_LEDGER / UTM からの実測（CVR・CPA）集計、
仮説の propose→govern、BMC/lean loop への接続は itonami の routine として運転する
（`itonami-react-growth-hourly` と同型。actor の Governor が「LP に勝手に嘘の
実績を足す」類の Evolve を拒否する安全弁になる）。

## react loop の設計

```
[内側: synthetic persona loop — 速い(分単位)、デプロイ前後で回せる]
1. Capture   headless Chrome で live LP を撮影(mobile 390 / desktop 1280、light/dark)
2. Panel     persona ごとに独立 judge(将来 3-judge、初回は 1)が採点 + 自由記述
             軸(1-5): first-view-comprehension / trust / cta-findability /
                      anxiety-resolution / readability / form-intent
3. Ledger    :eval/layer :persona-visual として append(手編集禁止・追記のみ)
4. Reflect   feedback → 根拠のある仮説へ(ページ実体・itad.edn と突合、重複排除)
5. Rank      impact × effort(coscientist の Elo/heuristic ranking を流用)
6. Evolve    top-k を itad.edn / lp.cljc に適用 → 再生成 → 決定論 audit gate
             (>= 95 で deploy、下回れば差し戻し) → 再 Panel → 収束判定(dry 2 rounds)

[外側: real-user loop — 遅い(週単位)、synthetic の答え合わせ]
7. Telemetry ITAD_LEDGER の lead イベント + UTM → CVR・チャネル別 CPA を集計、
             :eval/layer :telemetry として同じ ledger に append
8. Calibrate persona の form-intent 予測 vs 実 CVR を突合 — 乖離が大きい persona は
             プロンプト/属性を修正(persona 自体も Evolve の対象)
```

- **判定の再現性**: 最終 gate は常に決定論 audit（coscientist の原則 —
  「ranking is reproducible, never an LLM debate」）。persona panel は
  発見器であって gate ではない。
- **捏造ゼロの継承**: persona feedback が「実績数値を載せろ」「顧客の声を作れ」を
  提案しても、Evolve は保有しない事実を追加しない（Governor / 適用規約で拒否）。
  judge の stdev を記録する（単一 judge は合意の弱さを隠す — ADR-2607132300 の実測）。

## itad の初期 persona（5 体、`resources/personas.edn`）

1. **soumu-tanaka** — 中小企業(60名)の総務。46歳、IT は苦手。PC 20台の入替残骸。
   恐れ: 情報漏洩で自分の責任になること・面倒な手続き。デバイス: PC(Chrome)。
2. **jousys-sato** — 中堅企業(300名)の情シス。34歳、技術リテラシ高。監査対応で
   証跡が要る。恐れ: 「証明書」が実質ただの紙であること。デバイス: PC。
3. **keiei-suzuki** — 8名の会社の代表。52歳。PC 3台だけ処分したい。恐れ: 少台数で
   断られる・ぼったくり。デバイス: スマホ(移動中に検索)。
4. **isms-jimukyoku** — ISMS/P マーク事務局担当。41歳。外部監査の証跡粒度が全て。
   恐れ: シリアル不記載・検証不能な証明書。デバイス: PC。
5. **mobile-searcher** — 39歳の経理兼総務。昼休みにスマホで「パソコン 廃棄 証明書」
   を検索して比較中。恐れ: フォームが長い・料金が不明瞭。デバイス: スマホ。

## First iteration（本 ADR と同日実行）

live の itad.gftd.ai スクリーンショット(mobile/desktop)に対し 5 persona × 1 judge の
panel を実行し、スコアと feedback を `ai-gftd-itad/docs/persona-visual-ledger.edn` に
追記した（結果は ledger と addendum を参照）。3-judge 化・cloud-itonami routine 化・
telemetry 合流は follow-up。

## Addendum 1 — first iteration の結果と計測器の教訓（2026-07-14 同日）

run `persona-visual-20260714-0700`（5 persona × 1 judge、35 events →
`ai-gftd-itad/docs/persona-visual-ledger.edn`）。集計: cta-findability **5.00** /
first-view-comprehension 4.40 / anxiety-resolution 4.00 / form-intent 3.60 /
readability 3.00 / **trust 2.80（最弱軸）**。trust の主因は「会社情報が薄い +
許可『準備中』表記」で 4/5 persona が指摘 — これは LP 小手先でなくオーナー действие
（許認可・会社情報開示）が本丸、という発見自体が価値。

**Evolve 適用（正直に直せるものだけ）**: 料金モデルケース総額（3/5 persona 指摘）、
FAQ 監査・マニフェスト 2 問のデフォルト展開、NIST の日本語 gloss、footer に
許可業者連携の説明。audit 100.00 維持で deploy 済（version `451d717d`）。
**適用しなかった feedback**: 実績・認証ブロック（保有していない — 捏造禁止）、
輸送中管理の詳細・証明書 PDF・検証ページ実物（運用事実の確定 / 実装が先）。

**計測器の教訓（重要）**: `chrome --headless --screenshot --window-size=390,N` の
素撮りは mobile emulation なしのレイアウトで**実機に存在しない右端見切れ**を描画し、
persona 2 体の readability を 2 に落とす偽陽性 feedback を生んだ。CDP
（`Emulation.setDeviceMetricsOverride` mobile:true, DPR 2）で計測したところ
overflow 要素ゼロ・完全折返しを確認。**Capture 工程は persona panel の計測器の
一部であり、撮影は CDP 経路（`ai-gftd-itad/tools/capture_lp.cljs`）に固定する。**
synthetic user test の信頼性は「LLM の演技」より先に「入力画像の忠実さ」で壊れる。

## Consequences

- (+) 新リポ・新形式ゼロ: ledger 形式(:eval/*)・coscientist loop・actor 運転すべて既存に載る。
- (+) LP を持つ他プロダクト(net-babiniku・isekai 等)にも persona カタログを足すだけで水平展開できる。
- (−) synthetic persona は本物のユーザーではない — form-intent と実 CVR の突合
  (外側ループ)が回るまでスコアの絶対値を信用しすぎない(:telemetry 層で較正する)。
- (−) cloud-itonami routine 化はまだ(下記 follow-up)。それまでは手動トリガの内側ループのみ。

## Follow-ups

1. ~~persona panel の 3-judge 化(stdev 記録)と light/dark 両テーマ採点。~~
   → **機構（mechanism/runner）は Addendum 2（2026-07-15）で解消**。実際に
   実機LP に対し3-judge×light/dark の live panel を運転する（実 LLM judge
   呼び出し + 実スクリーンショット）のは、この session の環境にブラウザ
   自動化が繋がっていないため引き続き未実施 — 下記 Addendum 2 参照。
2. cloud-itonami routine 化(`itad-persona-react-daily` 等。Governor gate 付き)と
   ITAD_LEDGER/UTM telemetry の :telemetry 層 append。
3. persona 較正(synthetic form-intent vs 実 CVR)、persona 自体の Evolve。
4. design-quality README への :persona-visual 層の記載と他プロダクト展開。

## Addendum 2（2026-07-15）— 3-judge / light-dark テーマの機構実装（運転は未実施）

Follow-up 1 のうち、**機構側**（`kotoba-lang/design-quality` の
`persona.cljc` + `ai-gftd-itad` の runner）を実装した。**実際にitad.gftd.ai
に対して3-judge×light/dark のライブpanelを運転する（本物のLLM judge呼び出し
+ 本物のスクリーンショット取得）ことは今回実施していない** — この点を
正直に記録する: 本ADRの3層設計（mechanism/catalog/operation）のうち今回
触れたのは最初の2層（mechanism・catalog側runner）のみで、実運転
（operation層、cloud-itonami routine化とセット）は follow-up 2 と合わせて
別途になる。

**mechanism（`kotoba-lang/design-quality`、main `fb766e2f`）**:
`persona/score-events`・`persona/feedback-event` に任意の `:theme`
（light/dark等）を追加——与えられれば全イベントに `:eval/theme` が付き、
省略時は既存の（historicalな）イベント形状と完全に同一のまま（キー自体が
存在しない）。`mean-by-axis` はそもそも `:eval/judge` の値の種類数に
依存しない集計だと判明した——2judge・3judgeで挙動を変える必要は無く、
既存実装が既に3-judge以上に対応済みだった。3人の判定が割れるケースで
stdevが正しく非ゼロになることを確認するテストを追加。18 tests /
190 assertions（従来16/181）、clj-kondo 0。

**catalog側runner（`ai-gftd-itad`、main `8a7db51c`）**:
`tools/append_persona_run.cljs` が (1) 1 personaにつき複数judgeの結果
ファイル（`result-<id>.edn`=judge1、`result-<id>.j2.edn`/`.j3.edn`=
追加judge、既存の1judge運用と完全後方互換）を受け付けるように、
(2) 任意の第5引数 `<theme>` を受け付け全イベントに `:eval/theme` として
付くように、それぞれ拡張した。**着地前に実際のbugを1件発見・修正**:
runnerは既に `persona/mean-by-axis` でstdevを計算していたが、summary出力
では `:mean`/`:n` だけ表示し `:stdev` を握りつぶしていた——「3-judge化で
判定の割れを可視化する」という本ADR自身の目的にとって、計算していても
表示しなければ意味がない欠陥。表示するよう修正。

**検証**（本物の`docs/persona-visual-ledger.edn`には一切触れず、隔離
sandboxで実施）: 実際にnbbスクリプトを実行し、(a) 1personaに3個の
合成judge結果ファイル＋別1personaに1個の単一judge結果ファイルを与えて
実行→ n=4での正しい集計・非ゼロstdevの表示を確認、(b) `isms-jimukyoku`
という「jimu」を含むが本物のjudgeサフィックスではないpersona idが
誤って判定されないことを確認、(c) 冪等性（同一run-idの二重追記拒否）と
unknown persona id拒否が引き続き動作することを確認、(d) themeを省略
した従来通りの4引数呼び出しが`:eval/theme`キーを一切追加しないことを
確認——ただしこの過程でsummary行に余計な`nil`が表示される小さなバグを
自分で発見し、着地前に修正した。

**west pin**: `design-quality` `4d316cfa` → `fb766e2f`、`ai-gftd-itad`
`e23c16e2` → `8a7db51c`（いずれも `gh api compare` で `ahead_by=2,
behind_by=0` を確認済み）。
