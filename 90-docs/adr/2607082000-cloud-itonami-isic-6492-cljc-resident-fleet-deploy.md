---
id: adr-2607082000-cloud-itonami-isic-6492-cljc-resident-fleet-deploy
title: "ADR-2607082000: cloud-itonami-isic-6492 の affordability.wasm を asher 上で cljc/nbb 常駐 HTTP デーモンとして稼働 — Rust kotoba-server を置換した初の常駐 wasm デプロイ"
status: closed
doc_type: adr
topic: cloud-itonami-isic-6492-cljc-resident-fleet-deploy
authoritative: true
last_verified: 2026-07-08
authoritative_for:
  - "cloud-itonami-isic-6492 の affordability.wasm を、一回きりの動作確認(ADR-2607072600)ではなく、murakumo fleet ノード asher 上で真に常駐(launchd LaunchDaemon、KeepAlive で自己修復)する HTTP デーモンとしてホストした初の実例であること"
  - "この常駐デーモンが JVM を一切使わず、nbb(ClojureScript-on-Node)経由で動作すること — root CLAUDE.md の kotoba-wasm > clojurewasm > cljs > nbb > jvm ランタイム優先順位に従い、asher に JDK が無い制約(ADR-2607072530で確認済み)を回避する設計判断"
  - "asher 上で従来常駐していた Rust kotoba-server(com.murakumo.kotoba-mesh、libp2p gossipsub mesh + KSE 等を提供)を停止し、cljc/nbb デーモン(com.murakumo.cljc-isic-6492)に置換したこと — フリート10ノード中 asher の1ノードのみが対象で、他9ノードは無変更"
  - "nbb の SCI インタプリタに、ネストした Promise チェーンで `.then().catch()` が実行時に `Could not find instance method: catch` で失敗するバグが再現すること(単純な再現コードでは再現せず、実際の入れ子HTTPハンドラ形状でのみ発生 — 根本原因は未特定)。回避策として `.then(onFulfilled, onRejected)` の2引数形式を全面採用したこと"
related:
  - 90-docs/adr/2607072530-cloud-itonami-kototama-wasm-llm-infer-poc-isic-6511.md
  - 90-docs/adr/2607072600-cloud-itonami-isic-6492-kototama-tender-wasm-deploy.md
  - 90-docs/adr/2607072400-kaisha-pod-murakumo-fleet-deployment.md
supersedes: []
superseded_by: []
---

# ADR-2607082000: cloud-itonami-isic-6492 を asher 上で cljc/nbb 常駐 HTTP デーモンとして稼働

**Status**: closed（2026-07-08、Addendum 3 でclose）
**Date**: 2026-07-08
**Deciders**: Jun Kawasaki（オーナー指示「deploy を進めて、rust 版ではなく cljc 版」を受けて着手）

## Context

ADR-2607072530/2607072600 は cloud-itonami の `.kotoba`→WASM actor を murakumo fleet 実機(asher)で**動作確認**したが、いずれも SSH 経由の一回きりのスクリプト実行（`node wasm/verify_node.mjs` 等）であり、実行後に一時ファイルを削除する形だった。常駐(resident)プロセスとして継続稼働するものではなかった。

オーナーから「deploy を進めて」と明示的に指示された。合わせて、常駐先を JVM ベース（`kototama.tender`、ADR-2607072530/2607072600 が使った経路）ではなく **cljc 版**にする方針を確認した。調査の結果:

- `kotoba.wasm-exec`（`kotoba-lang/kotoba`）も `kototama.tender`（`kotoba-lang/kototama`）も、常駐用の HTTP リスナー・tick スケジューラ・lattice/gossipsub 配線を一切持たない、単発実行ライブラリであることを確認（`grep` で `on-http`/`on-tick`/`on-kse`/`ring.`/`http-kit`/`jetty` はゼロヒット）。
- `murakumo` の deploy tooling（`bin/BUILD.edn`、`src/murakumo/provision/plan.cljc`、`deploy/*.plist.tmpl`）は Rust バイナリ専用で、JVM/cljc 版の常駐経路は一切配線されていない。
- フリート全ノード（asher/naphtali/judah/zebulun/issachar 等）に JDK は未インストール（ADR-2607072530 で確認済み）。JDK を新規導入せず「cljc 版」を成立させるには、`orgs/kotoba-lang/wasm-webcomponent` の `actor-host.js`（プレーン Node.js ホスト）+ `nbb`（ClojureScript-on-Node、ビルド不要）の組み合わせが root CLAUDE.md のランタイム優先順位（kotoba wasm > clojurewasm > **cljs > nbb** > jvm）に整合し、かつ asher には既に Node.js v26.4.0 が残置されている（ADR-2607072530 の副産物）。

オーナーへの事前確認（AskUserQuestion）で以下を明確化した:
1. **Rust 版を置換する**（別ポートで共存ではなく）— kaisha realtime pod(ADR-2607072400)等の既存依存が壊れるリスクを明示した上で選択された。
2. 対象 actor は **isic-6492 affordability**（isic-6511 underwriting は llm-infer capability の host 側実装が別途必要でスコープが大きいため見送り）。
3. **JDK を使わない設計**。

実機調査で、asher の Rust `kotoba-server`（`com.murakumo.kotoba-mesh`、pid 67421、port 8077）が実際に稼働中で、`/health` が `kse_journal`/`kse_shelf`/`wasm_executor`/`udf_executor`/`invoke_router` すべて `"ready"` と応答し、9 ノード分の bootstrap peer を持つ libp2p gossipsub mesh の一員である（実測時点で `peer_count:0` — 現在メッシュには接続していない）ことを確認した。この事実を提示した上で最終確認を取り、実施に進んだ。

## Decision

**`orgs/cloud-itonami/cloud-itonami-isic-6492/wasm/server.cljs` を新設し、asher 上の Rust `kotoba-server` LaunchDaemon を置換する形で常駐デプロイした。**

### server.cljs の設計

`verify_node.cljs`（既存、nbb 経由で `actor-host.js` の ABI を使い affordability.wasm を一回実行する CLI）と同じ ABI 配線を再利用し、`node:http` の永続リスナーへ変換した:

- `GET /health` — liveness probe。
- `POST /isic-6492/affordability` — body `{existingDebt, requestedAmount, annualIncome}`(セント単位 i32) → WASM の export 済み linear memory のオフセット 0/4/8 へ書き込み、`main()` を呼び出し `{ok, result, affordable, input}` を返す。
- リクエストごとに `WebAssembly.instantiate` を新規実行（121 バイトの小さい module のため軽量。インスタンス間の状態リークを避ける最も単純な設計）。

### nbb の `.catch` ランタイムバグ

実装中、`(-> promise (.then f) (.catch g))` 形の chaining が実際の入れ子 HTTP ハンドラ内で `Could not find instance method: catch` という実行時エラーで落ちることを発見した。5 パターンの最小再現コード（`js/Promise.resolve` 直後の chaining、手動構築 `js/Promise.` + chaining、二重ネストした `cond`/`->`、`try`/`catch` 特殊形式との共存、`WebAssembly.instantiate` を挟んだ chaining）はいずれも**再現しなかった**——実際の `server.cljs` の形（`node:http` のコールバック内で `read-body` の Promise を `.then` し、その中の `cond` 分岐でさらに別の Promise を `.then`/`.catch` する）でのみ発生する。根本原因は未特定のまま、`.then(onFulfilled, onRejected)` の2引数形式（`.catch` を一切使わない）に全面書き換えることで回避した。これは新規の `.kotoba` コンパイラ制約バグ（ADR-2607072530/2607072600 の `pos?`/`neg?`/`and`/`or`/`when` 非対応）とは別種の、**nbb ランタイム自体**のバグとして記録する。

### 常駐デプロイ手順

1. `orgs/kotoba-lang/wasm-webcomponent/src/`（21ファイル）と `cloud-itonami-isic-6492/wasm/`（`server.cljs`/`affordability.wasm`/`node_modules`/`package.json` 等）を、sibling-checkout レイアウトを保ったまま `$HOME/.murakumo-cljc/orgs/...` という安定パス（`/tmp` ではない — 再起動後も残る）へ rsync。
2. ローカルおよび asher 自身の localhost で全エンドポイント（health / approve / reject / zero-income / missing-field / invalid-json / 404 / 状態リーク無し確認の再実行）を動作確認。
3. `sudo launchctl bootout system/com.murakumo.kotoba-mesh-watchdog` → `sudo launchctl bootout system/com.murakumo.kotoba-mesh`（Rust 版停止。plist ファイル自体は `/Library/LaunchDaemons/` に残置——復元は再 bootstrap するだけ）。
4. 新設 `/Library/LaunchDaemons/com.murakumo.cljc-isic-6492.plist`（`ProgramArguments = [/opt/homebrew/bin/node, .../node_modules/.bin/nbb, server.cljs, 8479]`、`RunAtLoad`+`KeepAlive`、`WorkingDirectory` = wasm dir）を作成・`launchctl bootstrap`+`kickstart`。
5. tailnet 経由（`100.96.122.69:8479`）で operator machine から health/approve/reject を実行し、ローカル実行と一致することを確認。
6. **KeepAlive の実証**: 稼働中の nbb プロセスを `sudo kill -9` で強制終了し、数秒後に launchd が自動再起動、`/health` が再び 200 を返すことを確認 — 真に「常駐」（一回きりの検証ではなく、プロセス死亡から自己修復する）であることの実測証拠。

## Consequences

- **cloud-itonami の wasm actor が初めて、真の意味で「常駐」した。** ADR-2607072530/2607072600 が明示的に残していた follow-up（「No wire transport puts a host in front of this ABI yet」）を解消した。
- **asher は libp2p gossipsub mesh から外れ、ADR-2607072400 の kaisha/denrei realtime pod は asher 上で機能しなくなる。** 実測時点で `peer_count:0`（メッシュに実際には接続していなかった）だったとはいえ、mesh 参加可能な状態ではなくなった。フリートの他 9 ノードは Rust kotoba-server のまま無変更。
- **`murakumo` 自身の provisioning tooling(`nbb murakumo provision`/`mesh`)はこの変更を認識しない。** 将来誰かが asher に対して同ツールを実行すると、`render-plist` は無条件に Rust 版 plist を再生成するため、**黙って cljc 版から Rust 版に戻る**（意図的なフェイルセーフとして許容 — 恒久的な fleet 運用変更ではなく実験的デプロイという位置付け）。
- 新設 HTTP エンドポイントは平文・無認証（実験目的として許容、本番運用には不十分）。
- 単一 actor（isic-6492）専用の固定ルーティングであり、汎用 dispatcher ではない。

## What this ADR does NOT decide

- フリート全体（他9ノード）への cljc/nbb ロールアウト。
- asher 上での mesh 参加・kaisha pod 機能の復元（両立させる設計、例えば別ポートでの共存や、Rust 版のメッシュ機能だけを別途起動し続ける構成は today 検討していない）。
- nbb の `.catch` バグの根本原因特定・upstream 報告。
- 認証・TLS 等、本番運用に必要な硬化。

## Revert 手順（asher 初回配備時点。Addendum のプルーン後は末尾参照）

```sh
ssh asher "sudo launchctl bootout system/com.murakumo.cljc-isic-6492 2>/dev/null; \
  sudo launchctl bootstrap system /Library/LaunchDaemons/com.murakumo.kotoba-mesh.plist && \
  sudo launchctl kickstart -k system/com.murakumo.kotoba-mesh && \
  sudo launchctl bootstrap system /Library/LaunchDaemons/com.murakumo.kotoba-mesh-watchdog.plist && \
  sudo launchctl kickstart -k system/com.murakumo.kotoba-mesh-watchdog"
```

## Verification

- ローカル: `nbb server.cljs <port>` を起動し、health/approve/reject/zero-income/missing-field/invalid-json/404/繰り返し呼び出し（状態リーク無し）を全て確認。
- asher localhost: 同上を全て確認。
- asher tailnet（`100.96.122.69:8479`）: operator machine から health/approve/reject を実行し、ローカル実行と同一結果を確認。
- KeepAlive 自己修復: `sudo kill -9 <pid>` 後、launchd が自動的にプロセスを再起動し `/health` が復帰することを確認。
- `gh push`: `orgs/cloud-itonami/cloud-itonami-isic-6492` main（`d7cc1fa..d598e26` — `wasm/server.cljs` 新設、`wasm/README.md` 更新）。

## Addendum (2026-07-08, same day): フリート残り4ノードへのロールアウト + Rust 資産の prune

オーナー指示「rust 関係は prune してok、他の mac mini fleet も同様に」を受けて、`orgs/kotoba-lang/murakumo/fleet.edn` の全10ノード中、到達可能な残り4ノード（naphtali/judah/zebulun/issachar）にも同じ cljc/nbb 常駐デーモンを配備し、5ノード全てで置換済みの Rust 資産を実際に削除（prune）した。

### 到達性

- 到達・配備完了: asher（初回）、naphtali、judah、zebulun、issachar（計5/10）。
- 到達不可（SSH タイムアウト）: simeon、levi、joseph、dan（4/10）— 電源/ネットワーク状態は未調査、後日再試行が必要。
- 到達するが配備不可: benjamin（1/10）— SSH 認証・セッション確立は成功するが、リモートログインシェルの起動時に何らかのエラーが発生し、単純な `echo` すら標準出力が返らず終了ステータス1になる。原因未調査（`.zshrc`/`.zprofile` 等のシェル起動スクリプト起因の可能性が高い）。

### 配備差分（naphtali/judah/zebulun/issachar）

- **Node.js が無かった3ノード（judah/zebulun/issachar）に `brew install node` で新規導入**（naphtali は既に v22.22.2 が導入済みだった）。3ノードとも `brew` 自体は既存（v26.4.0 がインストールされた）。
- 他はasherと全く同じ手順（`wasm-webcomponent/src` + `cloud-itonami-isic-6492/wasm` を `$HOME/.murakumo-cljc/orgs/...` へ rsync、`com.murakumo.kotoba-mesh`(+watchdog、存在するノードのみ)を bootout、`com.murakumo.cljc-isic-6492.plist` を各ノードの `$HOME`/`UserName` に合わせてレンダリングし bootstrap+kickstart）。
- 4ノード全てで tailnet 経由の `/health` と `POST /isic-6492/affordability`(approve シナリオ)を実行し、asher と同一の結果を確認。KeepAlive の再実証（kill -9）は asher で既に確認済みのため省略。

### issachar の固有事情

`fleet.edn` のコメントは「issachar は既に :8077 で kotoba-server を稼働中(yabai CTI persistence)のため、murakumo mesh ノードは別ポート(8076)を使う」と記していたが、実機確認では issachar の :8077 は現在何もリッスンしておらず(yabai CTI 用途の kotoba-server は現状稼働していない)、`com.murakumo.kotoba-mesh` ラベルの mesh 用プロセスのみが稼働していた。ラベル指定での bootout のため、この点は配備の安全性に影響しない。

### Rust 資産の prune（5ノード全て）

各ノードの `~/.murakumo/bin/` には Rust の `kotoba-server`/`kotoba` バイナリだけでなく、**別件の llama.cpp 分散推論 RPC サーバー一式**（`rpc-server`、`libggml-*.dylib`、`libllama-*.dylib`、`libmtmd*.dylib`、`murakumo-rpc-bin.tgz`、`prewarm-fetch.sh`）が同居していることを実機確認で発見した。後者は本 ADR の対象と無関係な現役設備（murakumo-exo-distributed-inference 系統）であり、削除対象から明確に除外した。

prune した対象（5ノード全て）:
- `/Library/LaunchDaemons/com.murakumo.kotoba-mesh.plist`
- `/Library/LaunchDaemons/com.murakumo.kotoba-mesh-watchdog.plist`（存在するノードのみ — asher と zebulun）
- `~/.murakumo/bin/kotoba-server`（65MB）、`~/.murakumo/bin/kotoba`（35MB）

prune 後、5ノード全てで `curl .../health` が HTTP 200 を返すことを再確認し、cljc/nbb デーモンの稼働に影響が無いことを確認した。

**触れていないもの**: `~/.murakumo/bin/` 内の llama.cpp RPC 関連一式、`~/.murakumo/store`（datom ログ）、`~/.murakumo/*.log`、`ai.gftd.murakumo.plist.bak.*`（全ノードに残る古いバックアップ）、naphtali の `ai.gftd.murakumo.system.plist`（別ラベル・現役稼働中、本 ADR と無関係）。

### Revert 手順（prune 後 — 上記の単純な plist 再 bootstrap は使えない）

plist ファイルと Rust バイナリ自体を削除したため、単純な `launchctl bootstrap` では戻せない。Rust 版に戻すには、`orgs/kotoba-lang/murakumo` の provisioning フロー（`bin/BUILD.edn` のバイナリ再取得 + `src/murakumo/provision/plan.cljc` の `render-plist` によるテンプレートからの再生成）を当該ノードに対して再実行する必要がある — 本 ADR の対象外（"prune してok" は不可逆な選択として明示的に指示されたもの）。

### Verification（Addendum）

- Node.js 導入: `brew install node`(judah/zebulun/issachar) → 全て `v26.4.0`。
- 配備: naphtali/judah/zebulun/issachar 全てで tailnet 経由の `/health`(200) と `POST /isic-6492/affordability`(approve、`{"result":1,"affordable":true,"ok":true}`)を確認。
- Prune: 5ノード全て(asher/naphtali/judah/zebulun/issachar)で `com.murakumo.kotoba-mesh*.plist` と `~/.murakumo/bin/{kotoba-server,kotoba}` の削除を確認、prune後も5ノード全てで `/health` が HTTP 200。

## Addendum 2 (2026-07-08, 同日): 再検証 — 状態変化なし

上記 addendum の直後に再検証を実施した。

- **prune 済み5ノード全て**（asher/naphtali/judah/zebulun/issachar）で `curl .../health` が HTTP 200 を継続していることを再確認。`sudo launchctl list | grep cljc-isic-6492` でも全ノードで `com.murakumo.cljc-isic-6492` が稼働中（pid は前回確認時と異なり、KeepAlive によるものか単純な継続稼働かは未区別）。
- **未到達だった5ノード**（simeon/levi/joseph/dan/benjamin）を再度確認したが**状態に変化なし**: simeon/levi/joseph/dan は依然 SSH タイムアウト、benjamin は依然 SSH セッションは確立するがログインシェル起動時のエラーでコマンドが実行できない。原因調査・復旧はまだ行っていない。

## Addendum 3 (2026-07-08, 同日): 最終再検証 — closing

オーナー指示「update adr, closing」を受け、最終確認を行いこの ADR を close する。

- **配備済み5ノード全て**（asher/naphtali/judah/zebulun/issachar）で `curl .../health`（tailnet 経由、port 8479）を再実行し、全ノードで `{"ok":true,"service":"cloud-itonami-isic-6492-affordability","runtime":"nbb/node (no JVM)",...}` の200応答を確認。`sudo launchctl list | grep cljc-isic-6492` でも全ノードで `com.murakumo.cljc-isic-6492` が起動中（"last exit status" 列は `-9`(asher)/`-15`(他4台) — KeepAlive による過去の再起動履歴であり、現在の稼働状態には影響しない）。Addendum 2 からの状態変化なし。
- **未到達5ノードも状態変化なし**: simeon/levi/joseph/dan は依然 SSH タイムアウト。benjamin は依然 SSH セッション自体は確立するがログインシェル起動時に無出力・exit status 1 で失敗し、コマンド実行に至らない（原因未調査のまま）。

**Close の判断**: フリート10ノード中5ノードへの cljc/nbb 常駐デプロイという当初スコープ（オーナー指示「deploy を進めて、rust 版ではなく cljc 版」「他の mac mini fleet も同様に」）は完了・安定稼働を再確認した。残り5ノード（未到達4 + benjamin）への展開、フリート全体の provisioning tooling（`murakumo` 側）への正式統合、nbb `.catch` バグの根本原因調査、認証/TLS 等の本番硬化は、いずれも本 ADR が「What this ADR does NOT decide」で明示済みの別スコープの follow-up として残し、本 ADR 自体はここで close する。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-6492/wasm/server.cljs`, `wasm/README.md`
- `orgs/kotoba-lang/wasm-webcomponent/src/actor-host.js`
- `orgs/kotoba-lang/murakumo/deploy/com.murakumo.kotoba-mesh.plist.tmpl`（置換元 Rust 版テンプレート）
- `orgs/kotoba-lang/murakumo/fleet.edn`（10ノードのフリート inventory SSoT）
- ADR-2607072530（llm-infer capability、murakumo fleet 実機配備の先例、JDK 不在の実測）
- ADR-2607072600（kototama.tender 経由の一回きり動作確認、本 ADR が「常駐」へ発展させた対象）
- ADR-2607072400（kaisha realtime pod、asher の Rust kotoba-server 依存 — 本 ADR で影響を受ける）
