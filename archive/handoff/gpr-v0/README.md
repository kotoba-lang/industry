# gpr

**地中レーダー（ground-penetrating radar）の trace → 断面 → 埋設物候補。純 `.cljc`、依存ゼロ。**

subject 面の repo（`manifest/repository-rules.edn` の `:plane-order`）。他者の仕様や
製品の実装ではなく、実行役割でもなく、既存 family にも属さない再利用ライブラリなので
bare 名。設計の根拠は superproject の **ADR-2608750000**。

この repo が答えるのは 4 つだけ:

1. **A-scan / B-scan の値をどう持つか** — `gpr.trace`
2. **前処理** — dewow・背景除去・time-zero・利得・包絡（`gpr.dsp`、FFT は `gpr.fft`）
3. **速度と深度の変換、点状散乱体の双曲線** — `gpr.velocity`
4. **双曲線フィットから候補の特徴量を出す** — `gpr.detect`

## 何を持たないか（意図的に）

- **機材とファイル形式を知らない。** SEG-Y / DZT 等ベンダ形式の読み取りは origin 面の
  別 repo が持つ。出所ドメインを DNS で実測してから命名する規則なので、推測した名前の
  parser をここに置かない（ADR-2608750000 D3）。入り口は `gpr.trace` の素のデータ構造。
- **判断を持たない。** 「この候補を報告するか」「その深さまで掘ってよいか」は
  [`kotoba/candidate_core.kotoba`](kotoba/candidate_core.kotoba) の decision core にある。
  `gpr.detect` が出すのは測った数値までで、閾値の既定値もここには焼かない。
- **法令上の義務を判断しない。** 労働安全衛生規則 355 条の事前調査、道路占用、埋設物照会は
  cloud-itonami の civil-engineering actor（`isic-4210` / `4220` / `4290` / `4312` / `4322`）が
  permits データセットとともに所有している。
- **描画を持たない。** 断面・点群の表示は kami-engine stack（WebGPU first / WebGL 2.0
  fallback）。第 2 の 3D エンジンを持ち込まない。
- **ハードウェアを持たない。** アンテナ・送受信・A/D は範囲外。

## 使う

```clojure
(require '[gpr.dsp :as dsp] '[gpr.detect :as detect] '[gpr.result :as r] '[gpr.trace :as t])

(def bs (t/b-scan [(t/a-scan samples 0.1 0.00)      ; dt = 0.1 ns, x = 0.00 m
                   (t/a-scan samples 0.1 0.05)]))   ; …測線に沿って

(r/bind (dsp/envelope-b-scan bs)
        (fn [env] (detect/features env {:from-ns 0.0 :to-ns 51.2} 0.05)))
;; => [:ok {:apex-x-m 2.0 :twt-ns 16.0 :velocity-m-per-ns 0.1 :permittivity 9.0
;;          :depth-m 0.8 :r2 0.999 :n-picks 81 :aperture-m 4.0 :peak-amplitude-ratio 1.0}]
```

失敗は例外ではなく `[:err {:code … :message …}]` で返る（`throw` / `try` / `catch` は
使わない — ADR-2608650000 の恒久制約）。

## 測ったこと / 測っていないこと

```
npx --yes nbb run-tests.cljs     # 23 tests / 48 assertions
```

**測った**（実行して両方向を見た）:

- FFT の往復・δ 関数の平坦スペクトル・正弦波のビン位置・包絡の山が波形中心に立つこと
- 比誘電率 ⇄ 速度 ⇄ 深度の往復と、値域外入力が err になること
- 合成 B-scan（v=0.1 m/ns、深さ 0.8 m、apex x=2.0 m、Ricker 400 MHz）からの復元 —
  速度 ±5% / TWT ±5% / 深度 ±10% / apex ±0.1 m、ノイズ 2% でも速度 ±10%
- 検査が**落ちること** — 速度の式を 2 倍に壊すと 4 件 fail・exit 1、無改変で exit 0
- 実行本数の床 — 25 assertion 未満なら「合格」ではなく **exit 2**（0 でも 1 でもない値）

**測っていない**（ここを緑と読まないこと）:

- **`kotoba/candidate_core.kotoba` は一度もコンパイル・実行されていない。** scaffold した
  環境に clojure CLI が無く `amu compile` を回せなかった。判定表
  [`resources/candidate-decision-vectors.edn`](resources/candidate-decision-vectors.edn) は
  書いてあるが、突き合わせる parity harness は未実装。**この decision core は現在
  「書いてある」だけである。**
- **実測データが 1 本も無い。** 検査はすべて合成 B-scan に対するもので、確かめられるのは
  実装が式どおりであることだけ。**閾値が現場で妥当かどうかは合成では決して分からない**
  （合成の仮定を検証してしまう）。だから既定の閾値をこの repo に持たせていない。
- **複数の双曲線の分離が無い。** `gpr.detect/features` は測線上の対象を 1 つと仮定する。
  「候補が 1 件」は「対象が 1 つ」の証拠ではない。
- **送受信間距離（offset）の補正が無い。** `hyperbola-twt` は offset 0 の近似なので、
  実機データの浅部で系統的にずれる。
- **fleet gate に載っていない。** CI は murakumo fleet（GitHub Actions は使わない）。

## 隣

| repo | 境界 |
|---|---|
| `kotoba-lang/voxel` `mesh` `mesher` | 断面・点群の 3D 表現。この repo は数値までで、描画はそちら |
| `kotoba-lang/com-gnss-rtk` | trace に cm 級の座標を付ける。`:gpr/x-m` の出所 |
| `kotoba-lang/org-openstreetmap-overpass` `com-mapillary-graph-api` | 地上側の地物台帳 |
| `cloud-itonami/cloud-itonami-isic-4290` ほか | 掘削・埋設物照会の許認可と法令判断 |
