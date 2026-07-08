---
id: adr-2607082500-kotoba-lang-kasane-utsushi-reverse-domain-decomposition
title: "ADR-2607082500: kasane/utsushi の DSL 分解 — 15新規 reverse-domain repo + ISOBMFF統合 + H.264/AAC/Opus新規実装"
status: accepted
doc_type: adr
topic: naming
authoritative: true
last_verified: 2026-07-08
authoritative_for:
  - kasane（ImageMagick相当）/utsushi（ffmpeg相当）に同居していた個別バイナリ
    フォーマット仕様を org-<標準化団体>-<spec> reverse-domain命名規約
    （ADR-2607052300以降の一連のADRで確立）に揃える判断
  - kasane.isobmff（AVIF/HEICメタ読取）とutsushi.container/demux/mux/remux
    （MP4）が同一仕様(ISO/IEC 14496-12)の重複実装だったと判明し、1つの
    org-iso-isobmffへ統合した判断
  - utsushi.bitstreamの全関数が未実装TODO stubだったと判明し、抽出ではなく
    新規実装（org-iso-h264/org-iso-aac/org-ietf-opus）で埋めた判断
related:
  - orgs/kotoba-lang/kasane
  - orgs/kotoba-lang/utsushi
  - orgs/kotoba-lang/org-ietf-deflate
  - orgs/kotoba-lang/org-adobe-tiff
  - orgs/kotoba-lang/org-iso-jpeg
  - orgs/kotoba-lang/org-iso-opentype
  - orgs/kotoba-lang/org-w3-epub
  - orgs/kotoba-lang/org-oasis-odf
  - orgs/kotoba-lang/org-w3-png
  - orgs/kotoba-lang/org-compuserve-gif
  - orgs/kotoba-lang/org-pkware-zip
  - orgs/kotoba-lang/org-iso-pdf
  - orgs/kotoba-lang/org-w3-woff
  - orgs/kotoba-lang/org-iso-isobmff
  - orgs/kotoba-lang/org-iso-h264
  - orgs/kotoba-lang/org-iso-aac
  - orgs/kotoba-lang/org-ietf-opus
  - 90-docs/adr/2607072500-kotoba-lang-semiconductor-standards-reverse-domain-batch.md
  - 90-docs/adr/2607052300-kotoba-lang-turn-rt-net-signal-reverse-domain-rename.md
  - 90-docs/adr/2606272100-adobe-edn-kasane.md
  - 90-docs/adr/2606272200-utsushi-video-edn-filtergraph.md
  - 90-docs/adr/2606280010-kasane-jpeg-dct-decode.md
supersedes: []
superseded_by: []
---

# ADR-2607082500: kasane/utsushi の DSL 分解

- Status: accepted (2026-07-08)

## 背景

オーナーからの依頼: `kasane`（ADR-2606272100、ImageMagick相当）と `utsushi`
（ADR-2606272200、ffmpeg相当）に、それぞれ十数個の独立した外部フォーマット仕様
（PNG/JPEG/GIF/TIFF/ZIP/PDF/OpenType/WOFF/ISOBMFF/H.264/AAC/Opus…）が
namespace単位で同居してしまっている状態を解消する。これは kotoba-lang の
既存命名規約 — `org-<標準化団体>-<spec>` / `io-<domain>` / `com-<vendor>` /
`edu-<institution>` の reverse-domain 命名（ADR-2607052300以降の一連のADRで
確立、直接の前例は ADR-2607072500: `pdk` に紛れ込んでいた Liberty/LEF パーサを
`org-synopsys-liberty`/`org-si2-lef` として分離しつつ8個の未着手規格を新規
`org-<body>-<spec>` リポジトリとして一括scaffoldしたバッチ）— に反する。

調査で3点の重要な事実が判明した:

1. `kasane.isobmff`（AVIF/HEICメタ読取、flat box walker）と
   `utsushi.container`/`demux`/`mux`/`remux`（MP4、nested-tree walker + 完全な
   demux/mux/remuxパイプライン）は**同一仕様(ISO/IEC 14496-12)の重複実装**
   だった。しかも `utsushi.container` は `meta` box が FullBox（4byte
   version+flags prefix）であることを知らず、`kasane.isobmff` 側にのみ
   その正しい処理があった — 統合はコード重複解消だけでなく実バグ修正も兼ねた。
2. `kasane.gltf`→既存 `org-khronos-gltf`/`org-khronos-glb`（書き専用）、
   `kasane.svg`→既存 `org-w3-svg`（書き専用）、`kasane.json`→既存
   `kotoba-lang/json`、kasaneのOOXML投影（`kasane.normalize/ooxml->doc`）→
   既存 `ooxml`/`office`/`office-style`/`drawingml` クラスタは、**読み書き
   逆または完全重複**。新規repoを作らず既存への統合が必要（本バッチでは
   follow-upとして未着手、両repoのREADMEに記録済み）。
3. `utsushi.bitstream`（H.264 NAL分割/SPS、AAC ADTS framing 用と設計文書に
   記載）は**全3関数が未実装のTODO stub**（`(throw (ex-info "TODO: ..." {}))`）
   だった。抽出ではなく新規実装が必要と判明。

AskUserQuestionで4点を確認（ADR-2607072500と同型の事前確認手順）:
JPEG/H.264/AACの団体prefix(ISO/IEC統一)、GIF命名(`org-compuserve-gif`)、
TIFF/ZIP命名(`org-adobe-tiff`/`org-pkware-zip`、vendor発行でも公開仕様書が
あればorg-にするSynopsys SDC/Liberty前例踏襲)、実行範囲(フルバッチ)。

## 決定

### 1. 命名決定

| 対象 | 決定名 | 根拠 |
|---|---|---|
| JPEG/H.264/AAC/ISOBMFF/PDF/OpenType | `org-iso-*` 統一 | ISO/IEC番号(10918-1/14496-10/13818-7/14496-12/32000/14496-22)が実装者に最も引用される識別子。prefixが揃う |
| GIF | `org-compuserve-gif` | CompuServe発行のGIF89a仕様書は実在・現役（w3.orgミラーあり）。Adobe/PKWARE/Synopsysと同じ「vendor発行でも公開仕様書があればorg-」パターン |
| TIFF | `org-adobe-tiff` | Adobe発行のTIFF 6.0仕様書、同上パターン |
| ZIP | `org-pkware-zip` | PKWARE発行・維持のAPPNOTE.TXT、同上パターン |
| PNG/WOFF/EPUB | `org-w3-*` | W3C Recommendation（PNG/WOFFはw3.org/TR、EPUB3はIDPFがW3Cへ統合後の仕様） |
| DEFLATE/Opus | `org-ietf-*` | RFC番号(1951/1950・6716)を持つIETF仕様 |
| ODF | `org-oasis-odf` | OASIS標準（ISO/IEC 26300とも共同） |

### 2. 分解マッピング（kasane）

| 旧 ns | 仕様 | 移動先 | 依存 |
|---|---|---|---|
| `kasane.codec.inflate` | DEFLATE(RFC1951)+zlib(RFC1950) | `org-ietf-deflate` | なし（leaf） |
| `kasane.png` | PNG(W3C) | `org-w3-png` | org-ietf-deflate |
| `kasane.gif` | GIF89a | `org-compuserve-gif` | なし（自前LZW変種） |
| `kasane.tiff` | TIFF 6.0 | `org-adobe-tiff` | なし（自前LZW/PackBits変種） |
| `kasane.zip` | ZIP(PKWARE) | `org-pkware-zip` | org-ietf-deflate |
| `kasane.jpeg`+`kasane.jpeg.decode` | JPEG | `org-iso-jpeg` | なし |
| `kasane.ttf` | TrueType/OpenType | `org-iso-opentype` | なし |
| `kasane.woff` | WOFF 1.0 | `org-w3-woff` | org-iso-opentype + org-ietf-deflate |
| `kasane.isobmff` | ISOBMFF | `org-iso-isobmff`（utsushiと統合） | なし |
| `kasane.cos` | PDF/COS(ISO 32000) | `org-iso-pdf` | org-ietf-deflate |
| `kasane.normalize/epub->doc` | EPUB 3 | `org-w3-epub` | なし（既にunzip済みentriesを受け取る純関数） |
| `kasane.normalize/odf->doc` | ODF | `org-oasis-odf` | なし（同上） |

kasaneに残るのは: `kasane.bytes`/`kasane.decode`（EDN文法エンジン本体）、
`kasane.codec`（PackBitsのみ、PSD用）、`kasane.normalize`/`kasane.schema`
（共通`:kasane/doc`モデル — 抽出後の各repoの生parse出力も純関数として受けられる
射影として維持）、そしてPSD/BMP/Sketchというkasane自身のflagship native
フォーマット。`kasane.gltf`/`kasane.svg`/`kasane.json`/OOXML投影は前述の
follow-upとして意図的に未変更（既存repoとの統合が必要で単純削除は不可）。

### 3. 分解マッピング（utsushi）

| 旧 ns | 仕様 | 移動先 | 備考 |
|---|---|---|---|
| `utsushi.container`/`demux`/`mux`/`remux`/`bytes`/`blob` | ISOBMFF | `org-iso-isobmff`（kasane.isobmffと統合） | metaのFullBox処理をkasane側から継承、実バグ修正 |
| `utsushi.bitstream`(全stub) | H.264 NAL/SPS framing | `org-iso-h264`（新規実装） | 実libx264エンコード済みfixtureで検証(64x48が一致) |
| 〃 | AAC ADTS framing | `org-iso-aac`（新規実装） | 実ffmpeg/libavcodecエンコード済みfixtureで検証(44100Hz mono一致) |
| （未着手・新規） | Opus packet framing(RFC 6716) | `org-ietf-opus`（新規実装） | RFC精密仕様表からの手動構築テストベクタで検証 |

utsushiに残るのは `utsushi.codec`（R1 façade）/`utsushi.graph`/`utsushi.pregel`
（filtergraph BSP実行）/`utsushi.policy`（capability/effect/gas モデル）/
`utsushi.quads` — kotoba `defgraph`/Pregel BSP/capability-gated codecという
utsushi固有の知的財産。`utsushi.codec`と`utsushi.pregel`はorg-iso-isobmffへの
git依存に書き換え、実物のorg-iso-isobmffに対してfiltergraphのdeny-by-default・
effect soundness(T2)・per-frame gas・BSP決定論・CID-MVメモ化を全てend-to-endで
再検証（6 tests/16 assertions green）。

### 4. レイヤリング原則（循環依存ゼロ）

抽出後の各formatrepoは原則leaf。唯一の例外はDEFLATE — PNG/ZIP/WOFF/PDFから
一方向で依存される（RFC1951/1950で完全に仕様一致・format間差異が無いため
共有価値が高い）。PackBits/LZWは共有repo化せず、必要な各repo（kasane本体は
PSD用PackBits、org-adobe-tiff/org-compuserve-gif/org-iso-pdfはそれぞれ自前の
小さなvariant実装）が個別に持つ — LZW自体がGIF/TIFF/PDFでbit-packing規約が
異なる（早期code-width変更のタイミング等）ため、単一実装の共有に実益が薄い。
byte-cursor等の極小プリミティブも同様に各repoが自前で持つ（既存の
`utsushi.bytes`/`kasane.bytes`併存という前例と整合）。

## 実施内容

- **15新規repo**を `kotoba-lang` org に scaffold・push・テストgreen確認
  （zero-dep `.cljc`、Apache-2.0、既存repoから移植したtest + fixtureをそのまま
  再利用。org-iso-h264/org-iso-aac/org-ietf-opusのみ新規実装につき新規test）。
- `kasane`/`utsushi` 本体は抽出済みnamespaceを削除し、新規repoへの
  `:git/sha` 依存 + 薄いdelegate呼び出しに配線し直し。両方とも既存test
  スイートがgreenのまま（kasane: 13 tests/51 assertions、utsushi:
  6 tests/16 assertions）。
- `manifest/repos.edn` に15新規repoを追加、`bb scripts/gen-west-manifest.bb
  --entry <17名>` でwest.yml最小diff生成（15新規 + kasane/utsushi pin前進、
  全17件サーバ側pin検証OK）。

## 検証

```bash
# 各新規repo（例）
cd orgs/kotoba-lang/org-iso-h264 && clojure -M:test   # 3 tests / 10 assertions, 0 failures
cd orgs/kotoba-lang/org-iso-isobmff && clojure -M:test # 5 tests / 28 assertions, 0 failures

# kasane/utsushi 本体（rewiring後）
cd orgs/kotoba-lang/kasane && clojure -M:test    # 13 tests / 51 assertions, 0 failures
cd orgs/kotoba-lang/utsushi && clojure -M:test   # 6 tests / 16 assertions, 0 failures（org-iso-isobmffへの実git依存越しに検証）

gh api repos/kotoba-lang/org-iso-h264 --jq '.full_name'   # 実在確認（15repo分繰り返し）
bb scripts/gen-west-manifest.bb --entry <17名>              # OK、全件サーバ側pin検証済み
```

## Consequences

- (+) kasane/utsushiは「フォーマットDSLの寄せ集め」から「EDN文法エンジン/
  filtergraphオーケストレータ + 各仕様への依存」という健全な構造になった。
- (+) ISOBMFFの重複実装が解消され、副産物として `meta` FullBox処理のバグが
  修正された（kasane側の正しい実装がutsushi側にも伝播）。
- (+) H.264/AAC/Opusという、これまでkotoba-lang全体に存在しなかった
  framing実装が実エンコーダ生成fixtureで検証された形で追加された。
- (+) 各新規repoは他のcreative/media系kotoba-lang repo（douga/koe等）からも
  独立して再利用可能になった。
- (−) `kasane.gltf`/`kasane.svg`/`kasane.json`/OOXML投影の既存repoへの統合は
  follow-up未着手（両repoのREADMEに記録済み）。単純削除ではなく統合PRが
  必要なため本バッチのスコープ外とした。
- (−) `org-iso-h264`/`org-iso-aac`/`org-ietf-opus`はutsushiのfiltergraphから
  まだ配線されていない（`:decode`nodeの前段としてbitstream framingを呼ぶ
  接続は未実装、utsushi READMEにfollow-up記録済み）。
- (−) H.264 SPSパーサは baseline/main プロファイルの幅/高さ計算を実装したが、
  scaling listはbit位置合わせのみ（値は不使用）。progressive/多スキャン等の
  edge caseは対象外。

## References

- 直接の前例（実行手順の正本）: ADR-2607072500（semiconductor/EDA standards
  reverse-domain batch — pdk分離+8新規repo一括scaffoldの同型パターン）
- 命名規約の確立: ADR-2607052300（turn/rt/net/signal reverse-domain rename）
- 分解対象repoの元ADR: ADR-2606272100（kasane設計）、ADR-2606272200（utsushi
  設計）、ADR-2606280010（kasane JPEG DCT decode）
- 大容量バイナリ規律: CLAUDE.md「大容量バイナリの扱い」（本バッチはfixtureが
  数KB級のため対象外、通常git管理）
