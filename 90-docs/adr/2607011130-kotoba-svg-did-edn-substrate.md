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
- `kotoba-lang/html` を Hiccup-compatible EDN HTML renderer として切り出す。
- `kotoba-lang/css` を `kami.css` / shadow-css authoring と互換の EDN CSS renderer として切り出す。

## Consequences

- SVG 前提の変換器や browser/Office pipeline は `svgraph` 系に残せる。
- `kotoba-lang/svg` は他の kotoba substrate から data として合成しやすくなる。
- DID は identity data を EDN document として扱えるため、agent/library 境界で runtime adapter を増やさずに共有できる。
- west manifest は `kotoba-lang/svg` を `0c2776c736bc06010fcaca0fc083d9bd7cd50d7c`、`kotoba-lang/did` を `32865d4725c390ac92dcbae297f37eec383919a6` に pin する。

## W3C substrate candidates

2026-07-01 時点で、SVG と DID 以外に `kotoba-lang` の EDN/CLJ substrate として切り出す優先候補は以下。

| Candidate | Proposed repo | Reason |
| --- | --- | --- |
| JSON-LD 1.1 | `kotoba-lang/json-ld` | DID/VC/Web Annotation/ActivityStreams の共通土台。EDN map から `@context`、compact IRI、node/value/list object を扱う薄い layer が必要。 |
| Verifiable Credentials | `kotoba-lang/vc` | `kotoba-lang/did` の直接の上位 layer。credential/presentation/status/schema を EDN document として扱えるようにする。 |
| ActivityStreams 2.0 | `kotoba-lang/activitystreams` | actor/event/message の語彙 substrate。ActivityPub や internal agent event と合わせやすい。 |
| ActivityPub | `kotoba-lang/activitypub` | ActivityStreams を transport/federation protocol として使う layer。まずは object/inbox/outbox/signature boundary を data として表現する。 |
| Web Annotation | `kotoba-lang/annotation` | document、media、code、office artifact への annotation model。JSON-LD preferred serialization なので EDN 化しやすい。 |

`DID Resolution v0.3`、`VC Confidence Method`、`VC Rendering Methods`、`VCALM` は 2026-07-01 時点では draft/experimental 色が強いため、すぐ独立 repo にせず、`kotoba-lang/did` / `kotoba-lang/vc` 内の namespace または ADR watch 対象に留める。

## Implemented follow-on substrates

`kotoba-lang/json-ld`、`kotoba-lang/vc`、`kotoba-lang/activitystreams`、`kotoba-lang/activitypub`、`kotoba-lang/annotation` を EDN-first CLJ/CLJC substrate として追加する。あわせて、W3C 固有ではないが web substrate の基礎として `kotoba-lang/html` と `kotoba-lang/css` も追加する。

| Repo | Revision | Scope |
| --- | --- | --- |
| `kotoba-lang/json-ld` | `985ce7eb51b3d5fb256ad24bae7ee5311b8d872d` | JSON-LD context/node/value/list/set/graph helper |
| `kotoba-lang/vc` | `61bd3a985ee523e9374524919d2f4b0e6c9370ee` | Verifiable Credential / Presentation EDN document helper |
| `kotoba-lang/activitystreams` | `81abaf05c122914ccfbe7aaee1afe2bf22e3760b` | ActivityStreams object/activity helper |
| `kotoba-lang/activitypub` | `5a8e30573e62d76405c0039261e1121fccb26a2a` | ActivityPub actor/collection/request boundary helper |
| `kotoba-lang/annotation` | `32def0c4f488cb8fd41ded2559340272a8fe04da` | Web Annotation document/target/body helper |
| `kotoba-lang/html` | `122a31e2f6714c1b965b6c0a2d1b9b9e0a3fc8bd` | Hiccup-compatible HTML renderer |
| `kotoba-lang/css` | `43b998d5f95bf4d416a70c157d07d75e59fd071b` | CSS as EDN renderer |
| `kotoba-lang/rdf` | `54303aa098314671cb89b068270d9572ca87ba45` | RDF term/triple/quad/dataset EDN model |
| `kotoba-lang/turtle` | `7ad53620c9ad63c563292db26f39afcc45b60516` | Turtle serializer for RDF EDN triples |
| `kotoba-lang/n-quads` | `c5dfc0399a31370a32570541a9b666be379fa46a` | N-Quads serializer for RDF EDN quads |
| `kotoba-lang/rdf-canon` | `31a8b4b4e5fd89880a6c03c2ef89035df0f90636` | Deterministic RDF canonical string/hash substrate |
| `kotoba-lang/shacl` | `0e502ac5b3405da02a5f7127cd7daa3fa144dd16` | EDN shape validator inspired by SHACL Core |
| `kotoba-lang/ooxml` | `3d50559f010327fab368b04d8f1b19dc74fd4de0` | OOXML OPC package/rels/content-types substrate |
| `kotoba-lang/drawingml` | `3ce239d800e8ed1d25c17b4e3f2783e0eb9ccf6f` | DrawingML XML builder substrate |
| `kotoba-lang/presentationml` | `1eec72b75ea33c99a25bf0922a643a5c79eb4f8e` | PresentationML/PPTX package projection substrate |

Primary references:

- W3C JSON-LD 1.1 Recommendation: https://www.w3.org/TR/json-ld11/
- W3C Verifiable Credentials Data Model v2.1 Recommendation: https://www.w3.org/TR/vc-data-model-2.1/
- W3C ActivityStreams 2.0 Recommendation: https://www.w3.org/TR/activitystreams-core/
- W3C ActivityPub Recommendation: https://www.w3.org/TR/activitypub/
- W3C Web Annotation Data Model Recommendation: https://www.w3.org/TR/annotation-model/
- W3C DID Resolution v0.3 draft: https://www.w3.org/TR/did-resolution/
