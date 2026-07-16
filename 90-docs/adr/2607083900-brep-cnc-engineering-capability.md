---
id: adr-2607083900-brep-cnc-engineering-capability
title: "ADR-2607083900: kotoba-lang/brep に実在するcylinder solid + :revolve、kotoba-lang/cnc に実在する:face-mill + :contourを追加する — process成熟度ではなく工学的能力そのものを向上"
status: accepted
doc_type: adr
topic: kotoba-lang-cad-cam-integration
authoritative: true
last_verified: 2026-07-08
authoritative_for:
  - brep.kernel/make-cylinder・brep.feature :revolve の実装スコープ(軸平行プロファイル・完全2π回転のみ)
  - cylinder-surfaceの高さバグ修正の経緯
  - kotoba.cam.toolpath :face-mill・:contour の実装スコープ(凸多角形プロファイルのみ)
  - fillet/chamfer/boolean/sweep/loft/shell/pattern(brep)・surface-3d/turn(cnc)を今回あえて実装しなかった理由
related:
  - 90-docs/adr/2607083400-vdesign-cad-cam-bridge.md
  - 90-docs/adr/2607083700-vdesign-cad-maturity-scoring.md
  - orgs/kotoba-lang/brep
  - orgs/kotoba-lang/cnc
supersedes: []
superseded_by: []
---

# ADR-2607083900: brep/cnc engineering capability

- Status: accepted (2026-07-08)
- Deciders: Jun Kawasaki

## Decision

ADR-2607083700で「process/governanceの成熟度スコアは100/production-candidateに
達したが、brep/cnc自体の工学的能力は変わっていない」と明記した後、オーナーから
「工学的能力の成熟度を向上」の指示を受け、`kotoba-lang/brep`と`kotoba-lang/cnc`
本体のカーネル実装を拡張した。

### brep（BREPカーネル）

1. **`brep.kernel/make-cylinder`**: 実在する円柱ソリッド生成。上下N角形cap面
   （plane-surface）+ 側面1面（cylinder-surfaceに実`:height`を持たせる）。
   解析値（体積πr²h、表面積2πr²+2πrh）と誤差2%以内で一致することを検証。
2. **バグ修正**: `cylinder-surface`が高さを持たず、`brep.tessellate`が常に
   `brep.config/cylinder-default-height`というグローバル定数を使っていたため、
   どんなcylinder-surfaceも「常に高さ1.0」で誤ってtessellateされていた。
   `cylinder-surface`に任意の`:height`を持たせ、指定があればそれを使うよう修正。
3. **`brep.feature/evaluate`の`:revolve`対応**: 単一の軸平行sketch-line
   プロファイルを完全2π回転させるケースのみ実装（make-cylinderを呼ぶ）。
   角度付きプロファイル（円錐/frustum、cone tessellationが未実装のため）や
   部分角度revolve（パイスライス）は`:error`を返す（誤った形状を黙って
   生成しない）。

### cnc（CAM/toolpathエンジン）

1. **`:face-mill`**: stockの上面全体をラスタースキャンする単層フェイシング。
   `(:stock job)`から領域を自動導出（`:pocket`と違い明示的な範囲指定不要 —
   フェイシングは定義上ワークピース全体を対象とするため）。
2. **`:contour`**: 呼び出し側が渡す凸多角形プロファイル（`:profile`、CCW）を
   工具半径オフセットして追従する実toolpath。`offset-convex-polygon`は
   各辺を外向き法線方向にオフセットし、隣接する2つのオフセット辺の交点を
   新しい頂点とする標準アルゴリズム。凹多角形/非CCWプロファイルは
   `convex-ccw?`で検出し、offsetを試みず既存のplaceholder rapidへ
   フォールバック（自己交差する誤ったtoolpathを黙って生成しない）。

## 誠実なスコープ（あえて実装しなかったもの）

- **boolean（add/cut/intersect）**: 一般的なポリゴン/多面体クリッピングは
  頑健性の要求が高く、雑に実装すると「間違ったジオメトリを黙って返す」
  リスクの方が「未実装と明記する」より悪いと判断し見送った。
- **fillet/chamfer/sweep/loft/shell/pattern（brep）**: いずれも既存の
  単一`result`ソリッド蓄積モデルでは表現しきれない（複数ソリッド・
  エッジトポロジー編集を要する）拡張で、今回のスコープには含めなかった。
- **`:surface-3d`/`:turn`（cnc）**: 3Dメッシュの高さフィールド追従、および
  2軸極座標旋盤という、raster facingやポリゴンoffsetとは全く異なる
  アルゴリズムパラダイムを要するため見送った。
- **revolveの角度付きプロファイル（円錐/frustum）**: `brep.tessellate`に
  cone面のtessellationロジックが存在しないため、正しくレンダリングできる
  保証がない状態での実装は避けた。
- **contourの凹多角形**: 自己交差解決を要する一般ポリゴンオフセットは
  今回のスコープ外。

## Consequences

- `kami-engine-vehicle-designer`のdeps.ednをbrep/cnc新pinへ更新
  （既存のパッケージングenvelope/pocket・drillベースの利用コードは
  変更なし、regressionなしを確認）。
- brep: 22→30テスト(88→386アサーション)、cnc: 7→11テスト(57→90アサーション)、
  いずれもclj-kondo clean。
- **process/governance成熟度スコア（ADR-2607083700）とは独立**: このADRの
  変更はkotoba.cad.coreのscore/coverage計算に直接影響しない
  （score/artifacts/approvals入力は変わらないため）。工学的能力の向上は
  別軸の指標であり、混同しないこと。

## 却下案

- **全機能を一気に実装**: booleanやsurface-3dのような、正しさの検証が
  極めて難しい機能を急いで実装すると「動くが間違っている」リスクが
  「未実装」より有害。段階的に、検証可能な範囲だけを実装する方針とした。
