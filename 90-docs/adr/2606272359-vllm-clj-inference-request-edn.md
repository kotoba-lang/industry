---
id: adr-2606272359-vllm-clj-inference-request-edn
title: "ADR-2606272359: vllm-clj — vLLM(OpenAI 互換)推論リクエストを EDN データとして表し、wire(string-keyed map)へ純レンダリング/応答正規化する再利用ライブラリ。request + sampling + wire + validate + host-injectable transport ports(ITransport/IStreamTransport)。third-party dep ゼロの portable .cljc"
status: proposed
doc_type: adr
topic: library-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - vLLM(OpenAI 互換)推論リクエストを Clojure で「データとして」表現する正準的な :vllm/* 形式の定義
  - vllm-clj の責務境界(request/sampling/wire/validate)と host-injected transport(ITransport/IStreamTransport)の設計
  - sampling パラメータ(OpenAI surface + vLLM 拡張 top_k/min_p/repetition_penalty/best_of/guided_*)の :vllm/* ↔ wire field 対応と範囲検査
  - 「JSON テキストを組み立てない」— wire は string-keyed map で授受し、HTTP+JSON (de)serialize は host transport に委ねる方針
  - 成果物の3-org 配置(共通=com-junkawasaki / 公益=etzhayyim / 事業=gftdcojp)
related:
  - orgs/com-junkawasaki/vllm-clj                       # 本 ADR のライブラリ
  - orgs/com-junkawasaki/torch-clj                      # 姉妹(module graph as data)
  - orgs/com-junkawasaki/mcp-clj                        # 先例(host-injected transport / string-keyed wire)
  - orgs/com-junkawasaki/jsonlogic-clj                  # 先例(データ第一 / validate / ports 方式)
  - orgs/com-junkawasaki/langchain-clj                  # 利用側(model 呼び出しの実 transport を注入しうる)
supersedes: []
superseded_by: []
---

# ADR-2606272359: vllm-clj — vLLM 推論リクエストを EDN data として

**Status**: proposed（実装済み・テスト緑。実 HTTP transport を注入する actor/langchain 側は別 PR）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

LLM 推論呼び出し（model・messages/prompt・sampling・tools・stream）を「データとして配り、
生成・差分・ログ・Datomic/kotoba 格納し、複数 host で同じ wire を再現する」需要がある。既存の
クライアントは (1) 特定 HTTP ライブラリに密結合、(2) リクエストを opaque object に隠して
`assoc`/`diff` と噛み合わない、(3) JSON テキスト生成を内部に抱えて可搬性（CLJS/SCI）を損なう。
本リポの方針（portable `.cljc`・third-party dep ゼロ・host-injected ports — mcp-clj /
jsonlogic-clj の先例）に沿う、**推論リクエストを素のデータのまま表し、wire を string-keyed map
で授受してソケットは host に委ねる軽量ライブラリ**が無かった。

## Decision

`com-junkawasaki/vllm-clj` を新設する。**third-party 実行時依存ゼロ**、全 namespace `.cljc`
（JVM/CLJS/SCI）。**リクエストは `:vllm/*` namespaced key の map**、2 endpoint（chat
`/v1/chat/completions` / completion `/v1/completions`）。責務を分離する:

- **`vllm.request`** — データ構築。`chat`/`completion`、`system`/`user`/`assistant`/`tool`
  メッセージ、threadable な `with-sampling`/`with-base-url`/`with-stream`/`with-tools`/
  `add-message`。`endpoint-path`/`target-url`。network I/O はしない。
- **`vllm.sampling`** — sampling を data として。`defaults`、`:vllm/*` → wire field 対応
  （OpenAI: temperature/top_p/max_tokens/n/presence·frequency_penalty/stop/seed/logprobs ＋
  **vLLM 拡張**: top_k/min_p/repetition_penalty/best_of/guided_json·regex·choice）、`to-wire`
  （未知 key は drop）、`problems`（範囲・best_of≥n の純検査）。
- **`vllm.wire`** — `:vllm/*` ↔ OpenAI/vLLM JSON 形状の翻訳。**JSON テキストは作らず
  string-keyed Clojure map** で授受（host が JSON (de)serialize）。`render`（request →
  body map）、`parse-response`（応答 → 正規化 `:vllm/*`: choices/text/finish-reason/usage）、
  `parse-chunk`/`strip-sse`/`done-chunk?`（SSE streaming delta）。
- **`vllm.validate`** — 構造検証。`{:vllm/severity :vllm/code :vllm/field :vllm/msg}` の vector。
  model 必須・role 妥当・content 文字列・endpoint 既知 ＋ sampling 範囲（`sampling/problems`
  に委譲）。`valid?` は error 無しで真。
- **`vllm.ports`** — `ITransport`（`request!`: 1 往復、body は string-keyed map で host が
  serialize、応答 `:body` は parsed map）と `IStreamTransport`（`stream!`: **任意**の SSE）。
  `no-transport`（既定、I/O 拒否で throw）、`fn-transport`（plain fn から ITransport）。
- **`vllm.core`** — 上位 op。`complete`（validate → request-spec 構築 → transport 送信 → 応答
  正規化。error level 問題があれば送らず `{:vllm/error :validation …}`、非 2xx は
  `{:vllm/error :http …}`）、`chat`/`completion` の便宜、`stream`（IStreamTransport 越しに
  token delta を `on-delta` へ）。

## Rationale

- **データ第一**: リクエストが EDN データのまま。生成・差分・バージョニング・監査ログ・
  Datomic/kotoba 格納が自明。応答も正規化された plain EDN。
- **JSON テキストを作らない**: wire は string-keyed map で授受し、JSON (de)serialize と HTTP は
  host transport に委ねる（mcp-clj と同型）。これで third-party dep ゼロ・CLJS/SCI 可搬を保つ。
- **host-injected transport**: ソケットは host の HTTP client（clj-http/http-kit/hato/fetch）。
  kernel は純粋な変換＋orchestration に留まり、テストは fake transport を注入して end-to-end
  で回せる（langchain.db の `:db-api` 注入と同思想）。
- **vLLM 拡張を一級で**: top_k/min_p/repetition_penalty/best_of/guided_* を `:vllm/*` key として
  持ち、OpenAI 互換に留まらず vLLM の structured output まで data で表現する。
- **姉妹との整合**: mcp-clj（transport 分離）・jsonlogic-clj（validate/ports）・torch-clj と
  同じ key 規約・問題 map 構造・default-port 方式を踏襲する。

## Consequences

- 完全な OpenAI/vLLM API 網羅ではなく、chat/completion ＋ 主要 sampling ＋ streaming ＋ tools
  pass-through の中核に限定。未対応フィールドは pass-through か `ITransport` 拡張で補う。
- 実 HTTP/JSON を持たない（既定）。実呼び出しには host が `ITransport` を注入する必要があり、
  その client は本ライブラリの可搬性保証の外（host の選択）。
- multimodal content（画像/音声の content 配列）は content を素通しする設計で、専用ヘルパは
  別途。embeddings/rerank 等の別 endpoint は対象外（chat/completion に絞る）。

## Verification

`clojure -X:test` 緑（12 tests / 44 assertions）。request builder/threadable 修飾・
`target-url`/`endpoint-path`・sampling の wire 対応と範囲/best_of 検査・`render`(chat/
completion/stream)・`parse-response`(chat/completion/usage)・SSE delta/`strip-sse`/
`done-chunk?`・`validate`(model/messages/role/endpoint/sampling)・fake `ITransport` 越しの
end-to-end `complete`（happy path / validation 短絡 / 非 2xx error / no-transport throw）・
`IStreamTransport` 越しの token delta を確認。
