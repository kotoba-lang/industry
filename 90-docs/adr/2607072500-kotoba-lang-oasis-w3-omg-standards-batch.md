---
id: adr-2607072500-kotoba-lang-oasis-w3-omg-standards-batch
title: "ADR-2607072500: kotoba-lang 標準規格 EDN ライブラリ一括新設（SBML / OWL 2 / UML / SysML v2）+ org-w3-rdf の RDF 1.1 準拠拡張"
status: accepted
doc_type: adr
topic: kotoba-lang-oasis-w3-omg-standards-batch
authoritative: true
last_verified: 2026-07-07
authoritative_for:
  - "kotoba-lang における SBML（Systems Biology Markup Language）・OWL 2（Web Ontology Language）・UML（Classes package のみ）・SysML v2 kernel の EDN 表現は、それぞれ `kotoba-lang/org-sbml` / `kotoba-lang/org-w3-owl2` / `kotoba-lang/org-omg-uml` / `kotoba-lang/org-omg-sysmlv2` を正本とする"
  - "既存 `kotoba-lang/org-w3-rdf` は RDF 1.1 Concepts and Abstract Syntax（W3C REC 2014-02-25）に対して構造検証・Dataset（sec 4）モデリング・literal の language/datatype 制約（sec 3.3）を備えるよう拡張済みで、`org-w3-rdf11` という別名の重複 repo は作らない（既存 org-<body>-<spec> 命名規約はバージョン接尾辞を付けない）"
  - "UML は v1 では Classes package（構造/クラス図メタモデル）のみを対象とし、StateMachine を含む Behavior 系13種は恒久的に対象外（StateMachine は既存 `kotoba-lang/statechart` が担当）とする"
  - "SysML v2 は EDN builder のみを提供し、テキスト具象構文（`.sysml` ファイル）のパーサは実装しない（仕様がまだ確定・進化中のため）"
  - "OWL 2 は完全な description logic 推論器を実装せず、非推論的（構造的）な subclass/property の閉包計算のみを提供する"
related:
  - 90-docs/adr/2607072350-kotoba-lang-org-oasis-open-xmile-system-dynamics.md
supersedes: []
superseded_by: []
---

# ADR-2607072500: kotoba-lang 標準規格 EDN ライブラリ一括新設（SBML / OWL 2 / UML / SysML v2）+ org-w3-rdf の RDF 1.1 準拠拡張

**Status**: accepted
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki（ユーザー指示: 「https://www.omg.org/sysml/sysmlv2/ も kotoba-lang/org-omg-sysmlv2、kotoba-lang/org-sbml、org-w3-rdf11、org-w3-owl2、org-omg-uml も lib を設計実装」「do it」）

## Context

前段の ADR-2607072350（`kotoba-lang/org-oasis-open-xmile`）で確立した「国際標準規格を EDN + `.cljc` capability library として実装する」パターンを、ユーザー指示により以下5件へ横展開した:

- `org-omg-sysmlv2`（OMG SysML v2）
- `org-sbml`（SBML Level 3、systems biology）
- `org-w3-rdf11`（ユーザー指定名）— ただし調査の結果 `kotoba-lang/org-w3-rdf` が既存（`rdf` からの改名、org-<body>-<spec> 規約に準拠）であり、この規約はバージョン接尾辞を付けない（`org-w3-svg2` や `org-ietf-cbor-8949` が存在しないのと同様）。「RDF」と言えば現行の唯一の W3C Recommendation である RDF 1.1 を指すため、別名の重複 repo を作る代わりに **既存 `org-w3-rdf` を RDF 1.1 準拠に拡張** した（ユーザーに確認済み、本ADRで正式化）。
- `org-w3-owl2`（W3C OWL 2）
- `org-omg-uml`（OMG UML）

5件とも並列の背景 agent に委譲し、各 agent は個別に spec を調査（該当する場合は WebFetch で一次資料を確認）・設計・実装・テスト・lint・GitHub push まで行い、本体（superproject）への ADR/manifest 反映はしないよう指示した（共有 manifest への同時書き込み競合を避けるため、本 ADR で一括して反映する）。

## Decision

### 新設4リポジトリ

1. **`kotoba-lang/org-sbml`** — SBML Level 3 Core。`sbml.model`（compartment/species/reaction/parameter/rule/unit-definition の EDN スキーマ）、`sbml.mathml`（Content MathML の `<math>` 要素木 <-> EDN式木 + 純粋評価器。XMILEの `xmile.expr`+`xmile.xml` を合わせた役割）、`sbml.xml`（math以外の構造の XML要素木<->EDN変換）、`sbml.validate`（SId名前空間の重複検出・kineticLaw の種参照スコープ制約 sec 4.11.5 等）、`sbml.execute`（Euler/RK4、reaction量論+kineticLaw によるODE、AssignmentRule/RateRule対応）。一次反応 A→B の質量作用則（解析解と比較、RK4相対誤差~1e-8）で検証済み。66 tests/259 assertions all green、lint 0/0。
2. **`kotoba-lang/org-w3-owl2`** — OWL 2 Structural Specification and Functional-Style Syntax（W3C REC 第2版 2012-12-11）。`owl.model`（entity/class-expression/axiom の EDN、sec 5-10 に準拠）、`owl.functional`（Functional-Style構文と1:1対応するEDN式木との相互変換。実際のテキスト構文パーサ自体は範囲外）、`owl.validate`（ぶら下がり参照・矛盾する DisjointClasses+ClassAssertion 等の構造検証）、`owl.reason`（**完全なDL推論器ではない** — SubClassOf推移閉包・TransitiveObjectProperty閉包・Symmetric/Inverse事実具体化のみの、決定可能な部分集合限定の閉包計算）。27 tests/151 assertions all green、lint 0/0、CI green。
3. **`kotoba-lang/org-omg-uml`** — OMG UML 2.5.1（formal/17-12-05）の **Classes package のみ**（Class/Property/Operation/Interface/Association/Generalization/Package/Enumeration/DataType/Dependency）。`uml.model`（EDN + builder + `all-supertypes` 等の推移閉包クエリ）、`uml.xml`（実務的なXMI部分集合、xmi:id ではなく classifier 名で参照解決）、`uml.validate`（ぶら下がり型参照・Generalizationサイクル検出・重複メンバー名・Interface属性の警告）。StateMachine を含む13の Behavior 系ダイアグラムは恒久的に対象外（`kotoba-lang/statechart` が別途担当）。41 tests/85 assertions all green、lint 0/0。
4. **`kotoba-lang/org-omg-sysmlv2`** — OMG SysML v2 kernel。`sysml.kerml`（KerML の Type/Classifier/Feature/Specialization/Featuring/Multiplicity）、`sysml.model`（SysML の Part/Attribute/Port/Item/Action/Connection/Interface/Requirement の Definition/Usage 双対 + Package）、`sysml.validate`（定義参照・Specializationサイクル・多重度・接続端の検証）。**テキスト具象構文（`.sysml`）のパーサは実装せず、EDN builder のみ**（仕様自体がまだ進化中のため）。一次資料は OMG の formal spec（KerML 1.0=formal/26-03-01、SysML 2.0=formal/26-03-02、2025-07-21採択）に加え、構造詳細は SysML-v2-Pilot-Implementation の Ecore メタモデル（spec準拠の一次実装、brief で許容された根拠）を参照。26 tests/77 assertions all green、lint 0/0。

### 既存リポジトリの拡張

5. **`kotoba-lang/org-w3-rdf`** — RDF 1.1 Concepts and Abstract Syntax（W3C REC 2014-02-25）に対する準拠を拡張。`rdf.core/literal` を修正（sec 3.3 の「language タグは datatype が `rdf:langString` の場合のみ」制約を常に満たすようデフォルト付与、RDF 1.0→1.1 の「plain literal」意味変更に伴う実質的なバグ修正）。新設 `rdf.validate`（`kotoba.dsl.problem` 規約、`kotoba-lang/dsl-core` に新規依存）、新設 `rdf.dataset`（sec 4 の Dataset ADT — default graph + named graphs、graph は真の集合）。16 tests/47 assertions all green（既存の plain `.clj` clojure.test 規約を踏襲、リネームはしない）。

## Consequences

- `manifest/repos.edn` の `:extra-projects` に4新規 path を登録 + `nbb scripts/gen-west-manifest.cljs --entry org-sbml,org-w3-owl2,org-omg-uml,org-omg-sysmlv2,org-w3-rdf` で5 entry（新規4 + org-w3-rdf pin前進1）を一括の最小 diff で反映。
- v2 スコープアウト項目（各リポジトリの README Follow-ups に明記）: SBML の空間/multi/comp/qual拡張パッケージ・完全な次元解析・algebraic rule の DAE 解法。OWL 2 の完全DL推論・RDFマッピング・プロファイル検証・SWRL。UML の13 Behavior ダイアグラム・XMI完全準拠。SysML v2 のテキスト具象構文・Action実行セマンティクス・要件検証エンジン・制約ソルバー・Views。いずれも「サイレントに間違った値を返す」のではなく、parse/round-tripはできても evaluate/execute 相当の操作は明確に throw する設計（org-oasis-open-xmile と同じ documented honesty 方針）。
- 5リポジトリとも `kotoba-lang/dsl-core` に `:git/sha` 依存（`kotoba.dsl.problem` 検証結果規約の再利用）。

## Verification

- 4新規リポジトリすべて `clojure -M:test` all green（sbml: 66/259, owl2: 27/151, uml: 41/85, sysmlv2: 26/77）+ `clojure -M:lint` errors 0/warnings 0（CI で JDK17/21 マトリクスも green を確認したものを含む）。
- `org-w3-rdf` は 16 tests/47 assertions all green、`ee174a6`→`c3db77a` へ fast-forward push 済み。
- `gh repo create kotoba-lang/{org-sbml,org-w3-owl2,org-omg-uml,org-omg-sysmlv2} --public` + push 済み。
- `manifest/repos.edn` への登録 + `nbb scripts/gen-west-manifest.cljs --entry <5件>` で最小 diff 生成、pin はサーバ側検証（`verify-west-pins.cljs`）で5件全て OK（新規4件は「main から到達可能」、org-w3-rdf は fast-forward）。
