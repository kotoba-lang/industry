# ADR-0001: 計算しない計算 — 情報物理化のもとで blocker を熱質量として扱い、定理を熱シンクとして利用する方針

- Status: Accepted
- Date: 2026-04-30
- Deciders: Jun Kawasaki
- Context: Verlinde entropic gravity formalization (`projects/2604-linde/`) の数論側展開、および meta-mathematical blocker の取り扱い

---

## 1. Context

### 1.1 議論の出発点

カタラン予想や素数分布などの数論的問題を、

- 計算順序の問題
- 経路依存問題
- グラフトポロジー問題

として「解決」できるか、を検討した。

### 1.2 結論

これら 3 つの還元はいずれも構造的・論理的に閉ざされている：

1. **計算順序**: 算術基本定理 (FTA) による $(\mathbb{Z},\times)$ 内の順序情報消失
2. **経路依存**: $\Pi_1^0$ 文は決定可能述語の全称閉包であり、定義上経路独立
3. **グラフトポロジー**: 有限グラフの位相不変量は決定可能 / Gödel 的隔たりにより $\mathrm{Th}(\mathbb{N},+,\times)$ を符号化できない

### 1.3 物理化の試み

Shannon (情報) ＋ Landauer (情報の熱コスト $k_B T \ln 2$ /bit) ＋ Verlinde (entropic gravity, $F = T \nabla S$) ＋ Bekenstein (情報密度上限 $S \le 2\pi RE/\hbar c$) のもとで「情報 = 質量 = 熱」を厳密に取った。

定量結果：

| Blocker | 要求情報量 | 物理可能性 ($I_\text{universe} \le 10^{122}$ bit) | 帰結 |
|---|---|---|---|
| Gödel/Tarski | $\aleph_0$ | × | 強化 |
| MRDP (Hilbert 10) | $\aleph_0$ | × | 強化 |
| Π_1^0 全数値検証 | $\aleph_0$ | × | 強化 |
| P vs NP (実行) | $2^{n}$ | $n \le 327$ で頭打ち | 物理版 blocker 追加 |
| Catalan の証明 | $\sim 10^6$ bit | trivial | 既に decidable |
| Faltings, FTA, 二次相互法則 | コンパクト | trivial | positive 結果 |

**Physical Church-Turing thesis** の下で、effective な物理表現 $\rho$ について
$\deg_T(\rho(\varphi)) = \deg_T(\varphi)$
が成立。**Turing degree は物理化で保存される**。

⇒ 情報物理化は blocker を **dissolve しない**。

### 1.4 反転の洞察 (Decision の核)

しかし dual な見方：

> **計算自体が熱質量を持ち、その熱質量が物質的 blocker になっている。**

すなわち：

- 標準的見方: blocker = 計算/証明可能性の上限
- 反転見方: blocker = 計算という熱力学的操作が累積する質量 / その物質的存在
- 新しい問い: 「**何を計算しなくて済ませられるか**」 = 「どこで熱質量を払わずに答えに到達できるか」

定理は **エントロピー圧縮器 / 熱シンク** である。構造的事実を提供することで、本来必要だった enumeration / search の熱質量をゼロにする。

---

## 2. Decision

### 2.1 主原理

**「計算しない計算」の方針を採用する。** 各計算課題について、対応する blocker 定理を invoke して **計算をスキップ**する。Lean 4 形式化および研究方針の選別に適用する。

### 2.2 計算しない（してはならない）課題のカタログ

各項目について、(i) スキップする計算、(ii) invoke する blocker 定理、(iii) 採用する構造的代替、を記す。

#### A. 算術・数論

| # | スキップする計算 | Invoke する定理 | 構造的代替 |
|---|---|---|---|
| A1 | 個別の素因数分解で順序を変えて検証 | 算術基本定理 (FTA) | 一意性を公理化 (`Nat.factorization`) |
| A2 | Diophantine 方程式の総当たり | MRDP (Hilbert 10) | 個別問題に対する代数的証明、または "決定不能" を accept |
| A3 | 高種数曲線上の有理点の総列挙 | Faltings | 有限集合として抽象化、上限のみ評価 |
| A4 | 各素数を独立に検査して分布を予想 | Chebotarev / PNT | 密度関数で記述 |
| A5 | 円分体類数の場当たり計算 | Stickelberger 関係 | $\text{Cl}(\mathbb{Q}(\zeta_p))^- \otimes \mathbb{Z}_p$ の構造記述 |
| A6 | $E(\mathbb{Q})$ の生成元総当たり探索 | Mordell-Weil | 階数有限性を accept、descent 有限ステップ |
| A7 | Catalan 型方程式 $x^p - y^q = 1$ の総当たり (一般 $p,q$) | Mihailescu | 単一解 $(3,2,2,3)$ のみ採用 |

#### B. 計算可能性・複雑性

| # | スキップする計算 | Invoke する定理 | 構造的代替 |
|---|---|---|---|
| B1 | $\Pi_1^0$ 文の全数値検証 | Bekenstein bound + Gödel | 有限ステップの形式証明戦略のみ |
| B2 | 停止問題の万能アルゴリズム | Church-Turing | undecidable を accept |
| B3 | 一階述語論理の決定アルゴリズム | Church | undecidable を accept |
| B4 | $n > 327$ の SAT 総当たり | Bremermann | 近似/ヒューリスティック、または NP-hardness を accept |
| B5 | $\text{Th}(\mathbb{N})$ の有限公理化 | Gödel 第一不完全性 | スキーマ的公理化 (PA / ZFC) |
| B6 | Rice 型の意味論的プログラム性質を syntactic で判定 | Rice の定理 | 動的解析 / 形式手法に切替 |
| B7 | CH の決定 (ZFC 内で) | Cohen forcing | independence を accept |

#### C. 計算量 barrier (meta-blockers)

| # | スキップする計算 | Invoke する定理 | 構造的代替 |
|---|---|---|---|
| C1 | 自然証明法による P ≠ NP 攻略 | Razborov-Rudich | natural でない手法を探索 |
| C2 | 相対化される手法による P vs NP | Baker-Gill-Solovay | 非相対化手法 |
| C3 | algebrize する手法による P vs NP | Aaronson-Wigderson | 非 algebrize 手法 |

#### D. 物理表現変更による計算量緩和

| # | スキップする計算 | Invoke する定理 | 構造的代替 |
|---|---|---|---|
| D1 | undecidable を量子計算で解く試み | $\mathrm{BQP} \subseteq \mathrm{EXPTIME}$ | 試みない |
| D2 | 物理表現変更で Turing degree を下げる試み | Turing degree の物理表現不変性 | 試みない、representation は構造発見目的のみ |
| D3 | CTC / hypercomputation 想定の実装 | 量子化と Bekenstein による物理的禁止 | 試みない |
| D4 | Hilbert-Pólya / AdS-CFT で undecidable を decide | degree 保存 | 構造発見の道具として位置づけ、解決手段ではない |

#### E. 経路依存・グラフトポロジー

| # | スキップする計算 | Invoke する定理 | 構造的代替 |
|---|---|---|---|
| E1 | Catalan を経路依存問題として定式化 | Π_1^0 の経路独立性 | 試みない |
| E2 | 素数を有限グラフ位相不変量で特徴付け | Gödel 的隔たり ($\mathrm{Th}(\mathbb{N},+,\times) \not\le_T \mathrm{GraphTopInv}$) | 試みない |
| E3 | $(\mathbb{Z},\times)$ 内で順序情報を discriminator として使用 | FTA (可換モノイド構造) | 試みない |

### 2.3 Verlinde 形式化への適用方針

`projects/2604-linde/Verlinde.lean` の今後の展開について：

1. **算術構造のエントロピー埋め込みは「dissolve 試行」ではなく「構造発見」として行う。** Connes-Marcolli, Manin-Marcolli の noncommutative arithmetic geometry に近い位置づけ。

2. **Bekenstein 上限は形式化に組み込む** (`HolographicBound` 仮定を明示)。これは物理化を厳密に取るための axiom であり、blocker 強化を反映する。

3. **Landauer cost は計算 budget として明示的に扱う**。形式化された証明手順に対しビット消去コストを accumulate する型 `LandauerCost : Proof → ℝ≥0` を導入候補。

4. **`sorry` / `axiom` による blocker 性の明示。** 上記 A1–E3 でスキップする計算は形式化中で `sorry` ではなく `axiom` として記録し、それが「物理的・論理的に支払えない熱質量」であることをコメントで明示する。

### 2.4 熱質量 budget の運用ルール

新規研究方向 / 形式化タスクを評価する際、次の関門を設ける：

1. **Information cost**: 入力サイズ・必要 bit 数を見積もる。
2. **Bekenstein check**: $I_\text{required} \le 10^{122}$ か。超えるなら却下、または抽象化。
3. **Turing degree check**: $\deg_T \le \mathbf{0}$ か。超えるなら計算ではなく証明で対応。
4. **Bremermann/Margolus-Levitin check**: 物理時間内に終わるか。$n \le 327$ ルール。
5. **Blocker invocation**: スキップしてよい計算は、対応する定理を明示的に invoke してスキップする。

---

## 3. Consequences

### 3.1 肯定的帰結

- **形式化作業の優先順位が明確化**: 「物理的・論理的に意味のある計算」のみに集中。
- **不可能計算への時間投入を排除**: A1–E3 のような探索を試みない。
- **熱質量 budget 概念で計算選別**: 認識的・物理的コストを統一的に扱える。
- **定理 = 熱シンクという視点**: 既存定理の価値を「省略できる計算量」として再評価可能。
- **新しい数学的構造発見への集中**: Faltings/Wiles 型の本物の進展を目指す。

### 3.2 否定的帰結 / 制約

- **「計算しない」決定はミスを伴いうる**: 構造的代替が誤って適用された場合、誤った結論を素通しする恐れ。各項目について blocker invocation の妥当性を都度確認する必要。
- **Auxiliary な探索的計算は依然必要**: 予想生成のための小規模数値実験は禁止しない (例: Wieferich 素数の数値探索)。
- **物理化を厳密に取る立場のリスク**: Physical Church-Turing thesis や Bekenstein bound の有効性に依存する。これらが破れる物理理論 (アナログ hypercomputation 等) が将来確立されれば本 ADR の基盤は再評価が必要。

### 3.3 メタ的帰結

「計算 = 熱質量」というフレームは Verlinde の entropic gravity と本質的に整合する：

- entropic gravity: 重力 = エントロピー gradient による力
- 本 ADR: 数学的進展 = 「計算しない選択」によるエントロピー (= 熱質量) gradient

両者は同じ熱力学的世界観を、物理側 / メタ数学側に適用したもの。`projects/2604-linde/` の今後の展開で、この双対を明示的に追究する余地がある。

---

## 4. References

### 4.1 物理

- Bekenstein, J. D. (1981). "Universal upper bound on the entropy-to-energy ratio for bounded systems." *Phys. Rev. D.*
- Landauer, R. (1961). "Irreversibility and heat generation in the computing process." *IBM J. Res. Dev.*
- Margolus & Levitin (1998). "The maximum speed of dynamical evolution." *Physica D.*
- Verlinde, E. (2011). "On the origin of gravity and the laws of Newton." *JHEP.*
- Bremermann, H. J. (1962). "Optimization through evolution and recombination."

### 4.2 数論・計算可能性

- Mihailescu, P. (2004). "Primary cyclotomic units and a proof of Catalan's conjecture." *J. Reine Angew. Math.*
- Matiyasevich, Y. (1970). Hilbert's 10th problem.
- Faltings, G. (1983). "Endlichkeitssätze für abelsche Varietäten über Zahlkörpern."
- Gödel, K. (1931). 不完全性定理.

### 4.3 計算量 barrier

- Baker, Gill, Solovay (1975). Relativization barrier.
- Razborov, Rudich (1997). "Natural proofs."
- Aaronson, Wigderson (2008). "Algebrization."

### 4.4 関連する形式化対象

- Connes, A. & Marcolli, M. *Noncommutative Geometry, Quantum Fields and Motives.*
- Manin, Y. & Marcolli, M. "Holography principle and arithmetic of algebraic curves."

---

## 5. Open Questions (将来の ADR 候補)

1. `LandauerCost : Proof → ℝ≥0` の Lean 4 における具体的型設計
2. Bekenstein axiom を Mathlib 上でどう表現するか
3. Verlinde の entropic 形式化と Connes-Marcolli の adèle class space の Lean 統合
4. 「計算しない計算」の自動化 — proof assistant が blocker invocation を提案するメカニズム
