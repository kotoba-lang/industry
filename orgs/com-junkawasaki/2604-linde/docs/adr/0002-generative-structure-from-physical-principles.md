# ADR-0002: 物理的生成原理から数学構造を導出する 5 層スタック

- Status: Proposed
- Date: 2026-04-30
- Deciders: Jun Kawasaki
- Predecessor: ADR-0001 (`docs/adr/0001-computation-as-thermal-mass-blocker.md`)
- Implementation: `projects/2604-linde/GenerativeStructure.lean`

---

## 1. Context

### 1.1 ADR-0001 の限界

ADR-0001 は **受動的** な blocker カタログである：

- 既存定理 (FTA, Faltings, Gödel, Bekenstein, …) を invoke して計算を skip する
- 「何を計算しないか」を判定するのみ
- **新しい数学的構造を生成する力は持たない**

### 1.2 ユーザー洞察

> 情報＝物理なので計算、定理を物理的に構造化できそうですが。

**情報 = 物理** を厳密に取るなら、定理は物理的対象であり、物理原理から
数学構造を **導出** する経路があるはず。Connes-Marcolli, Manin-Marcolli,
Frenkel-Witten, Lurie の研究はこの方向の先行研究。

### 1.3 必要な転換

| 観点 | ADR-0001 | ADR-0002 (本提案) |
|---|---|---|
| 役割 | 計算しない判断 | 構造を導出する生成原理 |
| 物理の用法 | 制約 (Bekenstein) | 生成則 (作用原理, Noether, ホログラフィー) |
| 定理の扱い | 既存定理を invoke | 物理から定理を導出 |
| 数学的内容 | 算術等式 | 普遍構成・スペクトル・圏論的双対 |
| 形式化 | `field_simp; ring` | `class`/`structure` + axiom 化 |

---

## 2. Decision

**5 層生成スタック**を採用する。各層は下層を消費し、上層の構造を生成する。

```
┌─────────────────────────────────────────────────────────┐
│ Layer 5: 圏論的普遍性 (Atiyah-Segal, Lurie)              │ ← Poincaré, 4-color
├─────────────────────────────────────────────────────────┤
│ Layer 4: スペクトル符号化 (Connes spectral triple)        │ ← RH, Connes-Marcolli
├─────────────────────────────────────────────────────────┤
│ Layer 3: ホログラフィック対応 (AdS/CFT, Manin-Marcolli)   │ ← Wiles, Langlands
├─────────────────────────────────────────────────────────┤
│ Layer 2: Noether 対称性 → 保存則                          │ ← Faltings, Mordell-Weil
├─────────────────────────────────────────────────────────┤
│ Layer 1: 作用原理 (proof action principle)                │ ← FTA, Mihailescu
├─────────────────────────────────────────────────────────┤
│ Layer 0: Blocker カタログ (ADR-0001)                      │ ← 既存
└─────────────────────────────────────────────────────────┘
```

### 2.1 Layer 1: 作用原理 (Proof Action Principle)

#### 物理側
$$
\delta S = 0 \quad \Longrightarrow \quad \text{Euler-Lagrange equations}
$$

最小作用原理。系は作用 $S = \int L\, dt$ を停留させる経路を選ぶ。

#### 数学側への翻訳
証明 $\pi$ の作用を Landauer コストの累積として定義：
$$
S[\pi] = \sum_{\text{step}} k_B T \ln 2 \cdot \text{(bit erased)}
$$
**最小作用の証明** = 最短証明 = **正準的 (canonical) 定理**。

#### 主張可能定理
- `trivial_proof_zero_action`: $0$ bit の証明は作用 $0$
- `shortest_proof_minimizes_action`: bit 数単調 ⇒ 作用単調

#### 具体例
- **FTA** = $(\mathbb{Z}, \times)$ の自由 commutative monoid における普遍構成 = 極小作用
- **Mihailescu (Catalan)** = $\{(x,y,p,q) : x^p - y^q = 1\}$ の唯一解 = ground state

### 2.2 Layer 2: Noether 対称性 → 保存則

#### 物理側
連続対称性 $G$ ⇒ 保存量 $Q$ (Noether 1918)：
$$
\frac{\partial L}{\partial \dot q^i} \delta q^i \quad \text{(conserved)}
$$

#### 数学側への翻訳
**構造的対称性 ⇒ 不変量**：

| 対称性 | 数学的不変量 |
|---|---|
| 翻訳 (additive) | コホモロジー類 |
| スケール | 階数 / 次元 |
| 双対 | 自己共役性 (Hilbert-Pólya) |
| Galois | $L$-function 関数等式 |

#### 主張可能定理
- `IsInvariant`: 群作用に対する不変関数
- `invariant_on_orbit`: 不変関数は軌道上一定
- `invariant_constant_on_orbit`: 同じ軌道なら同値

#### 具体例
- **Mordell-Weil** = 楕円曲線の Galois 作用に対する Noether 型有限性
- **Faltings** = $g \ge 2$ 曲線の自己同型有限性 + Bekenstein 上限 ⇒ 有理点有限

#### 主張すべき定理 (informal)
$$
\text{algebraic 対称性 } G \curvearrowright X \;+\; \text{Bekenstein bound on } X
\;\Longrightarrow\; |X^G| < \infty
$$

### 2.3 Layer 3: ホログラフィック対応 (AdS/CFT)

#### 物理側
$$
Z_\text{bulk}[\phi_0] = \langle e^{\int \phi_0 \mathcal{O}} \rangle_\text{CFT}
$$
$(d+1)$-次元 bulk = $d$-次元 boundary CFT.

#### 数学側への翻訳 (Manin-Marcolli)
- bulk = arithmetic 構造 (Galois 表現, Shimura variety)
- boundary = 解析的構造 ($L$-function, modular form)
- 対応 = Langlands functoriality

#### 具体例
- **Taniyama-Shimura** = 半安定楕円曲線 (bulk) ↔ modular form (boundary)
- **Wiles 1995** = この対応の特殊ケースの確立 ⇒ Fermat

#### 主張すべき定理 (informal)
$$
\text{quantum consistency of bulk} \;\Longrightarrow\; \text{automorphic property of boundary}
$$

これが厳密化されれば **Langlands 予想全体が物理整合性から従う**。
Frenkel, Witten が部分的に進めている方向。

#### 形式化方針
- Layer 3 は完全には Lean 化困難（research frontier）
- `structure HolographicPair` を skeleton として用意
- 具体的対応は axiom 化

### 2.4 Layer 4: スペクトル/作用素論的符号化

#### 物理側
Hamiltonian $H$ の固有値 = 物理的観測量。Hilbert 空間上の自己共役作用素。

#### 数学側への翻訳 (Connes spectral triple)
$$
(\mathcal{A}, \mathcal{H}, D)
\;:\;
\text{algebra} + \text{Hilbert space} + \text{Dirac operator}
$$

Connes-Bost: $\text{Spec}(D)$ から Riemann ζ の零点が抽出される構造。

#### 具体例
- **Hilbert-Pólya 予想** = ζ 非自明零点 ↔ 自己共役作用素のスペクトル
- **Connes-Marcolli** = adèle class space $\mathbb{A}_\mathbb{Q}/\mathbb{Q}^*$ → arithmetic $L$-functions

#### 形式化方針
- `structure SpectralTripleSkeleton` を用意
- 具体化は Mathlib の作用素論との接続が必要 (Phase 3 以降)

### 2.5 Layer 5: 圏論的普遍性

#### 物理側
TQFT (Atiyah-Segal): bordism category → vector spaces。
Cobordism 仮説 (Lurie): fully extended TQFT = $E_n$-algebra dualizable object.

#### 数学側への翻訳
- 物理過程 = 関手 $F: \text{Bord}_n \to \mathcal{C}$
- 数学的定理 = 関手の自然変換
- 証明 = 自然変換の合成

#### 具体例
- **四色問題** = 平面 bordism category の彩色関手の存在
- **ポアンカレ予想** = $\text{Bord}_3$ の単連結対象の同型分類
- **Reshetikhin-Turaev**, **Crane-Yetter** が部分的具体化

#### 形式化方針
- `structure TQFTSignature` を用意
- Mathlib の `CategoryTheory` ライブラリを利用 (Phase 4)
- Cobordism は research frontier として skeleton

---

## 3. 3 大予想の位置付け

| 予想 | 達する層 | 機構 |
|---|---|---|
| **フェルマー** | L3 (ホログラフィック) | Frey curve (bulk) ↔ modular form (boundary) |
| **ポアンカレ** | L1 + L5 (作用 + 圏論) | Ricci flow を最小作用 + $\text{Bord}_3$ 単連結対象分類 |
| **四色** | L5 (圏論) | 平面 bordism + 有限 reducibility (Bekenstein 内) |
| **Riemann hypothesis** | L4 (スペクトル) | Hilbert-Pólya / Connes-Marcolli |
| **Langlands** | L3 (ホログラフィック) | bulk-boundary 対応の一般化 |

⇒ 主要予想群すべてが 5 層スタック内に**自然な位置**を持つ。

---

## 4. Implementation Phases

### Phase 1 (本 ADR で着手): Layer 1, 2 の Lean 形式化
**ファイル**: `projects/2604-linde/GenerativeStructure.lean`

- `proof_action`, `trivial_proof_zero_action`, `shortest_proof_minimizes_action`
- `IsInvariant`, `invariant_on_orbit`, `invariant_constant_on_orbit`
- 既存 `BlackHoleComputer.lean` との接続定理

これらは具体的に証明可能で、Layer 0/1 が algebraic に閉じる。

### Phase 2: Layer 3 (ホログラフィック) — Frenkel-Witten 文脈
- `structure HolographicPair` の精緻化
- Galois 表現と modular form の対応の type-theoretic skeleton
- Wiles の証明戦略の categorical 化 (research)

### Phase 3: Layer 4 (スペクトル) — Connes-Marcolli
- Mathlib の `OperatorAlgebra` との接続
- adèle class space の formalize
- ζ 関数零点とスペクトルの対応 (axiom)

### Phase 4: Layer 5 (圏論) — Lurie
- Mathlib `CategoryTheory.Bordism` (まだ無い場合は新規 contribute)
- TQFT 関手としての形式化
- 四色問題の TQFT 化 (research)

各 phase は独立 ADR + Lean ファイルとし、段階的に build green を維持する。

---

## 5. Consequences

### 5.1 肯定的帰結

- **解析の方向性が明確**: 上層は下層を消費して新構造を生成
- **既存 BlackHoleComputer.lean が再位置付け**: Layer 0-1 の具体例
- **3 大予想・主要予想の位置が明示**: 各予想がどの層で解かれたか/解かれるかが構造化
- **段階的形式化が可能**: Phase 1 の Layer 1, 2 は本 ADR で完結
- **物理-数学双対の operational な記述**: 哲学的主張ではなく工学的実装

### 5.2 否定的帰結 / リスク

- **Layer 3-5 は research frontier**: 完全形式化は将来の研究次第
- **axiom 依存度が高い**: skeleton は記号的、内容の厳密化が今後必要
- **評価の難しさ**: 「導出」と「invoke」の境界は哲学的に曖昧で、批判の余地あり
- **形式化コストの予測困難**: Layer 4, 5 は Mathlib への大規模 contribution が必要かもしれない

### 5.3 メタ的帰結

ADR-0001 と ADR-0002 を合わせると、以下の世界観が得られる：

$$
\boxed{\text{数学 = 情報 = 物理}}
$$

下からは「計算しない判断」(ADR-0001)、上からは「物理原理が数学構造を生成」(ADR-0002)。
両者を貫くのは **Turing 還元不変量保存則** と **物理的 Church-Turing thesis**。

`projects/2604-linde/` の Verlinde entropic gravity 形式化は、本スタックの
**Layer 0-1 の具体例**であり、上層への scaffold となる。

---

## 6. References

### 6.1 Layer 1 (Proof Action)
- Solomonoff, R. (1964). "A formal theory of inductive inference."
- Susskind, L. (2014). "Computational complexity and black hole horizons."
- Bennett, C. (1973). "Logical reversibility of computation."

### 6.2 Layer 2 (Noether)
- Noether, E. (1918). "Invariante Variationsprobleme."
- Mordell, L. J. (1922). On weil-Mordell.
- Faltings, G. (1983). Mordell 予想の解決.

### 6.3 Layer 3 (Holography)
- Maldacena, J. (1998). "The large N limit of superconformal field theories."
- Manin, Y. & Marcolli, M. (2002). "Holography principle and arithmetic of algebraic curves."
- Frenkel, E. (2007). *Langlands Correspondence for Loop Groups*.

### 6.4 Layer 4 (Spectral)
- Connes, A. (1999). "Trace formula in noncommutative geometry."
- Connes, A. & Marcolli, M. (2008). *Noncommutative Geometry, Quantum Fields and Motives*.
- Berry & Keating (1999). Hilbert-Pólya 関連.

### 6.5 Layer 5 (Categorical)
- Atiyah, M. (1988). "Topological quantum field theories."
- Segal, G. (1988). "The definition of conformal field theory."
- Lurie, J. (2009). "On the classification of topological field theories."
- Witten, E. (1989). "Quantum field theory and the Jones polynomial."

---

## 7. Open Questions

1. Layer 1 の `proof_action` を Lean 4 の証明オブジェクトに直接対応させる方法
2. Layer 2 の Bekenstein bound + symmetry → finiteness の精密化
3. Layer 3 の Manin-Marcolli ホログラフィック対応の Lean 表現
4. Layer 4 の Connes spectral triple を Mathlib に組み込む路線
5. Layer 5 の `Bord_n` の Mathlib への contribute 計画
