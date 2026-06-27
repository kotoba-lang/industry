# ADR-2606272100: Adobe(AI/PSD/PDF) を 純 cljc + EDN データ駆動文法で扱う `kasane`（重ね）ライブラリ（外部依存ゼロ）

**Status**: proposed (draft / たたき台)
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

> 改訂メモ(2026-06-27): 当初ドラフトは binary 解析を yorishiro cell（`psd-tools`/MuPDF/
> pdfium 等の外部 CLI/native）に委譲する案だった。**ImageMagick 等の外部依存を一切持たず、
> 解析自体を純 cljc + EDN データ駆動で行う**方針に全面改訂。外部委譲は「却下した代替」へ移動。

## Context

Adobe Illustrator(`.ai`) / Photoshop(`.psd`) / PDF を **EDN + Clojure cljc** で
扱うライブラリは現状このリポジトリに存在しない（調査: 2026-06-27）。近接物は
`yorishiro_pdftotext`（PDF→テキスト抽出のみ）/ `drawingml-svg` / `svgraph`（Office
グラフィック専用）/ `m365-archive`（psd/pdf の実ファイルのみ）で、どれも目的を満たさない。

**追加要件（本改訂の核）**: ImageMagick・`psd-tools`・MuPDF・pdfium 等の**外部 CLI / native
ライブラリに依存しない**。binary フォーマットの解析・展開・正規化のすべてを
**cljc / kotoba / EDN のデータ駆動**で完結させる。

この要件は ecosystem の哲学と完全に一致する:

- **EDN が第一級データ** — `kotoba-clj` は EDN-subset → WASM コンパイラで、reader は
  `kotoba-edn`。コードではなく**データ（EDN）で振る舞いを駆動**するのが基調。
- **`kotoba-clj` langgraph workstream** で `loop`/`recur` + byte-builder + in-guest
  CBOR decode/encode が入った（CLAUDE.md kotoba 参照）。**バイト列を舐めて構造化する
  純関数を WASM Component 化できる**素地が既にある。
- **content-addressed** — フォーマット文法を EDN データとして持てば、それ自体が CID で
  参照可能な kotoba の fact になる。
- **大容量バイナリ方針（CLAUDE.md）** — raster ピクセルは git/EDN にインラインせず
  B2+DataLad の annex キー(CID)で持つ、がそのまま設計境界。

したがって「外部 parser をラップする」のではなく、**binary 文法を EDN で宣言し、それを
解釈する小さな純 cljc エンジンで解析する**（Kaitai Struct 的なものを EDN データとして
表現し、Clojure で解釈する）方向に倒す。

## Decision

新規 west project **`kasane`（重ね）** を、**外部依存ゼロの純 cljc + EDN データ駆動**で
起こす。AI アートボード / PSD レイヤ / PDF ページの「重なり」を単一の正規化ツリーに写す。

### 0. レイヤ構成（すべて cljc, 外部依存なし）

| ns | 役割 | WASM(kotoba-clj) |
|---|---|---|
| `kasane.bytes`   | byte buffer + cursor 抽象（JVM byte[]/ByteBuffer・cljs Uint8Array/DataView を 1 protocol に）。u8/u16/u32/i*/f*・endian・固定長・null 終端・varint の read primitive | ○ subset 内 |
| `kasane.spec`    | **EDN binary 文法 DSL の仕様**（struct/field/seq/switch/when/sub/codec を EDN データで宣言） | データ（コードでない） |
| `kasane.decode`  | **文法 EDN を解釈する純エンジン**。`(decode grammar buf) → raw EDN`。loop/recur + byte ops のみ | ○ ターゲット中核 |
| `kasane.codec`   | 純 cljc コーデック群（後述）。最重要は `kasane.codec.inflate`（DEFLATE/zlib） | ○ |
| `kasane.cos`     | PDF の COS オブジェクト/xref/trailer/object-stream パーサ（線形文法に乗らない部分） | ○（要 subset 拡張） |
| `kasane.normalize` | raw 解析結果 → 共通 `:kasane/doc` モデル | ○ |
| `kasane.schema`  | **malli スキーマ = 共通モデルの SSoT**（validate/generate）。full Clojure | ✕（検証専用） |
| `kasane.svg`     | vector/text node → SVG 射影（`svgraph`/`drawingml-svg` モデルへ橋渡し） | ○ |
| `kasane.quads`   | `:kasane/*` 述語で kotoba Datom へ射影 | ✕（射影専用） |

**新規外部依存はゼロ**（malli は Clojure ライブラリで native/CLI 依存ではない。WASM 経路は
malli を含めない）。

### 1. EDN binary 文法 DSL（コードでなくデータ）

フォーマットを EDN で宣言し、`kasane.decode` が解釈する。PSD（線形構造で最も素直）の例:

```clojure
{:meta  {:id :psd :endian :big}
 :enums {:color-mode {0 :bitmap 1 :gray 2 :indexed 3 :rgb 4 :cmyk 7 :multi 8 :duotone 9 :lab}
         :compression {0 :raw 1 :rle 2 :zip 3 :zip-pred}}
 :types
 {:psd-file
  [{:id :sig    :type :magic :value "8BPS"}
   {:id :ver    :type :u16}
   {:id :_       :type :skip  :size 6}
   {:id :chans  :type :u16}
   {:id :height :type :u32}
   {:id :width  :type :u32}
   {:id :depth  :type :u16}
   {:id :mode   :type :u16   :enum :color-mode}
   {:id :cmdata :type :blob  :size-prefix :u32}
   {:id :res    :type :blob  :size-prefix :u32}
   {:id :limask :type :struct :of :layer-mask-info :size-prefix :u32}
   {:id :image  :type :struct :of :image-data}]
  :layer-mask-info
  [{:id :count  :type :i16}
   {:id :layers :type :seq :count [:field :count] :of :layer-record}]
  :layer-record
  [{:id :top :type :i32} {:id :left :type :i32} {:id :bottom :type :i32} {:id :right :type :i32}
   {:id :nchan :type :u16}
   {:id :chans :type :seq :count [:field :nchan] :of :channel-info}
   {:id :blendsig :type :magic :value "8BIM"}
   {:id :blend :type :bytes :size 4 :enum :blend-mode}
   {:id :opacity :type :u8}
   ;; … mask / blending ranges / name(pascal) / extra …
   ]}}
```

サポートする field type（最小核）: `:magic` `:skip` `:u8/u16/u32` `:i8/i16/i32`
`:f32/f64` `:bytes(:size|:size-prefix)` `:str(:encoding :len-prefix|:size|:zero-term)`
`:enum` `:struct(:of)` `:seq(:count|:until)` `:switch(:on :cases)` `:when` `:sub`（部分
バイト列を別文法で再帰）`:codec`（後述コーデック適用）。`:count`/`:size` は `[:field k]`
`[:abs n]` `[:expr …]` で先行フィールド参照。**これらは全部 EDN データ** — 形式追加は
コードでなく EDN の追加で済む。

### 2. 純 cljc コーデック（外部展開ライブラリを使わない）

| コーデック | 用途 | 備考 |
|---|---|---|
| **`inflate`（DEFLATE RFC1951 + zlib RFC1950）** | PSD `:zip`、PDF `FlateDecode` | **最重要・唯一の難所**。純 cljc 実装（Huffman + LZ77 窓）。約 ~300 LOC 規模、有界 |
| `packbits`（PSD RLE） | PSD `:rle` | 自明 |
| `lzw` | PDF `LZWDecode` | 小 |
| `ascii85` / `asciihex` | PDF stream filter | 小 |
| `runlength` | PDF `RunLengthDecode` | 小 |

DEFLATE を 1 本入れれば PSD-ZIP と PDF-Flate の双方が賄える。コーデックも
「`{:codec :inflate}`」のように EDN 文法から指名する（データ駆動）。

### 3. PDF だけはハイブリッド（線形文法に乗らないため）

PDF は線形 struct ではなく **COS オブジェクトグラフ + xref + trailer**。ここは
`kasane.cos` に手書きの純 cljc トークナイザ/オブジェクトパーサを置く（dict/array/
stream/ref/name/number/string）。ただし**トークン規則・stream filter 表・content-stream
オペレータ表は EDN テーブル**として持ち、制御フローだけが cljc。xref（classic + xref
stream）・object stream（圧縮オブジェクト）・incremental update を解決し、ページツリーを
辿る。`.ai`（現代）は PDF 互換なのでこの経路に相乗り、legacy AI/PGF private data は
best-effort。

### 4. 共通ドキュメントモデル（正規化 EDN, SSoT）

```clojure
{:kasane/format :psd            ; :psd | :pdf | :ai
 :kasane/canvas {:width 1920 :height 1080 :unit :px :dpi 72 :color-mode :rgb}
 :kasane/nodes  [ … 層/ページ/アートボードの木 … ]
 :kasane/resources {:images [{:id "im1" :cid "bafy…" :w 800 :h 600 :samples :raw}]
                    :fonts  [{:family "…" :cid "bafy…"}]}
 :kasane/meta   {:xmp {…}}}
;; node:
{:node/id "n42" :node/kind :group   ; :group :page :artboard :raster :vector :text …
 :node/name "BG" :node/visible? true :node/opacity 1.0 :node/blend :normal
 :node/bbox [x y w h] :node/transform [a b c d e f] :node/children [ … ]
 :text/runs [{:text "見出し" :font "Noto Sans JP" :size 24 :fill "#111"}]
 :vector/paths [ … ]               ; SVG path 互換（svgraph と共有）
 :raster/blob {:cid "bafy…" :w 800 :h 600 :fmt :raw}}  ; ★ピクセルはインラインしない
```

**不変条件**: 自前で展開した raster サンプル・埋込フォント等は **EDN/git にインライン禁止**。
CBOR/raw blob として B2+DataLad に put し、EDN/node は **CID 参照のみ**（CLAUDE.md 大容量
バイナリ規律）。フォーマット変換（PNG 化等）は R0 では行わず raw サンプルを保持。プレビュー
用 PNG エンコードが要るなら、それも将来 EDN データ駆動の純 cljc エンコーダで足す。

### 5. kotoba 統合は native（yorishiro 不要）

`kasane.decode` は純 cljc なので **kotoba-clj で WASM Component 化**できる
（`run: list<u8> → list<u8>`）。文法 EDN は content-addressed な入力として渡し、出力 EDN を
in-guest CBOR で QuadStore へ。**外部バイナリは経路上どこにも無い**。CLI ラッパー（yorishiro）
経路は本決定で不採用。

### 6. 射影

EDN(canonical, doc CID) / SVG(`kasane.svg`→svgraph) / kotoba Datom(`kasane.quads`)。

## Consequences

- (+) **外部依存ゼロ**。ImageMagick/psd-tools/MuPDF/pdfium 不要。PATH・ライセンス
  （MuPDF AGPL 等）・サンドボックスの懸念が消える。
- (+) **フォーマット追加 = EDN 追加**。文法はコードでなくデータ。content-addressed で
  kotoba に載る。
- (+) 純 cljc なので JVM / cljs / WASM(kotoba-clj) で同一コードが動き、kotoba ノード内で
  native 実行（CALL_FOREIGN も外部 proc も不要）。
- (+) raster 非インラインで EDN/git 軽量、実体は B2+DataLad 既存規律に乗る。
- (−) **自前 DEFLATE/inflate の実装と検証が必須**（PSD-ZIP/PDF-Flate の土台）。純 cljc の
  展開速度は巨大 raster には不利 → 大 raster は遅延・オンデマンド展開し、既定は構造/
  テキスト/ベクタ優先。
- (−) PDF は完全な線形文法に乗らず `kasane.cos` の手書きパーサが要る（データ駆動なのは
  トークン/filter/operator 表まで）。
- (−) **DCTDecode(JPEG)/JPXDecode(JPEG2000) 等の符号化画像は R0 では復号せず opaque blob
  (CID) として通す**（純 cljc JPEG デコーダは規模大、別 ADR）。構造・テキスト・ベクタは取れる。
- (−) AI の private(PGF) は仕様非公開 → best-effort。
- (−) kotoba-clj の EDN-subset では `kasane.cos` 等が現状はみ出す可能性。R0 は JVM/cljs を
  主、WASM は subset 成長（loops/bytes は済、残りは map/string 周り）に追随。

## Alternatives considered

1. **外部 CLI/native parser を yorishiro で委譲（当初案）** — 却下。ImageMagick・psd-tools・
   MuPDF・pdfium 依存はランタイム PATH/ライセンス/移植性の負債。EDN データ駆動という
   ecosystem 哲学にも反する。本 ADR の要件で明示的に不採用。
2. **cljc に各フォーマットを手書きベタ実装（文法 DSL なし）** — 却下。形式ごとに解析コードが
   増殖し保守不能。EDN 文法 + 汎用エンジンなら追加が宣言的。
3. **format ごとに別モデル（共通ツリーなし）** — 却下。横断クエリ/共通変換不可。
4. **raster を EDN にインライン（base64 等）** — 却下。CLAUDE.md 大容量バイナリ規律違反。

## Migration / 立ち上げ手順

1. 本 ADR をマージ（`.md` + `.edn`）。
2. west project 追加: `manifest/repos.edn` に `com-junkawasaki/kasane` を登録 →
   `bb scripts/gen-west-manifest.bb`（手書き禁止 / CI は `--check`）。
3. **基盤先行**: `kasane.bytes` → `kasane.spec`(DSL 仕様) → `kasane.decode`(エンジン) →
   `kasane.codec.inflate`(DEFLATE) を、ゴールデンベクタ（zlib RFC1951 test vectors）付きで固める。
4. **PSD を最初の E2E**: `grammar/psd.edn` を書き、PackBits + ZIP チャンネルを `decode`→
   `normalize`→`:kasane/doc`→ malli validate まで 1 本通す（最も線形で検証が楽）。
5. **PDF**: `kasane.cos`（xref/trailer/object-stream）+ FlateDecode + content-stream
   operator 表 → ページ/テキスト/ベクタ抽出。`.ai`(現代)は相乗り。
6. `kasane.quads`(QuadStore 射影) と `kasane.svg`(svgraph 描画) を接続。WASM 化は
   kotoba-clj subset の成熟に合わせて `decode`/`inflate` から段階適用。

## 実装状況（2026-06-27 追記）

リポジトリ: **`com-junkawasaki/kasane`**（private, west project に登録済み）。
構成は **1 リポ + 名前空間分割**で確定（形式ごとの別リポ `psd-clj`/`pdf-clj`/`ai-clj` は
不採用 — 共有コアへの 4 リポ依存とバージョン結合を避ける。形式追加は「ns + grammar EDN
追加」で行い、独立 Clojars 公開が必要になった形式だけ将来 `kasane-<fmt>` に切り出す）。

| 領域 | ns / 成果物 | 状態 |
|---|---|---|
| コア | `kasane.bytes` / `kasane.decode`(EDN 文法エンジン, `:seq :until-eof` 含む) / `kasane.codec`(+`.inflate` 純 DEFLATE/zlib) / `kasane.schema`(malli SSoT) | ✅ |
| **PSD** | `grammar/psd.edn`(データ) + `normalize/psd->doc` | ✅ ヘッダ+レイヤ |
| **PDF** | `kasane.cos`(COS/xref/trailer/page-tree/FlateDecode/BT-ET text) + `pdf->doc` | ✅ R0 |
| **AI** | `ai->doc` が COS/PDF 経路を再利用（現代 .ai = PDF）。page→`:artboard` node | ✅ R0 |
| **PNG** | `grammar/png.edn`(データ) + `kasane.png`(IDAT=zlib→既存 inflate + unfilter) + `png->doc` | ✅ |
| **BMP** | `grammar/bmp.edn`(データ, LE) + `bmp->doc` | ✅ ヘッダ（pixels=blob） |
| **TIFF** | `kasane.tiff`(offset-based IFD 手書き, byte-order 自動判定, SHORT/LONG) + `tiff->doc` | ✅ メタ（dims/comp/bps） |
| **GIF** | `grammar/gif.edn`(header/LSD) + `kasane.gif`(frame scan) + `gif->doc` | ✅ dims/frames（LZW pixel は保留） |
| **ZIP** | `kasane.zip`(中央ディレクトリ + member を inflate-raw) | ✅ Sketch/.docx/.xlsx/.pptx/ODF/EPUB の基盤 |
| **Sketch** | `sketch->doc`(`pages/*.json`→artboard、`kasane.json` で意味解析) | ✅ artboard 名+frame→bbox |
| **OOXML** | `ooxml->doc`(docx/pptx/xlsx 判定 + テキスト抽出 w:t/a:t/t) | ✅ text runs |
| **JSON** | `kasane.json`(純 cljc 依存ゼロ JSON リーダ) | ✅ Sketch 等の基盤 |
| **TTF/OTF** | `kasane.ttf`(SFNT table directory + head/maxp/name) | ✅ family/units/glyph 数（実 OFL font で head magic 検証） |
| **LZW** | `kasane.codec/lzw` (MSB early / LSB) | ✅ TIFF=bit-exact(実 libtiff)、PDF=対応 / ⚠ GIF=experimental(画素数のみ一致) |
| テスト | bb 純 cljc スイート | ✅ **27 tests / 104 assertions green** |

DEFLATE/zlib inflate は PSD-ZIP・PDF-Flate・PNG-IDAT・**ZIP(Sketch/OOXML/ODF/EPUB)** を
**1 本で**賄えており、「形式追加＝EDN/ns 追加で増える」設計が実証された（PDF は線形文法に
乗らないので `kasane.cos`、TIFF/TTF は offset-based なので `kasane.tiff`/`kasane.ttf`、ZIP は
`kasane.zip` と、非線形だけ手書きに分岐）。

**LZW** は実 libtiff エンコードの fixture で **TIFF を bit-exact 検証**（9→10→11→12bit の
code-width 境界跨ぎ含む。確定規則: early-change で next-free-code == 2^width−1 で width++）。
**GIF(LSB) は experimental** — 画素数は正しいが行/辞書境界で参照と値が食い違うため未確定
（共有 LZW コアは TIFF で実証済み）。JPEG 系（DCT/JPX）は別 ADR、R0 は opaque blob 通し。
次手: GIF LZW の境界整合、Sketch/OOXML のレイヤ/図形ジオメトリ、glTF/WOFF2。

## References

- 近接実装: `orgs/com-junkawasaki/kotoba/crates/kotoba-kotodama/cells/yorishiro_pdftotext/`,
  `orgs/com-junkawasaki/drawingml-svg/`, `orgs/com-junkawasaki/svgraph/`
- EDN/WASM 基盤: `kotoba-clj`（EDN-subset → WASM, reader=`kotoba-edn`,
  langgraph workstream で loop/recur + bytes + in-guest CBOR 済み）
- グラフ射影先: kotoba QuadStore / Datom（`kotoba-graph` / `kotoba-query`）
- 大容量バイナリ規律: `CLAUDE.md`「大容量バイナリの扱い（B2 + DataLad）」,
  ADR-2606241428
- manifest 運用: `manifest/repos.edn`, `scripts/gen-west-manifest.bb`, ADR-2606271500
- 文法 DSL の先行概念: Kaitai Struct（ただし本設計は YAML でなく **EDN データ** + Clojure 解釈）
