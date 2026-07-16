# ADR-2606280010: kasane の JPEG/DCT 復号は別ライン（R0 はメタデータ + opaque blob）

**Status**: proposed (draft / たたき台)
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

`kasane`（ADR-2606272100）は外部依存ゼロの純 cljc + EDN データ駆動で多形式を扱う。
ラスタの**展開**は次を純 cljc で実装済み:

- DEFLATE/zlib(`inflate`) → PSD ZIP / PDF FlateDecode / PNG IDAT / ZIP(Sketch/OOXML)
- PackBits(RLE) → PSD / TIFF
- **LZW** → TIFF(compression 5, bit-exact 検証) / GIF / PDF LZWDecode

しかし **DCT 系（JPEG/JFIF、PDF の DCTDecode、TIFF compression 7、HEIC/AVIF 等）** の
ピクセル復号は未実装。これらは離散コサイン変換ベースで、展開フィルタ 1 本では済まない。

JPEG は ecosystem 横断で頻出する（写真、PDF 埋込画像、PSD smart object、HEIC の基盤は別
コーデックだが構造は類似）。「どこまでを kasane R0 でやるか」を決める必要がある。

## Decision

**JPEG/DCT のピクセル復号は kasane R0 では行わない。** 次の二段構えにする:

### R0（実装済み）: メタデータ + opaque blob

- **`kasane.jpeg`**: マーカーセグメントを走査し、SOF0/1/2 から **幅・高さ・成分数・
  progressive 判定**を取得（エントロピー符号化スキャンは復号しない）。
- **`normalize/jpeg->doc`**: `:kasane/doc` に canvas 寸法 + `:raster` ノード（`:raster/blob`
  に `:fmt :jpeg` でポインタ）。**実体はインラインせず B2+DataLad の CID 参照**（CLAUDE.md
  大容量バイナリ規律）。PDF/TIFF 内の DCT ストリームも同様に opaque blob として通す
  （構造・テキスト・ベクタは取得済み）。

これにより「JPEG を含むドキュメントでも、構造とメタデータは完全に EDN 化でき、画素だけ
未復号」という一貫した状態になる。

### R1（**実装済み 2026-06-28**: `kasane.jpeg.decode`）: 純 cljc baseline JPEG デコーダ

> 状態更新: baseline(SOF0) デコーダを実装。実 JPEG(Pillow quality-80, 4:2:0)に対し
> **平均絶対誤差 1.615 / 最大 6**（float IDCT のため bit-exact でなく bit-close）。
> progressive(SOF2)・restart 多用は best-effort。設計は以下のとおり:

別 crate/ns（例 `kasane.jpeg.decode`）として、baseline(SOF0) + progressive(SOF2) を段階実装:

1. **マーカー解析**: DQT(量子化表) / DHT(Huffman 表) / SOF(フレーム) / SOS(スキャン) / DRI(restart)。
2. **エントロピー復号**: Huffman（DC 差分 + AC run-length/zigzag）。restart マーカー対応。
3. **逆量子化** → **IDCT**（8×8、整数 AAN もしくは float）。
4. **クロマアップサンプリング**（4:2:0 / 4:2:2 / 4:4:4）。
5. **色変換** YCbCr→RGB（JFIF）。
6. 出力 = raw サンプル → **B2 blob（インラインしない）**。

検証は **実 JPEG fixture（Pillow/libjpeg 生成）を oracle に bit-近似**（IDCT 丸めのため
完全一致でなく許容誤差±1〜2 で検証）。LZW を実 libtiff fixture で確定したのと同じ方式。

### 範囲外（さらに別 ADR）

- **JPEG2000**（ウェーブレット）/ **HEIC・AVIF**（HEVC/AV1 intra）/ **WebP lossy**（VP8）
  — コーデック規模が JPEG よりさらに大きく、R1 でも対象外。R0 同様 opaque blob 通し。

## Consequences

- (+) JPEG を含む資産でも構造/テキスト/寸法は今すぐ EDN 化・グラフ射影できる。
- (+) 純 cljc・外部依存ゼロの原則を崩さない（libjpeg 等を runtime 依存にしない）。
- (+) R1 の設計が確保され、必要になったら fixture 駆動で安全に実装できる。
- (−) R0 では JPEG 画素にアクセスできない（プレビュー生成等は不可）。必要なら R1 を前倒し。
- (−) IDCT の丸めにより、R1 でも実装間で完全一致は保証されない（許容誤差で検証）。
- (−) progressive JPEG は baseline より複雑（複数スキャン・スペクトル選択）。R1 でも後段。

## Alternatives considered

1. **R0 で baseline JPEG をフル実装** — 却下。Huffman+IDCT+upsample+色変換は規模が大きく、
   他形式（PSD/PDF/PNG/TIFF/GIF/ZIP/glTF/SVG/TTF）の R0 を遅らせる。段階分離が妥当。
2. **libjpeg / ImageMagick / PIL を runtime 依存に** — 却下。kasane の外部依存ゼロ原則
   （ADR-2606272100）に反する。fixture 生成（テスト時）に使うのは可。
3. **JPEG を一切扱わない** — 却下。寸法・成分・progressive 判定は marker 走査で安価に取れ、
   ドキュメントモデルの一貫性に寄与する（R0 で実装済み）。

## References

- 親 ADR: ADR-2606272100（kasane 設計、外部依存ゼロ・データ駆動）
- 実装: `orgs/kotoba-lang/kasane/src/kasane/jpeg.cljc`（R0 メタデータ）,
  `normalize/jpeg->doc`
- 大容量バイナリ規律: `CLAUDE.md`「大容量バイナリの扱い（B2 + DataLad）」
- 既存ラスタ展開: `kasane.codec`(inflate/packbits/lzw)、`kasane.tiff`/`kasane.png`/`kasane.gif`
