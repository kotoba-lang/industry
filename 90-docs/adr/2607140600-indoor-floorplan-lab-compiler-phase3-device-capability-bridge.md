# ADR-2607140600: 建物内部構造（floorplan）推定lab のための kotoba-lang/compiler Phase 3a capability bridge（motion/audio-io/ble-scan/wifi-info）設計 + kotoba-lang/device 統合提案

**Status**: proposed（設計提案としては未承認のまま——ただしオーナー指示により
Phase 3a ソフトウェア層 + 実機iOS shim + 3D可視化の実装は完了・実機検証中。
下記「Addendum」参照）
**Date**: 2026-07-13
**Deciders**: 未確定（提案者: Claude、オーナー承認待ち）
**Related**:
- `orgs/kotoba-lang/compiler` `docs/adr/0001-worldwide-95-percent-platform-coverage.md`
  （Phase 3 capability bridge ロードマップ——本ADRが着手する未実装TODOの出所）
- `orgs/kotoba-lang/device`（bluetooth/wifi/display/geolocation/camera の
  capability token 抽象化層、host-injected driver パターン）
- ADR-2607100030（kami:engine host imports — capability 登録パイプライン
  contracts→lang→runtime の precedent）
- ADR-2607030900（aiueos 特権device-access design — 特権昇格範囲の最小化・
  薄いnative shimに閉じ込めるパターンの precedent）
- CLAUDE.md runtime優先順位（kotoba wasm > clojurewasm > cljs > nbb、
  app runtimeとして新規Rust/native実装を安易に書かない原則）

## Context

2026-07-13、ユーザーから「iPhoneのセンサー・WiFi・Bluetooth・スピーカー・
マイクを使って建物内部構造を分析するlab」の有無を問われ調査した結果、
このmonorepoには該当するlabが存在しないことを確認した。近い資産として
`kotoba-lang/device`（capability抽象化層、driver未実装）と
`kotoba-lang/compiler`（安全なマルチターゲット `.kotoba` コンパイラ）を
発見した。

`kotoba-lang/compiler` を追加調査した結果:

- `.kotoba` は純粋な整数演算・`let`/`if`/算術/比較・不変pair/listのみの
  極小言語で、FFIもOS API呼び出しも一切ない deny-by-default 設計。
- `aarch64-ios` ターゲットは存在するが、これは検証済み機械語を Mach-O の
  `__TEXT,__text` に直接埋め込む static AOT packaging であり、
  CoreMotion/CoreBluetooth/AVAudioEngine等のネイティブAPIを呼ぶ codegen
  ではない。
- センサー/カメラ向け capability bridge は `docs/adr/0001` の Phase 3
  ロードマップに「Define capability bridges for lifecycle, network,
  storage, sensors, camera, and notifications. Every bridge is
  deny-by-default and independently metered.」と明記されているが、
  現時点で **完全に未着手**（コード上に該当実装ゼロ）。

一方 iOS の制約として、WiFi の生RSSIスキャンおよび Web Bluetooth は
iOS Safari を含む Web API からは取得不可能（App Store配布アプリでも
特別entitlementなしには生スキャンできない）。したがって「建物内部構造を
実機センサーで分析する」には、何らかの形でネイティブ相当のAPI境界を
通す必要があり、CLAUDE.md の runtime優先順位（app runtime として新規
Rust/native実装を安易に書かない）との整合が課題になる。

ユーザーは 2026-07-13、実現方針として「p3（kotoba-lang/compiler の
Phase 3 capability bridge）+ device（kotoba-lang/device との統合）」を
明示的に選択した。

## Decision（提案）

Phase 3 の汎用範囲（lifecycle/network/storage/sensors/camera/
notifications 全て）を一気に実装するのではなく、本labが必要とする
**4種類の capability に絞った「Phase 3a」** として定義する。

### 1. capability の最小定義（read-only・能動操作なし）

| capability | 内容 | 対応する実機API |
|---|---|---|
| `motion` | 加速度計/ジャイロ/磁力計のサンプルストリーム取得（固定レート・固定小数点） | CoreMotion `CMDeviceMotion` |
| `audio-io` | 指定周波数パターンの再生 + 同時録音バッファ取得（固定小数点PCM） | `AVAudioEngine` |
| `ble-scan` | 近傍BLEペリフェラルのRSSI読み取りのみ（ペアリング/GATT書き込み等は含まない） | `CoreBluetooth` セントラルスキャン |
| `wifi-info` | 接続中ネットワークの信号強度等、iOSが位置情報許可下で公開する範囲のみ（生スキャン不可という制約を機能に織り込む） | `NEHotspotNetwork` 等 |

いずれも ADR-2607100030 が確立した capability 登録パイプライン
（kotoba-core-contracts でcapability id + import descriptor 登録 →
kotoba-lang の `effect-for-kind` → kotoba runtime の `op->kind` /
`guarded-host-functions`）と同型で追加し、"read-only・能動操作なし・
network egressなし・個別にmetered/gate可能" という deny-by-default
哲学をそのまま継承する。

### 2. 実機アクセスは薄い native shim に閉じ込める（ADR-2607030900踏襲）

ADR-2607030900 の「特権昇格の範囲を最小化し、判定ロジックは非特権層に
残す」パターンを流用する:

- 判定ロジック（capabilityが許可されているか、どのレートでサンプルを
  返すか等）は `.kotoba`/CLJC 側にとどめる。
- 実際に CoreMotion/CoreBluetooth/AVAudioEngine を呼ぶのは、**判断を
  一切行わない薄い Swift native shim** のみ。shim は「命令された通りに
  OS APIを叩いて固定フォーマットで結果を返すだけ」の役割に限定する
  （ADR-2607030900 の `aiueos-device-provider` と同じ設計思想）。
- この native shim は `kotoba-lang/compiler` が自動生成するものではなく、
  capability bridge の「向こう側」に人間または別途承認を得たagentが
  実装する最小限のグルーコードとして明示的に切り出す——deny-by-default
  の精神から、この最終境界だけは本ADRの自動化提案に含めない。

### 3. kotoba-lang/device への統合

上記4 capabilityを `kotoba-lang/device` の capability token 語彙に追加
登録し、host-injected driver として `kotoba-lang/compiler` が生成する
iOSターゲット向け native shim を接続する。これにより `device` の既存
capability抽象化層はそのまま活かし、これまで空白だった driver 実装を
埋める形になる。

### 4. lab 本体（後続タスク、本ADRのスコープ外）

新規repo（案: `kotoba-lang/floorplan-lab`）で、上記4 capabilityを使い:

- `motion` ストリームで歩行軌跡（dead-reckoning）を推定
- `audio-io` capabilityで chirp音を発し、echo到達時間から壁までの距離を
  推定する音響sonar
- `ble-scan` capabilityで自前設置ビーコン等のRSSIから位置を補正

これらをセンサーフュージョンし、部屋の輪郭（floorplan）を推定・可視化
する。UIは `kotoba-ui`/`shitsuke.hig` スタックのPWAとし、native shimは
WKWebView等に埋め込む。**repoのscaffold・実装は本ADR承認後の別タスク**
とする。

## Scope of this ADR

本ADRは Phase 3a capability bridge の**設計方針の提案のみ**。
`kotoba-lang/compiler` へのコード変更、native shim実装、lab repoの
scaffoldは本ADR承認後に別コミットで着手する。

## Alternatives considered

- **純PWAのみ（WiFi/BT無し、motion+audio-ioだけ）**: 既存のcljs/
  kotoba-uiスタックのみで最速に実現できるが、「WiFi/Bluetoothによる
  測距」という要求機能を満たせない。ユーザーが明示的にp3+deviceを
  選んだため不採用。
- **完全ネイティブSwiftアプリ（compiler経由なし）**: 開発速度は最速だが、
  CLAUDE.mdのruntime優先順位ポリシー（app runtimeとして新規Rust/native
  実装を安易に書かない）に反するため不採用。
- **kotoba-lang/compilerのフルPhase3を一気に実装**: lifecycle/network/
  storage/sensors/camera/notifications全てを含む汎用capability bridge
  基盤は巨大すぎ、本labが必要とする4 capabilityに絞ったPhase 3aの方が
  着手可能。将来Phase3残りを埋める土台にもなる。

## Consequences

- (+) CLAUDE.mdのruntime優先順位を守りながら、iOS実機センサー
  （モーション/音響/BLE）にアクセスする正式な経路ができる。
- (+) `kotoba-lang/device` の既存capability抽象化層をそのまま活かせる
  （driverの空白を埋めるだけ）。
- (+) `kotoba-lang/compiler` の他の潜在ユーザー（このlab以外のapp）にも
  4 capabilityが再利用可能になる。
- (−) native shim（Swift）は今回のスコープでも依然として書く必要がある
  ——「appのruntimeとして新規Rust/nativeを書かない」原則の例外として、
  最小限のOS API glueだけに限定する必要があり、実装時にこの境界を
  厳密に守れているか要レビュー。
- (−) `kotoba-lang/compiler` のdeny-by-default検証パイプライン（KEXE
  署名/fuzzing/sanitizer/SBOM）に4 capabilityの検証を新規に組み込む
  作業が必要——正確な工数は未見積もり。
- (−) WiFiはiOSの制約上、生のRSSIスキャン値を取れない可能性が高く、
  `wifi-info` capabilityの実際の有用性は限定的（位置情報許可下での
  SSID/推定信号強度程度）——floorplan推定の主力は `audio-io`（音響
  sonar）と `ble-scan` になる見込み。
- (−) 本ADRは proposed のまま——capability設計の承認、native shim
  実装者の割当（agentか人間か）、lab repoのscaffold着手はオーナー
  確認後に進める。

## Open decisions for owner

- 本Phase 3a設計方針の承認/却下
- native shim（Swift）実装をagentに任せるか、人間が書くか
- lab repo名（`kotoba-lang/floorplan-lab` 案）の承認
- `wifi-info` capabilityのスコープ（位置情報許可前提を受け入れるか、
  ドロップして `ble-scan` + `audio-io` だけにするか）

## References

- `orgs/kotoba-lang/compiler/docs/adr/0001-worldwide-95-percent-platform-coverage.md`
- `orgs/kotoba-lang/device`
- ADR-2607100030（kami:engine host imports）
- ADR-2607030900（aiueos privileged device-access tender design）
- CLAUDE.md「`.cljc` / `.kotoba` ランタイム優先順位」節

## Addendum（2026-07-13、実装状況）

上記「Open decisions for owner」はオーナーの都度確認を経て「進めてよい」との
返答を得たため、`proposed` の設計をベースに以下まで実装が進んだ（各リポの
main HEADに着地済み、west manifestにも反映済み）。

### 実装済み（Phase 3a ソフトウェア層）

- `kotoba-lang/kotoba-core-contracts`（`adb221f`）: capability id 234-237
  （`motion/read`/`audio/io`/`ble/scan`/`wifi/info`）登録。
- `kotoba-lang/kotoba-lang`（`9c3f1b7`）: `effect-for-kind` に4 capability追加。
- `kotoba-lang/kotoba`（`c7ca33a`）: `src/kotoba/sensing_host.cljc` —
  5 opの決定論的stub host実装（実機ドライバ未注入時）。
- `kotoba-lang/device`（`7bbe30d`）: capability token
  `:motion`/`:audio-io`/`:ble-scan`/`:wifi-info` を追加。既存surfaceと同様、
  driver実体は持たず、host-injected driver（呼び出し側が注入）のまま。
- manifest登録: 上記4リポのpin前進 + `kotoba-lang/floorplan-lab`（新規、
  public）の登録が `com-junkawasaki/root` main（`dc8f871`）に着地。

### 実装済み（`kotoba-lang/floorplan-lab`、実機センサー検証済み）

- `.cljc`アルゴリズム: dead-reckoning（`motion.cljc`）、chirp-echo音響sonar
  （`sonar.cljc`）、BLE RSSI三辺測量（`ble.cljc`）、センサーフュージョン→
  凸包で部屋輪郭推定（`fusion.cljc`）。26テスト/54アサーション全パス。
- `ios/`: `SensingBridge.swift`（CoreMotion/CoreBluetooth/AVAudioEngine/
  NEHotspotNetworkを呼ぶ薄いnative shim）。**実機（iPhone Air, iOS 26.1,
  `17AIR`）で5/5 capability全て動作確認済み**（`motionRead`/`audioPlay`/
  `audioRecord`/`bleScan`/`wifiInfo`、`wifi-info`はiOSの制約通り`nil`が
  期待通りの結果）。実機検証中に見つけた2バグ（マイク権限未リクエスト、
  `AVAudioEngine`のinput node format 0）を修正済み（`d7a241f`, `0f6ad27`）。
  署名はjun784@gmail.comの個人チーム（`3A5CBTEBFP`、free provisioning）。

### 実装済み・実機検証が未完了（3D可視化）

- `web/`: `kotoba-lang/webgpu`（既存の再利用可能なcljs製WebGPU/WebGL2
  render-IR executor）をそのまま使い、`floorplan-lab.viewer`（`web/src/
  floorplan_lab/viewer.cljs`）が壁点群/部屋輪郭をinstance列に変換して描画。
  ingestのたびに`floorplan-lab.motion/sonar/ble/fusion`をフル再計算して
  redrawするため、設計上はリアルタイム更新（motion ~20Hz、sonar 3秒毎、
  ble 5秒毎）になっている。
- `ios/FloorplanLabApp/FloorplanMapView.swift`: WKWebViewの薄いホスト
  （`UIViewRepresentable`）。判定/フュージョン/レンダリングロジックは
  一切持たず、`SensingBridge`の生データを`evaluateJavaScript`でcljs側に
  渡すだけ（ADR-2607078000 addendumの「ネイティブラッパーは既存の
  ブラウザ実証済みcljs経路をwebviewで包む、新規native描画コードを
  書かない」方針に準拠）。
- Simulatorビルド・実機ビルドともに成功、アプリはクラッシュせず起動する
  ことを確認済み。**しかし実機で3D画面自体が表示されない（`表示されません`
  という実測報告あり、2026-07-13時点で原因未特定）。** デバッグのため
  WKWebViewのconsole.log/warn/errorをSwift側にforwardする一時的な
  `WKScriptMessageHandler`ブリッジを追加（`a4db495`）。60秒のコンソール
  キャプチャを試みたが、実機操作者が3D画面へのnavigation自体を行う前に
  キャプチャ枠が終了し、`[WebConsole]`ログを一件も取得できなかった——
  **WKWebViewの初期化/レンダリング自体が失敗しているのか、単に画面遷移が
  行われなかっただけなのか、まだ切り分けできていない。** 次回セッションで
  実機操作者と時間を合わせてconsoleキャプチャを再試行するか、Safari Web
  Inspector（`webView.isInspectable = true`済み）で直接デバッグするのが
  follow-up。

### 未着手のまま

- `web/`側の3D描画結果とSwift側のセンサー生データの実際の整合性
  （`sampleIndex`近似の妥当性等）は実機で未検証（実機操作者との協調が
  必要、引き続きfollow-up）。
- ~~`manifest/west.yml`の`floorplan-lab` pinが最新に追従していない~~
  → Addendum 2（2026-07-15）で解消。

## Addendum 2（2026-07-15、west pin追従）

前addendumが記録していたfollow-upのうち、west pinの追従だけを解消した
（実機3D描画の検証は引き続き実機操作者待ち、対象外）。共有checkout
（`orgs/kotoba-lang/floorplan-lab`）自体は既に最新`a4db495`にあったが、
`manifest/west.yml`のpinだけが`e2b97ad0855c`（iOS shim/3D viewer追加前）
のまま取り残されていた。`gh api`で新pinを検証（`ahead_by=14,
behind_by=0, merge_base==旧pin`）した上で、west.ymlの該当1行のみ
手動編集し、sibling worktree + `gh api .../merges`サーバ側マージで
着地した。
