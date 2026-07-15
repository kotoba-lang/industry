# ADR-2607091500: kotoba-lang 全域への clj-kondo :lint ゲート導入

**Status**: accepted (rollout ほぼ完了、この ADR は事後記録)
**Date**: 2026-07-09
**Deciders**: Jun Kawasaki

## Context

`成熟度を向上` ループの一環として、kotoba-lang org 配下で `deps.edn` に
`:lint` エイリアス(clj-kondo)を持たないリポジトリを継続的に洗い出し、
1リポジトリずつ実際に `clojure -M:lint` を通して直す作業を数セッションに
わたって行った。対象は最終的に kotoba-lang の test/CI 保有リポジトリほぼ全体
(kami-* ファミリー、kotobase-* ファミリー、eda/kotodama-host/kotobase-server/
plm、kqe/ocio 等の単独リポジトリ、com-arm-cpu 他 21 件の clean-room
ハードウェア actor 群、murakumo)に及んだ。

この過程で、単なる「lint エイリアスの追加」では終わらない、**繰り返し現れる
バグクラス**が判明した。場当たり対応ではなく毎回同じ判定基準で直せるよう、
ここに固定する。

## Decision

**kotoba-lang の deps.edn を持つ全リポジトリに `:lint` エイリアスを標準搭載
し、CI の必須ジョブにする。** 形は統一:

```clojure
;; clj-kondo as a Clojars lib (no Homebrew/system install needed).
:lint {:replace-deps {clj-kondo/clj-kondo {:mvn/version "2024.11.14"}}
       :main-opts ["-m" "clj-kondo.main" "--lint" "src" "test"
                   "--fail-level" "error"]}
```

`--fail-level error` を明示する(clj-kondo は既定で warning でも非0 終了しない
設定と紛らわしいため、警告は許容し実エラーだけを CI ゲートにする意図を毎回
明文化する)。

### 発見された繰り返しバグクラスと対処

1. **JVM専用シンボルの `.cljc` 内素通し**(`format` / `slurp` / `java.*`
   interop / `bigdec` / `java.util.Date` など、cljs に対応物が無い)。
   - ファイル全体が単一プラットフォーム前提で既存の `#?` が皆無 →
     `.cljc` → `.clj`(または `.cljs`)へ**リネーム**が最小修正。
   - ファイルの他の部分は真に移植可能(他所に `#?(:cljs ...)` が実在)で、
     該当関数だけが単一プラットフォームな場合 → その関数だけ
     `#?(:clj ...)` で囲う。
   - 呼び出し側が単純な1値埋め込みの `format` の場合、ポータブル化の最も
     安い経路は `format` を丸ごと `str` 連結に置換すること(murakumo の
     shell コマンド文字列生成で採用。出力は不変、cljs 側でも動く)。
   - 複数呼び出し箇所で使う場合は `->bigdec` 方式のポータブル helper
     (`(defn ->bigdec [x] #?(:clj (bigdec x) :cljs x))`)を1箇所定義して
     使い回す(kotoba-lang/plm で採用。同一 namespace 内の複数ファイルから
     呼ぶ場合は helper を1ファイルに集約し他ファイルは修飾呼び出しする)。
2. **cljs 専用ファイルの誤 `.cljc` 命名(逆パターン)**。npm import
   (`@noble/curves` 等)・`js/*`・`cljs.test` を使うのに既存の `#?` が
   ゼロ → `.cljc` → `.cljs` へリネーム。kami-genko で発見後、
   kotobase-client / kotobase-cljc-worker / kotobase-browser-worker の
   同型ファイル群でも同じ誤りを確認・修正(このファミリーは元々同じ
   スキャフォールドから複製されたため、同じ誤りが複数リポジトリに
   伝播していた)。
3. **`clj-kondo/.cache/` の gitignore 漏れ**。新規に `.clj-kondo/config.edn`
   を追加するときは必ず `.gitignore` に `.clj-kondo/.cache/` があるか確認
   する(無いと cache バイナリを誤ってコミットする実例が複数回発生)。
4. **カスタム def 風/let 風マクロを clj-kondo が解析できない**。
   `.clj-kondo/config.edn` の `:lint-as` で解決。形が既存の built-in と
   厳密一致するなら `clojure.core/defn` 等を、一致しないなら
   `clj-kondo.lint-as/def-catch-all` を使う。
5. **`boolean-array` 等 cljs.core スタブの既知ギャップ**は
   `#_:clj-kondo/ignore` で個別に握りつぶす(false positive と確定した
   もののみ)。
6. **意図的な誤アリティ呼び出しをテストが検証しているケース**
   (kqe の `visible-is-required`: `ArityException` が飛ぶことを確認する
   ためにわざと少ない引数で呼ぶ)。clj-kondo の静的アリティチェックは
   これを実行時契約と区別できないため `#_:clj-kondo/ignore` で該当行だけ
   抑制する。
7. **`#?(:cljs (:refer-clojure :exclude [...]))` だけがあって置換定義が
   存在しない**(kami-vehicle/vec3.cljc)。cljs ビルドが実際には壊れて
   いた真性バグ。exclude 節を削除して修正(単なる lint 対応ではなく実際の
   コンパイル breakage を lint が検出した例)。
8. **フォワードリファレンス**(kotobase-cljc-worker の `mint.cljs`:
   `-main` が自分より後に定義される `-mint` を呼ぶ)。実行時には
   `-main` が呼ばれる時点で全 defn が読み込み済みなので害は無いが、
   clj-kondo の静的解析は forward reference を解決できず
   unresolved-symbol として拾う。定義順を入れ替えて解消(挙動不変)。
9. **stale な `:local/root` sibling 参照**(org 全体の `*-clj` → 新名への
   リネーム波及後、deps.edn の座標名とパスが古いまま)。coordinate 名と
   `:local/root` パスの両方を現行の canonical repo/path に更新する。

### アーカイブ・壊れた依存の扱い

- **アーカイブ済みリポジトリ**(例: kqe)は push 不可。ローカルで修正しても
  最終的に revert し、west pin は現状維持する。
- **依存自体が未実装のスタブ**(例: ocio の `kotoba-lang/yaml` は README
  のみで実体無し、CI は1週間前から red)は、:lint 修正の範囲外。深追いせず
  現状維持で記録するに留める(dance/scene2d の欠落 namespace と同カテゴリ)。

### 着地経路

各リポジトリは west 管理の shared checkout で直接 `main` へ push できる場合は
そのまま、並行セッションの WIP と衝突する場合は `git stash` 退避 →
fast-forward 同期 → 再適用、あるいは feature branch を push して
`gh api repos/<org>/<repo>/merges` でサーバサイドマージする(CLAUDE.md
`:manifest-workflow` 準拠)。着地後は必ず実 CI が green になったことを
確認してから `nbb scripts/gen-west-manifest.cljs --entry <name>` で pin を
前進し、superproject 側を `chore(manifest): ...` でコミットする。

## Consequences

- kotoba-lang の実質ほぼ全リポジトリに、静的解析による最低限の正しさ保証
  (未解決シンボル・アリティ不整合等)が入った。今後の新規リポジトリも
  同じ `:lint` エイリアス雛形をコピーすれば同じゲートに乗る。
- 副産物として、lint が無ければ気付かれなかった実バグを複数発見・修正した
  (kami-vehicle の cljs ビルド breakage、kotobase-cljc-worker の forward
  reference)。lint ゲート導入は「体裁を整える」だけでなく実際に品質を
  上げた。
- 上記9パターンは今後同種の作業をするときの判定チェックリストとして使える
  (ゼロから都度考え直さなくてよい)。
- 未解決のまま残るもの: ocio(yaml 依存が未実装)、kqe(アーカイブ済み)、
  dance/scene2d(欠落 namespace、webgpu 側が WIP のため保留)、
  kami-autodrive(GNC 収束バグ、本 ADR のスコープ外)、shell(browser 側の
  既存テスト失敗)。これらは個別 ADR ないし follow-up で扱う。
