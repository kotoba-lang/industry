# ADR-2607140600: 建物内部構造（floorplan）推定lab のための kotoba-lang/compiler Phase 3a capability bridge（motion/audio-io/ble-scan/wifi-info）設計 + kotoba-lang/device 統合提案

**Status**: proposed（未承認——実装前にオーナーレビュー必須）
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
