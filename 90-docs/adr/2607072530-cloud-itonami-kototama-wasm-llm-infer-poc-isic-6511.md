---
id: adr-2607072530-cloud-itonami-kototama-wasm-llm-infer-poc-isic-6511
title: "ADR-2607072530: cloud-itonami-isic-6511 の governor 決定ロジックを .kotoba/actor:host wasm 化し、新設 llm-infer capability 経由で murakumo fleet の1ノード(asher)へ JVM 無しで実配備した(kototama wasm PoC)"
status: accepted
doc_type: adr
topic: cloud-itonami-kototama-wasm-llm-infer-poc
authoritative: true
last_verified: 2026-07-07
authoritative_for:
  - kotoba-core-contracts の llm/infer capability(id 225)の存在とABI形状
  - kototama.tender(JVM)およびwasm-webcomponent(Node,JVM不要)の2系統のllm-infer実装が存在すること
  - cloud-itonami-isic-6511のgovernor決定ロジックを.kotobaへ縮約する際の設計方針(Store/StateGraph/EDNマップは対象外)
  - murakumo fleetの実行系がRust(フリーズ済み)/JVM(kotoba.wasm-exec)/kototama.tender(JVM)の3系統に分裂しており、かつ全ノードにJVMが存在しないという実測事実
  - 本ADR時点でmurakumoの正式なmesh/auction配布経路(nbb murakumo deploy)は未修復のままであること(フォローアップ課題)
related:
  - 90-docs/adr/2607062330-kototama-tender-chicory-execution-runtime.md
  - 90-docs/adr/2607062400-wasm-webcomponent-actor-host-browser.md
  - 90-docs/adr/2607071250-cloud-itonami-brokerage-6612-coverage.md
  - 90-docs/adr/2607071320-cloud-itonami-credit-6492-coverage.md
  - 90-docs/adr/2607070900-cloud-itonami-core-business-domains-testing.md
  - 90-docs/adr/2607072400-kaisha-pod-murakumo-fleet-deployment.md
  - 90-docs/adr/2607071400-murakumo-family-k8s-wasmcloud-wadm-positioning.md
supersedes: []
superseded_by: []
---

# ADR-2607072530: cloud-itonami の kototama wasm PoC — llm-infer capability 新設 + murakumo fleet 実機(JVM無し)への配備

**Status**: accepted
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki

## Context

オーナー指示「cloud-itonami の成熟度・coverage を向上させ、各actorを murakumo fleet に kototama wasm としてデプロイする」を受けて着手。調査の結果、以下が判明した:

1. cloud-itonami は ISIC/ISO3166/COFOG/UNSPSC 等約900エントリのblueprint群を持つが、「implemented」(実際に動くactor)段階はISICの16件のみ。
2. `.kotoba`(kotoba言語の極小WASMサブセット)には LLM 呼び出し用の host capability が存在せず、cloud-itonami の実装済みactor(langgraph-clj StateGraph + langchain-clj advisor)をそのままwasm化することは不可能。
3. murakumo fleet(Apple M4 Mac mini 10台、常時XMRig稼働中の共有インフラ)が実際に動かす `kotoba-server` は、2026-07-01 に `kotoba-lang/kotoba` 本体から削除された旧Rust実装のフリーズ済みバイナリで、現行mainの祖先ではない。現行JVM実装(`kotoba.wasm-exec`)・`kototama.tender`(JVM/Chicory)を含め、実行系が3系統に分裂しており相互に非互換。
4. さらに実機確認(asher/naphtali/judah/zebulun/issachar)の結果、**フリート全ノードにJVMが一切インストールされていない**(Rustバイナリ+XMRigのみの構成のため)。`kototama.tender`をそのまま動かすには新規にJDKを共有マイニング機材へ導入する必要があり、これは避けるべきと判断した。

オーナーは対話中、以下を明示的に指示・選択した:
- wasm化はLLM advisorを含めて丸ごと行う(`llm-infer` capabilityを新設)。
- 小バッチでPoCを1本確実に通し、手順をADR化する。
- murakumo側はRustを退役しcljc/kotoba実装へ一本化する方向で進める。
- JVMには依存せず、`kotoba wasm` の枠内(kototama wasm / clojurewasm / ClojureScript / nbb)で実現する(CLAUDE.mdのランタイム優先順位に整合)。

## Decision

**PoC対象は `cloud-itonami-isic-6511`(生命保険underwriting、全implemented actor中最小構成)。llm-infer capability を新設し、2系統のホスト実装を用意した上で、JVM不要な Node.js/`wasm-webcomponent` 経路で murakumo fleet の実ノード(asher)へ配備・実行確認した。**

### 1. `llm/infer` capability の新設(id 225)

`kotoba-core-contracts`(`capability_contract.edn`)に `llm/infer`(id 225、`(prompt-ptr prompt-len out-ptr out-cap) -> bytes-written|-1`)を登録。2つの独立したホスト実装を用意:

- **`kototama.tender`(JVM/Chicory)**: `http-post-host-fn` を雛形に `llm-infer-host-fn` を実装。Anthropic Messages API への直接HTTP呼び出し。APIキー解決は `cloud_itonami.runtime/model-config` と同一のenv var chain(`ITO_MODEL_API_KEY→OPENAI_API_KEY→OPENCLAW_API_KEY→HERMES_API_KEY→ANTHROPIC_API_KEY`)。キー未設定・非2xx・ネットワークエラー・不正応答はすべて `-1` でfail-closed。
- **`wasm-webcomponent`(Node.js、JVM不要)**: `actor-host.js` に `llm-infer` を追加。`http-post` と異なり本質的に実装不可能ではない(ブラウザタブは同期ネットワークI/Oができないが、Node の `child_process` は可能)ため、`opts.llmInfer`(同期関数)を注入する形で実装。未注入時は `http-post` と同じ「宣言はされるがlinkされない」誠実な扱いに倣う。

いずれもGitHub PRとしてレビュー・テスト green(kotoba-core-contracts 8 tests/86 assertions、kototama 31 tests/60 assertions、wasm-webcomponent 22 checks)を確認の上でmainへマージ。

### 2. `.kotoba` 決定モジュール(cloud-itonami-isic-6511)

`wasm/underwriting_decision.kotoba` として新規作成。governor の3 HARD違反チェック(spec-basis / sanctions / documents)+ confidence閾値(0.6)+ llm-infer によるadvisor consultを、`.kotoba` の極小文法(`if`/`let`/`do`/算術/比較のみ)へ翻訳した。**Store永続化・EDNマップ・langgraph-clj StateGraphは対象外**(スカラー5バイト入力・decision code 1バイト出力に縮約)。

副産物として `.kotoba` コンパイラの実バグを発見: `and`/`or`/`when` はソース安全性チェックを通過するが `kotoba wasm emit` のWASMコード生成では `unsupported-op` で失敗する。nested `if` で回避し、`wasm/README.md` に明記した。

### 3. murakumo fleet 実機(asher)への配備

`kototama.tender`(JVM)ではなく、**Node.js経由の `wasm-webcomponent`/`actor-host.js`** を配備経路に選定した(全ノードにJVM不存在のため)。asherへ `brew install node`(~92MB、`brew uninstall node` で可逆)し、新規スクリプト `wasm/verify_node.mjs` を配置して6シナリオ(auto-ok / HOLD×3 / escalate×2)すべてを実行、ローカル実行結果と完全一致することを確認した。llm-infer はどちらの環境でもAPIキー未設定のためfail-closed(`ESCALATE:*:llm-unavailable`)。**これがこのモノレポで初めての、実fleetノード上での `.kotoba`→wasmコンパイル成果物の実行確認である。**

検証後、一時ファイル(`/tmp/kotoba-wasm-poc`)は削除。Node.js自体は今後の同種actorデプロイの土台として残置した。

murakumoリポジトリ自体(`bin/BUILD.edn`・`deploy/plan.cljc` 等のRust/WIT依存コード)は**今回変更していない**。理由: 同日付ADR-2607072400(kaisha)が murakumo fleet の現行Rustベース `kotoba-server` 常駐プロセスへの実デプロイを決定しており、並行して murakumo リポの中核コードを書き換えることは他ワークストリームとの衝突リスクが高いと判断したため。代わりに、murakumo の既存デプロイパイプラインを一切変更せずに追加実行できる、副作用の小さい経路(SSH+Node.js直接実行)を選んだ。

## Consequences

- 実装済み: `llm/infer` capability(JVM/Node両対応)、`.kotoba`決定モジュール1本、実fleetノードでの実行確認1件。
- cloud-itonami本体のtest coverageも並行して向上(`plm_export.cljc`/`local_app.cljc`へのテスト追加、350→361 tests / 2719→2773 assertions、PR gftdcojp/cloud-itonami#36)。
- **未実施・フォローアップ**:
  - 実Anthropic APIキーを用いた実LLM応答の検証(今回はfail-closed配線の検証のみで十分と判断)。
  - murakumoの正式なmesh/auction配布経路(`nbb murakumo deploy`)の修復。RustのWIT参照が現行`kotoba-lang/kotoba`に存在しないため壊れたままであり、ADR-2607072400(kaisha)が依存する現行Rust実装との調整を含め、別スコープの課題として残す。
  - `.kotoba`コンパイラの`and`/`or`/`when`未実装バグの本体側修正。
  - ISIC以外(ISO3166/COFOG/UNSPSC)のimplemented化、および今回のPoCパターンを他actorへ横展開すること。
  - wasm-webcomponent側`http-post`の非同期未対応は今回も未解消(ブラウザ文脈でのみ真に困難。Node文脈は本ADRのllm-infer同様の手法で解決可能と推測されるが未着手)。

## References

`orgs/kotoba-lang/kotoba-core-contracts`(PR#4）、`orgs/kotoba-lang/kototama`（PR#25）、`orgs/cloud-itonami/cloud-itonami-isic-6511`(PR#1, PR#2, `wasm/README.md`)、`orgs/kotoba-lang/wasm-webcomponent`(PR#4)、`orgs/gftdcojp/cloud-itonami`(PR#36)、ADR-2607062330(kototama.tender)、ADR-2607062400(wasm-webcomponent)、ADR-2607072400(kaisha/murakumo)。
