---
id: adr-2607086500-pcb-lee-routing
title: "ADR-2607086500: kotoba-lang/pcb に Lee's algorithm ベースの実配線(maze autorouting)を実装 — route-trace の「データを記録するだけ」から実際のパス探索へ"
status: accepted
doc_type: adr
topic: kotoba-lang-cad-cam-integration
authoritative: true
last_verified: 2026-07-08
authoritative_for:
  - pcb.route の実装スコープ（Lee's algorithm、MST、単層のみ）
  - obstacle-avoidance テストが vacuous にならないための根拠（pad :size を無視するバグの経緯）
related:
  - 90-docs/adr/2607083900-brep-cnc-engineering-capability.md
  - orgs/kotoba-lang/pcb
supersedes: []
superseded_by: []
---

# ADR-2607086500: pcb.route — Lee's algorithm maze autorouting

- Status: accepted (2026-07-08)
- Deciders: Jun Kawasaki

## Decision

`kotoba-lang/pcb` に新規ネームスペース `pcb.route` を追加し、
`pcb.layout/route-trace`（点列を渡された通りに記録するだけの純粋データ
コンストラクタで、実際の経路探索は一切行っていなかった）とは別に、
実際にパスを探索して配線する `route-net`/`route-all` を実装した。

- **Lee's algorithm**（C. Y. Lee, "An Algorithm for Path Connections and
  Its Applications", IRE Transactions on Electronic Computers, 1961）
  — グリッド上のBFS波面伝播で、障害物を避けた最短4方向接続パスを
  source cellから探索し、target到達後にtrace-backする。全てのPCB
  autorouterが歴史的にここから始まった基礎アルゴリズム。
- **多ピンnet**はManhattan距離のMST（Prim's algorithm）でpadを結び、
  MSTの各辺を個別にmaze-routeする（真のrectilinear Steiner treeでは
  ない、標準的な近似）。
- **障害物**は自ネット以外のpad（中心点だけでなく`:size`由来の実際の
  物理的な広がり＋DRCクリアランス）、既存trace/via、基板外周1セル。

## Context

`kotoba-lang/pcb`はkami-eda（削除済みRust crate）からの復元repoで、
schematic/layout/netlist/ercの4モジュールは全て移植済みだったが、
配線（trace routing）は`route-trace`という「渡された点列をそのまま
`:traces`に積むだけ」の関数しかなく、経路探索アルゴリズムが存在しな
かった。EDAツールとして「配線」を名乗るには、この欠落を埋める必要が
あった。

## 誠実さの確認: obstacle-avoidance テストが最初vacuousだった

実装の初期ドラフトで`build-obstacle-set`が、pad周りの障害物セルを
**DRCクリアランスのみ**で計算し、pad自身の`:size`（物理的な広がり）
を完全に無視していた。これにより、6×10mmの意図的に大きい「壁」padを
配置しても、実際にブロックされる範囲はクリアランス分の約0.225mm半径
のみとなり、ルーターは壁の存在を無視して直線的な経路をそのまま返す
——**テストは通るが、意図した障害物回避を一切検証していない**状態
だった。

発見の経緯: 障害物ありのテストと無しのテストで**経路が完全に同一**
になるという不審な結果からバグを疑い、`(> pathlen 16.5)`という
detour判定を追加したところ壁を大きくしても`false`のままだったことで
`:size`無視のバグと特定した。

修正: `footprint-pad-positions`の返り値に`pad-radius-mm`（`:size`から
求める外接円半径）を追加し、`build-obstacle-set`のpad障害物計算を
`(+ radius-mm clearance)`に変更。再検証の結果、意図的な壁（board
20×20mm、endpoint間Manhattan最短16.0mm）を置いた場合に実際に28.5mmへ
迂回する経路が生成され、DRC違反ゼロを確認した——この数値（16.0mm→
28.5mm）は`test/pcb_route_test.cljc`の
`route-net-obstacle-forces-genuine-detour`にハードコードされている。

## 正直なスコープ（実装していないこと）

- **単層のみ**。box化されたnetをviaで別層へ逃がすフォールバックは
  無く、`route-all`は単に`:failed`に積んで次のnetへ進む。
- **グリッド量子化・4方向接続のみ**（既定0.25mmピッチ）。45°/円弧の
  コーナースタイルは後処理として存在しない。
- **逐次ルーティング**（rip-up-and-reroute無し）。net Nのtraceがnet
  N+1の障害物になるため、ルーティング順序が成否を左右しうる。
- **pad形状は外接円近似**。round padには正確、rect/oblong padには
  安全側（やや保守的）な過大評価。

## Consequences

- `pcb.layout/pad`に任意の`:net-id`フィールドを追加（後方互換、既定
  `nil`）——`route-net`がpadをnetに紐付けて発見するために必要。
- 11テスト・33アサーション追加（repo合計23テスト・61アサーション、
  失敗ゼロ）。obstacle-detourの定量的証明（16.0mm→28.5mm）と
  boxed-in-net失敗ケース（`:unrouted`、layout不変）を含む。
- clj-kondo clean（0 errors/warnings）。マージ直前に別セッションが
  `the-ns`→`find-ns`のcljs互換性修正＋`:lint` aliasを追加していたため、
  worktreeでmainを再取得した上で新規テストの`the-ns`も同じ修正を適用
  してからマージした。
- `manifest/west.yml`のpcb pinをGitHub API single-entry commitで
  前進（`55eaba1`→`629ed55`）。依存する他repoは無し（`deps.edn`で
  `kotoba-lang/pcb`を参照するrepoはゼロと確認済み）。

## 却下案

- **`clojure.lang.PersistentQueue`でBFSキューを実装**: JVM専用で
  cljsに存在しないため、既存repoの`.cljc`ポータビリティ方針に反する。
  代わりにplainベクタ＋frontインデックスのFIFO（`nth`によるO(1)
  アクセスなので、O(n²) shiftにはならない）を使用。
- **真のrectilinear Steiner tree**: MSTより最適だが実装が複雑で、
  今回のスコープでは「多ピンnetを一応結線できる」ことが目的のため
  MST近似を採用（README/docstringで明記、隠さない）。
