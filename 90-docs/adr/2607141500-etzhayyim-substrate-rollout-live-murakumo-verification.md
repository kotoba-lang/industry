# ADR-2607141500: etzhayyim-substrate-rollout — 実Murakumo fleetによるライブ検証(ADR-2607132200 third amendment, closing)

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki(「api.murakumo.cloud についての到達性について調査して. tailscale も確認」
→ 発見結果の報告に「はい、進めて」→ 4リポジトリ全件のライブ検証完了後「update adr, closing」)
**Amends**: ADR-2607132200(初版)、ADR-2607140500(第1 amendment)、ADR-2607141000(第2 amendment)
**Scope**: `orgs/etzhayyim/com-etzhayyim-yosoku`、`orgs/etzhayyim/com-etzhayyim-fleet`、
`orgs/etzhayyim/com-etzhayyim-tashikame`、`orgs/etzhayyim/com-etzhayyim-kouhou`

## Context

ADR-2607141000までの3本のADRは、governed System-Dynamicsモデルと実LLM配線コードを
実装・テストしたが、いずれも「この環境からMurakumo fleetへのライブ接続は確認できな
かった(`127.0.0.1:11434`/`192.168.1.70:4000`とも到達不可)」という限界を明記していた。
ユーザーの指示により `api.murakumo.cloud` の到達性と Tailscale 接続を調査した結果、
**実際に稼働中のMurakumo fleetへライブ接続できる経路が見つかり、4アクター全てで
実LLM推論を使った end-to-end 動作を実証した**。本ADRはその結果を記録し、
一連のetzhayyim実LLM配線作業(ADR-2607132200〜)を締めくくる。

## Decision

### 1. 発見: Murakumo fleetの実トポロジー

- `api.murakumo.cloud`(Cloudflare経由)は実在・稼働中——`/health`は
  `{"ok":true,"service":"local-murakumo","storage":{"backend":"kv","ok":true}}`
  を返し、`/nodes` はフリートの自己申告レジストリAPIとして機能している。
- `murakumo.cloud`(apiサブドメイン無し)は "Decentralized GPU Cloud,
  Blockchain-Native" を謳う実サイトで、ecosystem内の他ADRの記述
  (CID/EDN datoms、leaderless auction、no central scheduler)と一致した。
- **Tailscale tailnet(`com-junkawasaki@`)に12ノード**(創世記12部族由来の命名:
  asher/benjamin/dan/gad/issachar/jacob(offline)/joseph/judah/levi/naphtali/
  simeon/zebulun + main-2)。`api.murakumo.cloud/nodes` レジストリと突き合わせ、
  そのうち**8ノードが`:11434`で生Ollamaを直接公開**(dan/zebulun/levi/benjamin/
  issachar/joseph/naphtali/simeon、いずれも`gemma4:e4b-it-qat`等を配備)、
  **judah(`:4000`)はLiteLLM風ゲートウェイでAPIキー必須**(未取得、今回未使用)、
  **gad(`head-gad`、`:8090/v1`)は35Bモデル(`qwen-agentworld-35b-a3b`)の分散
  推論クラスタのヘッドとしてレジストリに登録されているが、現在プロセス未起動
  (接続拒否)**——これは私からは起動できない運用操作。
- 既存コードの `allowed-infer-hosts`(4リポジトリ共通パターン)は
  `127.0.0.1:11434`/`192.168.1.70:4000` 等**ローカル/LANのみ**を許可しており、
  上記tailnet実体は未登録だった——実際に到達可能な経路が、コードのガードに
  よってブロックされていた状態。

### 2. allowed-infer-hostsへのtailnet追加(4リポジトリ共通)

`tashikame.advisor`/`kouhou.advisor`/`yosoku.advisor`/`fleet.advisor` の
`allowed-infer-hosts` に、確認済み8ノードの `<tailscale-ip>:11434` を追加した
(`api.murakumo.cloud/nodes` レジストリと突き合わせ済みの同一物理フリート、
経路がLAN→Tailscaleに変わるだけ)。

### 3. 実バグ発見・修正: "thinking"モデルのトークン予算

`gemma4:e4b-it-qat` はOpenAI-compatible応答の `message` に `content` とは別の
`reasoning` フィールドを持つ"thinking"モデルで、`max-tokens` が小さいと
reasoning側でトークン予算を使い切り `content` が空になる。yosokuの実行で
実際に再現(`max-tokens 256` → `disposition :hold`、`basis [:missing-model
:low-confidence]`)——**governorは空応答を正しくholdし、誤commitは発生しな
かった**(安全設計が実際の失敗モードで機能した実例)。原因特定後、4リポジトリ
全ての `deploy.clj`(yosoku/fleet: 256→1024、tashikame/kouhou: 512→1024、
後者2つは実際には再現しなかったが予防的に統一)を修正した。

### 4. ライブ検証結果(4アクター全件、`dan`ノード = 100.98.142.59:11434)

| Actor | 入力 | 実LLM出力 | disposition |
|---|---|---|---|
| yosoku | intent「mimamori consent coverageを慎重に拡大」 | `G7ConsentGate: 0.05 → 0.1`、根拠付き | `:commit` |
| fleet | pending proposal 3件(1件protected path) | 実LLM pickerがtriage、2件即materialize、1件はsign-off後materialize | `:materialize` / `:signoff→:materialize` |
| tashikame | クレーム「水は海抜0mで100℃で沸騰する」+ 実Wikipedia URL | `:supported`、confidence 1.0、引用根拠付き | `:commit` → publish(mock) |
| kouhou | 令和8年度衛生対策方針(例)プレスリリース | 日本語要約(domain `:health`、tags 衛生対策/予防接種/保健所)、confidence 1.0 | `:commit` → publish(mock) |

いずれも `MockPublisher`/in-memory `:materialize` は維持したまま(実publish/実git
書き込みは別途 owner の判断——ADR-2607132200以来一貫した境界)、**advisor/picker
の推論のみ実際のMurakumo fleetを使用**した。

## Consequences

- (+) ADR-2607132200が「設計はあるが実装は自己ゲート済み」と評価した
  `RealLLMWiringGate` 相当のギャップが、**4アクター全件で実際に閉じた**——
  「実際に動くように」という一連の作業の目標を、mockでなく実インフラで達成。
  数値上は `RealLLMWiringGate` は依然0.1(自己賦課ゲート自体は未変更、
  本ADRはコードパスの実証であり、ゲート値変更というCouncil決議行為ではない)。
- (+) `allowed-infer-hosts` の拡張は「同一フリートへの到達経路追加」であり
  「新しい第三者GPUの許可」ではない——Rider v3.3 §2(i)の「no opaque/lock-in
  commercial GPU」という制約は維持されたまま。
- (+) governorの安全設計(実行結果を独立再検証してから commit/hold を判定)が、
  実LLMの実際の失敗モード(thinking modelのトークン予算枯渇)に対しても
  設計通り機能したことを実証できた——mockでの想定シナリオだけでなく、
  実際に想定外の失敗が起きて、それを正しく吸収した。
- (−) `head-gad`(35Bモデルの分散推論クラスタ)は未起動のままで、今回の検証は
  8Bクラス以下の小型モデル(`gemma4:e4b-it-qat`)に限られる——より大規模な
  推論が必要な場合は別途起動(SSH等の運用操作、私の範囲外)が必要。
- (−) `judah`(`:4000`、LiteLLMゲートウェイ)はAPIキー未取得のため未検証——
  1Password/kagi索引(`secrets-location-map`)に該当項目が見当たらず、
  follow-upとして残る。
- (−) ライブ検証は各アクター1回ずつの単発実行であり、継続的な可用性・
  レイテンシ・コスト特性は未計測。

## Artifacts

- `orgs/etzhayyim/com-etzhayyim-yosoku` PR #5(`f76747b → b03ecfd`)
- `orgs/etzhayyim/com-etzhayyim-fleet` PR #2(`9be7aa3 → 7559617`)
- `orgs/etzhayyim/com-etzhayyim-tashikame` PR #4(`1b71970 → d0affc6`)
- `orgs/etzhayyim/com-etzhayyim-kouhou` PR #4(`8dc1a31 → 5c279ea`)
- `manifest/west.yml` の4リポジトリ分のpinを上記へ前進(GitHub API commit、
  yosoku/fleetは個別single-entry、tashikame/kouhouは同一作業単位としてまとめて1 commit)。

## References

- ADR-2607132200(初版)、ADR-2607140500(第1 amendment)、ADR-2607141000(第2 amendment)
- `orgs/etzhayyim/root/90-docs/adr/2605192100-etzhayyim-mission-charter.md`
  §1.12(`RealLLMWiringGate` が代替する「行政手続き: LLM + agent fleet」の行)
