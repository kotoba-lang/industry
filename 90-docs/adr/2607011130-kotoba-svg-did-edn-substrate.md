# ADR-2607011130: `kotoba-lang/svg` と `kotoba-lang/did` を EDN substrate として扱う

Status: Accepted
Date: 2026-07-01

## Context

`kotoba-lang/svg` は、旧 `drawingml-svg`/`svgraph` 系の SVG 前提ツール移管先として扱われていた。しかし `kotoba-lang` の言語基盤では、`hicuupus` や `shadowcss` と同じように、SVG も文字列や browser pipeline ではなく EDN data と CLJ/CLJC 関数で扱いたい。

また DID も kotoba の identity substrate として、外部 runtime 依存の adapter ではなく EDN first の小さな CLJ/CLJC library として切り出す必要がある。

## Decision

- `kotoba-lang/svg` は EDN/Hiccup-like SVG DSL として再整理する。
- SVG node は `[:tag {:attrs ...} child ...]` を基本形にし、`render` で SVG/XML 文字列へ落とす。
- CSS は shadow-css 型に EDN map/vector から宣言文字列へ変換する。
- 旧 `drawingml-svg` の path override は外し、`kotoba-lang/svg` を旧 repo の単純な移管先として扱わない。
- `kotoba-lang/did` を追加し、DID/DID URL parse、DID document EDN、`did:key` Ed25519、`did:web` helper を CLJ/CLJC で提供する。

## Consequences

- SVG 前提の変換器や browser/Office pipeline は `svgraph` 系に残せる。
- `kotoba-lang/svg` は他の kotoba substrate から data として合成しやすくなる。
- DID は identity data を EDN document として扱えるため、agent/library 境界で runtime adapter を増やさずに共有できる。
- west manifest は `kotoba-lang/svg` を `0c2776c736bc06010fcaca0fc083d9bd7cd50d7c`、`kotoba-lang/did` を `32865d4725c390ac92dcbae297f37eec383919a6` に pin する。
