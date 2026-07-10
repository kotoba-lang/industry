# ADR-2607101525: kotoba-lang/usd — USDA読み込み(parse-usda)+ usdcat/usdchecker実オラクル検証

- **Status**: proposed
- **Related**: ADR-2607110900（ロボット接触力学 — 同じ「物理的リアリズムの成熟度を上げる」取り組みの一環、着手順として1番目。本ADRは2番目=OpenUSD）

## Context

オーナーから「OpenUSD・URDFロボティクス・cloud-itonami製造simの完成度」を問われた調査（先行研究）で判明した内容を、着手前に自分で一次ソースを読んで独立に再検証した（ADR-2607110900でエージェント報告のハルシネーションが発覚した直後のため、今回は最初から自分で全文読む）:

- `orgs/kotoba-lang/usd/src/usd/core.cljc`（全95行）: EDNをUSDA（ASCII テキスト）にコンパイルする**書き込み専用**のコンパイラ。`usda`/`prim`/`attr`/`rel`/variant-set。**パース関数は一切存在しない**（`parse`/`read`という名前の関数はゼロ）。
- `test/usd/core_test.clj`（全63行、4 deftest・19 assertions）: すべて**文字列比較**（`str/starts-with?`/`str/includes?`/`=`）。round-tripテスト（出力をパースし直して元のEDNと比較）は無い。
- `test/usd/core_test.clj`のnamespace docstringは「usdcat/usdcheckerが同じ出力を実際に検証する（`bb gate`で）」と主張しているが、**この主張は虚偽**——リポジトリ内に`bb gate`タスクの定義は存在せず（`bb.edn`自体が無い）、CI（`.github/workflows/ci.yml`）は`clojure -M:test`のみで、usdcat/usdcheckerの呼び出しは影も形もない。ADR-2607110900の教訓（ドキュメントの主張を鵜呑みにしない）を踏まえ、これも実装着手前に自分で検証して判明した。
- **一方、実在するPixar公式USDツールチェーンを実際にローカルで動かして確認した**:
  - macOSにはシステム同梱の`/usr/bin/usdcat`/`/usr/bin/usdchecker`（root所有、Mach-O universal binary、Apple製 — AR Quick Look/USDZ関連でmacOSに標準搭載）が実在し、動作確認済み（手書き`.usda`をparseして再emit、`.usda`↔`.usdc`バイナリのround-tripも成功、`usdchecker`は実際にmetersPerUnit/upAxis欠落を検出した）。ただしこれは**macOSローカル限定**でLinux CIランナーには無い。
  - PyPI `usd-core`（NVIDIA/Pixar公式配布のPython bindings、`pxr`モジュール）を実際に`pip install`して動作確認済み: install ~1.2秒・224MB、`Usd.Stage.Open(...).ExportToString()`（usdcat相当）と`UsdUtils.ComplianceChecker`（usdchecker相当、deprecation警告はあるが動作する）の両方が実際に機能することを確認した。これはLinux/macOS双方で配布されておりGitHub Actions（ubuntu-latest）でも`pip install usd-core`で入手可能——**CI上で実オラクル検証を組み込める**、ということも意味する。

## Decision

### D1. スコープはUSDA（ASCIIテキスト）読み込みのみ — `.usdc`(バイナリcrate形式)/`.usdz`(zipパッケージ)のパースはやらない

`.usdc`はPixar独自のバイナリフォーマット（TOC・セクション・圧縮配列を含む非自明な構造）で、ゼロから正しく実装するのは本ADRの範囲を大きく超える大工事になる。ADR-2607110900のM2で学んだ「最小スコープ」原則を踏襲し、既存の書き込みパスと対称的な**USDAテキストのみ**に絞る。バイナリが必要な場面は、実オラクル（`usdcat`/`usd-core`）に変換を委譲すればよい（D3参照）——自前でバイナリパーサを書く必要はない。

### D2. パーサーの出力は既存の書き込みパスと同じEDNデータモデルにする

`usd.core/usda`が受け取る`[spec type? name meta? & body]`プリム木・`[:attr typ nm value]`・`[:rel nm value]`・`[:variant-set name variants]`という既存の表現へ**双方向**にする。これにより「EDN → usda文字列 → parse → EDN」というround-tripが構造的に定義でき、19件の既存golden testの出力を全てparse-and-compareでも検証できるようになる（新規テストを別体系で作るのでなく、既存資産を強化する）。

### D3. 自作パーサーの正しさは、自作テストだけでなく実際のPixar USDツールチェーンをオラクルとして使って検証する

`test/usd/core_test.clj`の「usdcat/usdcheckerで検証」という記述を、初めて**実際に本物にする**。JVM専用の新規テストnamespace（`.clj`、既存テストファイルと同じくJVM限定）から、ローカルには`usdcat`/`usdchecker`（macOS標準搭載時）またはCIには`usd-core`（pip、D3で確認済み）経由で外部プロセスとして実行する薄いPythonドライバスクリプトを呼び、以下を検証する:
  - 自作emitterが出力したUSDAが`usdchecker`相当（`UsdUtils.ComplianceChecker`）でエラー0件であること。
  - 自作emitterの出力を`usdcat`相当（`Usd.Stage.Open` → `ExportToString`）に通しても意味的に同じ内容が得られること（Pixar自身のパーサーとの整合性）。
  - 自作parserでparseした結果を再emitしたものと、`usdcat`が同じ入力から出力するものが（フォーマット差はあれど）意味的に同一であること。
外部ツールが見つからない環境（オラクル未インストールのローカル環境等）ではこれらのテストは明示的にskipし、CI（D4でセットアップ）では必ず実行される状態にする——「無ければ静かに0件通過」にはしない。

### D4. CI（GitHub Actions）に`pip install usd-core`ステップを追加し、オラクル検証を実際のゲートにする

現状の`.github/workflows/ci.yml`は`clojure -M:test`のみ。ここに`actions/setup-python`+`pip install usd-core`を追加し、D3のオラクル検証テストがCI上で（skipでなく）実際に走るようにする。これによりnamespace docstringの主張が初めて事実になる。

### D5. パーサーは既存書き込みパスが実際に出力する構文のサブセットに限定する（USDA仕様全体はやらない）

USDA仕様は非常に広い（layer offset、sublayerサブ構文、時系列サンプル`.timeSamples`、コネクション`connect`、カスタムスキーマ拡張等）。本ADRでは`usd.core/usda`が現に出力できる構文——`#usda 1.0`ヘッダ・layer metadata・`def`/`over`/`class`プリム（型/名前/メタデータ/composition-arc `prepend`）・属性（scalar/tuple/array-of-tuples/scalar-array/asset-ref/path-ref/string/token/number）・`rel`・`variantSet`——のパースに限定する。それ以外のUSDA構文に遭遇したパーサーは、黙って無視/誤読するのでなく明示的にエラーを投げる（D2のround-tripを壊さないため）。

## Milestones

- **M1**: `usd.core`に`parse-usda`（トップレベル）と`parse-prim`（再帰下降パーサーの本体）を追加。字句解析→構文木という2段構成にはせず、既存の`usd.core`のスタイル（純粋関数、外部依存なし）に合わせて手書き再帰下降で直接EDNへ。既存19件のgolden test出力全てをparse→re-emitしてbyte-exact一致することをテストで確認（D2のround-trip）。
- **M2**: JVM専用の新規テストnamespace `usd.oracle-test`（`.clj`）を追加。Pythonドライバスクリプト（1ファイル、`subprocess`経由でJVMから呼ぶ）で D3 の3種類の検証（compliance/usdcat round-trip/parse-then-reemit-matches-usdcat）を実装。オラクル未検出環境はskip（理由をテスト出力に明記）。
- **M3**: `.github/workflows/ci.yml`に`actions/setup-python@v5`+`pip install usd-core`ステップを追加し、M2のオラクルテストがCI上で実行されることを確認（グリーンになるまで）。`test/usd/core_test.clj`のnamespace docstringの「bb gate」という虚偽記述を、実態（このCIジョブ自身がusdcat/usdcheckerで検証する）に合わせて訂正する。

## Non-goals（明示的にやらないこと）

- `.usdc`（バイナリcrate形式）・`.usdz`（zipパッケージ）の自前パース実装——D1参照。必要になったら実オラクル（usdcat/usd-core）経由の変換に委譲する。
- USDA仕様全体のカバー（`.timeSamples`・`connect`・sublayer・カスタムスキーマ等）——D5参照、既存書き込みパスがカバーする構文のみ。
- 新しいUSD機能（新しい属性型・新しいcomposition arc等）を書き込みパス側に追加すること——本ADRは読み込み側のみ、書き込みパスは変更しない。
- IsaacSim/Omniverseとのライブ差分比較——この組織の一貫した検証手法（定数比較・実ツールとの直接突き合わせ）の範囲内で、usdcat/usdcheckerという実ツールとの突き合わせに留める。

## Consequences

- `usd.core`に閉じた変更のため、`kami-app-amenominaka`等の既存consumerには影響しない（書き込みAPIは変更しない、読み込みAPIを追加するのみ）。
- M2/M3はCIにPython実行環境という新しい依存を追加する——JVM/Clojureのみだったこのリポジトリのビルドに`actions/setup-python`が加わる（ローカル開発でも同様、Pythonが無い環境ではオラクルテストはskipされるが、CIでは必須になる）。
- D3のオラクル検証により、`usd.core`の出力がPixar公式ツールチェーンから見て実際に正しいUSDAであることが初めて機械的に保証される（現状は自己参照的な文字列比較のみ）。

## Open Questions / Follow-up

- M1のパーサーがD5のスコープ外構文（`.timeSamples`等）に遭遇した際のエラーメッセージの質は、実装しながら調整する。
- `.usdc`バイナリサポートが将来必要になった場合は、自前実装でなく実オラクル（usd-core）経由の変換に倒すのが引き続き妥当か、その時点で再評価する。

## Related

- ADR-2607110900（ロボット接触力学 — 同じ物理的リアリズム向上の取り組みの1番目、本ADRは2番目）
