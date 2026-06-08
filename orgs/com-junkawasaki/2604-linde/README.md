# 2604-linde — Verlinde の Entropic Gravity を Lean で証明する

Erik Verlinde, *"On the Origin of Gravity and the Laws of Newton"*
([arXiv:1001.0785](https://arxiv.org/abs/1001.0785), 2010) の中心的な
主張 ―― **重力はホログラフィック・スクリーン上のエントロピー力として
創発する** ―― を、Lean 4 + Mathlib で形式化したミニ・プロジェクト。

## なにを証明しているか

以下 5 つの物理的仮定だけから、ニュートン万有引力の法則
`F = G M m / R²` が**純粋な代数恒等式として**したがうことを示す。

| # | 仮定 | 数式 |
|---|---|---|
| 1 | ホログラフィック・スクリーン (球殻面積) | `A = 4π R²` |
| 2 | ホログラフィック束縛 (スクリーン上のビット数) | `N = A c³ / (G ℏ)` |
| 3 | Unruh 温度 | `T = ℏ a / (2π kB c)` |
| 4 | 等分配定理 | `E = ½ N kB T` |
| 5 | 質量・エネルギー等価性 | `E = M c²` |

`(1)`–`(5)` を縮約すると一本の方程式

```
½ · (4π R² c³ / (Gℏ)) · kB · (ℏ a / (2π kB c))  =  M c²
```

になる。本プロジェクトの主定理 `Verlinde.gravitational_acceleration`
はこの式から `a = G M / R²` を導く。系として
`Verlinde.newton_law_of_gravitation : m * a = G * M * m / R^2` が出る。

さらに副定理 `Verlinde.entropic_inertia` で

> エントロピー力 `F · Δx = T · ΔS`、コンプトン波長 `Δx = ℏ/(m c)`、
> エントロピー量子化 `ΔS = 2π kB`、Unruh 温度 ⇒ `F = m a`

というニュートン第二法則の創発も示している。

## 証明の構造

`R² c² a` の係数だけが残るように `field_simp + ring` で代数簡約し、
`linear_combination` と `mul_right_cancel₀` で `c²` を消去するだけで
クローズする。物理定数は単なる正の実数として扱い、`R, c, ℏ, kB, G ≠ 0`
のみを仮定する。

```
       Step 1.  field_simp; ring          ─→  R² c² a / G = M c²
       Step 2.  div_eq_iff                ─→  R² c² a    = M c² G
       Step 3.  linear_combination + cancel ─→  R² a      = G M
       Step 4.  eq_div_iff                ─→  a          = G M / R²
```

## ディレクトリ構成

```
projects/2604-linde/
├── README.md          # このファイル
├── lakefile.lean      # Lake プロジェクト設定 (Mathlib v4.15.0)
├── lean-toolchain     # Lean ツールチェイン
├── .gitignore
└── Verlinde.lean      # 主定理・副定理・数値計算をすべて含む
```

## ビルド方法

```bash
cd projects/2604-linde
lake update      # Mathlib を取得 (初回のみ。数分かかる)
lake exe cache get  # コンパイル済み Mathlib をダウンロード (推奨)
lake build       # Verlinde.lean を型検査
```

`lake env lean Verlinde.lean` で `#eval` の出力を確認できる。

## 数値計算 (SI 単位)

`Verlinde.lean` 末尾の `#eval` で以下を計算している:

| 物理量 | 期待値 |
|---|---|
| 地球表面の重力加速度 `g = G M⊕ / R⊕²` | ≈ 9.82 m/s² |
| 地球サイズのホログラフィック・ビット数 `N` | ≈ 2.0 × 10⁸⁴ |
| 地球サイズホライズンの Bekenstein–Hawking エントロピー (`S/kB = N/4`) | ≈ 4.9 × 10⁸³ |
| 1 g における Unruh 温度 | ≈ 4 × 10⁻²⁰ K |
| 質量 1 kg 地球表面の万有引力 `F = G M⊕ / R⊕²` | ≈ 9.82 N |

## 物理的解釈

* **「重力は基本相互作用ではなく、自由度の数え方そのものである」**
  というのが Verlinde の主張。ホログラフィック・スクリーンが
  情報を貯蔵し、その情報量 (= 面積) が場所によって異なるとき、
  系は自由エネルギー最小化の方向へ「押される」。これがエントロピー力。
* 本ファイルが証明しているのは、**「ホログラフィック原理 + 等分配 +
  Unruh 効果 ⇒ Newton 重力」** という代数的構造。
* AdS/CFT、ブラックホール熱力学、creation of bits 議論の代数的核として
  そのまま使える。

## 参考文献

* E. Verlinde, *On the Origin of Gravity and the Laws of Newton*,
  JHEP **04** (2011) 029, arXiv:1001.0785.
* T. Jacobson, *Thermodynamics of Spacetime: The Einstein Equation of
  State*, Phys. Rev. Lett. **75** (1995) 1260, gr-qc/9504004.
* J. D. Bekenstein, *Black holes and entropy*, Phys. Rev. D **7** (1973) 2333.
* W. G. Unruh, *Notes on black-hole evaporation*, Phys. Rev. D **14**
  (1976) 870.
