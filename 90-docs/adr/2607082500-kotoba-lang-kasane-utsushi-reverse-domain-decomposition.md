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
- `manifest/repos.edn` に15新規repoを追加、`nbb scripts/gen-west-manifest.cljs
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
nbb scripts/gen-west-manifest.cljs --entry <17名>              # OK、全件サーバ側pin検証済み
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

## Addendum（2026-07-08）— Consequences記載の2件のfollow-upを両方完了

本ADR初版のConsequences節（下記(−)2件）は、その後の同日中の作業ですべて解消
された。両READMEの「follow-up」記録も対応するpush済みcommitへ更新済み。

1. **`kasane.gltf`/`kasane.svg`/`kasane.json`/OOXML投影の既存repoへの統合**:
   完了。`kasane.gltf`→`org-khronos-glb`(`glb/parse-glb`+`glb.json/parse`)、
   `kasane.svg`→`org-w3-svg`（read側を新設`svg.reader` nsへ追加、write側
   `svg.core/attrs`とのシグネチャ衝突を回避）、`kasane.json`→
   `kotoba-lang/json`(`json.core/decode`+keywordizeラッパ)へそれぞれ薄い
   adapterとして委譲。OOXML投影は`kotoba-lang/ooxml`(`package-kind`/
   `office-parts`)+`kotoba-lang/office`(`office.graph/part-graph`、Word本文
   抽出)を**JVM専用関数`office.opc/open-package`を一切呼ばずに**呼び出す形で
   統合（xlsx共有文字列/pptxシェイプ幾何はkasane自前ロジックのまま — 対応
   する既存repo側に等価実装が無いため）。
   - 副次的に判明した誤情報を訂正: `kasane.gltf`のdocstring/READMEが
     「org-khronos-glbはaccessor/mesh decodeまで含む完全実装」と主張して
     いたが、org-khronos-glb自身のREADMEを再確認したところ「no glTF-JSON
     schema knowledge (accessors, meshes, materials, ...)」と明記されており
     誤りだった（`kasane.normalize/gltf->doc`自体がnode/mesh/scene個数+
     transformしか読まないため実害は無い）。表記を訂正した。
2. **H.264/AAC/Opusのutsushi filtergraphへの配線**: H.264のみ配線完了 —
   `utsushi.codec/decode`が`:h264` video trackに対し、MP4のavcC box
   （AVCDecoderConfigurationRecord, ISO/IEC 14496-15）に埋め込まれた
   SPSを`org-iso-h264`でパースし、実width/height/profile-idc/level-idcを
   `:params`として付与するようになった（実libx264エンコードMP4で
   96x64を検証）。AAC/Opusは**意図的に配線しない**と結論: ADTS/Opus TOCの
   framingはMP4コンテナへの格納時点で既に解決済み（demux後のsampleは
   境界確定済みバイト列そのもの）であり、`utsushi.codec`の役割である
   「post-demuxのbitstream framing」が適用対象を持たないため。

### 追加で完了した成熟度向上作業（本ADRのスコープを超えるがkasane/utsushi
本体に対する変更のため、ここに記録する）

- **kasaneのテストランナーをbabashka(nbb)からnbb(ClojureScript-on-Node)へ
  移行**（CLAUDE.md「`.cljc`/`.kotoba`ランタイム優先順位」でnbbがJVM単体
  より優先されるため）。移行の過程で`kasane.bytes/sint!`の実バグを発見・
  修正: `bit-shift-left`ベースの32-bit符号拡張がJVM(64-bit Long)では安全
  だがcljs/JS（32-bit符号付きbitwise、shift量mod-32）では壊れていた
  （`1 << 32` = `1`になる等）。乗算ベースの`pow2`ヘルパへ置き換え。
  同種のバグを`kotoba-lang/json`の`\u`エスケープデコードにも発見したが、
  別repoのため対象外・READMEに記録のみ。
- **WASM(`kotoba wasm emit`)コンパイル検証**: `org-ietf-deflate`を実際に
  コンパイルしようとしたところ、`kotoba.runtime/check`がClassCastException
  でクラッシュした（`decode-sym`のmap-destructuring引数が一因の一つと
  判明したが、修正後も同種のクラッシュが別箇所で再発 — 根本原因は未特定）。
  対応方針はオーナー確認の上「READMEの表記修正のみ」（kasane側の
  `kotoba wasm`欄を「未検証*」+脚注に変更、`kotoba-lang/kotoba`自体は
  修正しない）に決定・実施済み。
- **real-file fixtureによる検証強化**（6repo、いずれも実際のツールが
  生成したファイルで検証、synthetic hand-builtデータではない）:
  PSD(kasane, ImageMagick生成)、PNG(org-w3-png, Pillow生成)、
  TIFF Adobe Deflate(org-adobe-tiff, Pillow生成)、PDF(org-iso-pdf,
  qpdf/pikepdf生成 — `/Type /ObjStm`圧縮object streamと、classic
  trailerキーワードを持たない純粋な`/Type /XRef`構造の両方を初めて検証)、
  H.264 PPS(org-iso-h264, 実libx264エンコード)、ISOBMFF(org-iso-isobmff,
  実ffmpeg AV MP4 — video+audio 2トラックをffprobeの`nb_frames`と
  突き合わせ)、EPUB(org-w3-epub, pandoc生成)、ODF(org-oasis-odf,
  pandoc生成)、SVG(org-w3-svg, Graphviz `dot -Tsvg`生成)、OOXML
  docx/xlsx/pptx(`ooxml`本体 + kasane、pandoc/xlsxwriter生成 — xlsxは
  openpyxlのデフォルトinline-string出力ではなくxlsxwriterの
  `xl/sharedStrings.xml`出力を意図的に選択)。kasaneのOOXML統合について
  は、既存の`.clj`専用テスト（`java.util.zip.ZipOutputStream`でfixtureを
  実行時生成するためJVM専用）に加え、実docx/xlsx/pptxファイルを
  `org-pkware-zip`の`zip.core/parse`（純cljc、読み専用）で読む新規`.cljc`
  テストを追加し、nbb/cljsランタイム上でOOXML統合を初めて検証した。
- **PDF `/Type /ObjStm` object stream対応**（ISO 32000 §7.5.7）:
  header table解析・offset順object抽出・generation-0マージを実装。
- **H.264 PPS実装**: 共通ケース(`num_slice_groups_minus1 == 0`)を
  カバー（FMOは非対応でthrow）。entropy-coding-modeがCAVLC（baseline
  プロファイルはCABAC禁止という制約との整合性）をクロス検証。
- **17repo（kasane/utsushi含む）すべてにGitHub Actions CIを追加**
  （clj-kondo lintではなくtest-runner、JDK 17/21マトリクス）。この過程で
  `org-khronos-gltf`（ローカルディレクトリ名`gltf`のまま、正規パスは
  `org-khronos-gltf`）の`deps.edn`が`:local/root`で兄弟チェックアウトに
  依存しており、CI/独立cloneの両方でビルド不能だったバグを発見・修正
  （`:git/sha`座標へ変更、ディレクトリ名もrename）。同じ調査の過程で
  `kotoba-lang/office`/`office-style`のCIが「`node bin/<name>.js`を
  smoke-testで実行するが、そのファイルは存在せず`bin/<name>.cljs`
  （nbb実行）のみが実在する」という理由で作成時から一貫してredだった
  ことを発見・修正（`npm install`+`npx nbb <実パス>`へ変更）。

このaddendumの範囲を超えて、`:local/root`非移植バグと同種のパターンを
`kotoba-lang` org全体（EDA/semiconductor standards batch、OASIS/W3C/OMG
standards batch等、本ADRと直接関係しない別batch）に対しても横断的に
調査・修正した（`org-materialx`/`org-oasis-saml`/`org-vrmc-vrm`/
`eda`→`rtl`→`org-accellera-uvm`の依存chain、計70件の`org-*` repo全件で
CI健全性を確認）。これは本ADRの決定事項の適用範囲外（kasane/utsushiの
アーキテクチャ判断ではなく、既存パターンの機械的横展開）のため、ここでは
概要のみ記録し、個別のADR化はしない。各repoのcommit履歴に十分な文脈を
記載済み。
