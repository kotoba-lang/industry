# ADR-2607152322: kotoba-lang/com-backblaze-secure — Backblaze B2 向け secure MCP server

**Status**: accepted
**Date**: 2026-07-15
**Scope**: `kotoba-lang` organization — new repo `com-backblaze-secure`

## Context

Backblaze B2 はこの workspace 全体で使われている（DataLad annex / `ai-gftd-cdn` /
`ai-gftd-quickwit` / `ai-gftd-nats` 等、`secrets-location-map` skill 記載）。鍵は
用途ごとにスコープされ、1Password/Keychain/kagi に分散管理されている
（アカウント全体を触れる Master Key は明示的に隔離済み）。

コミュニティ製の Backblaze MCP server（例: npm `backblaze-mcp`）は
`B2_APPLICATION_KEY_ID`/`B2_APPLICATION_KEY` を素の環境変数として受け取り、
bucket 単位の allowlist・operation 単位の capability gate・tool 応答からの
秘密値 redaction を一切持たない — 接続した MCP client（LLM 呼び出し側）に
その鍵が触れる bucket 全体への生アクセスをそのまま渡す設計になっている。

この危険性は仮説ではなく本セッション内で実際に踏んだ: `b2 account authorize`
をこの会話から手動実行した際、その JSON 出力（`applicationKey`/
`applicationKeyId` を含む）をそのまま redact せず会話に出力してしまい、
annex 用スコープ鍵を露出させた（オーナーは revoke を後日別途行う）。ツール
実行結果に秘密値がそのまま乗る設計は、人間が介在する会話でも MCP 経由の
自動呼び出しでも同じ事故を起こす。

## Decision

`kotoba-lang/com-backblaze-secure` を新設し、以下を非交渉の invariant として
実装する:

1. **redaction 必須**: `b2` CLI の JSON 出力を含む、いかなる値も MCP tool の
   戻り値・監査ログに書き込まれる前に、鍵/トークン系フィールド名の
   denylist（`applicationKey`/`applicationKeyId`/`accountAuthToken`/
   `secretAccessKey`/`accessKeyId` 等、再帰的にネストしたマップ/配列内も）を
   redact する関数を必ず通す。
2. **bucket + capability allowlist**: どの bucket にどの capability
   （`:list`/`:read`/`:write`/`:delete`）を許可するかは、gitignore された
   ローカル設定ファイルで明示登録した組み合わせだけ。設定にない
   bucket/capability への tool 呼び出しは、`b2` CLI を一切起動せずに拒否する。
   同梱する `config/example.edn` は `:list`/`:read` のみを既定にする
   （`:write`/`:delete` は opt-in）。
3. **既存の認証解決順序を再利用**: `scripts/b2-creds.cljs` /
   `manifest/repos.edn` の `:b2 :credentials`（`env → 1password → keychain`、
   参照先は `op://` パス / Keychain service 名）と同じ解決順序・参照形式を
   一般化して使う。新しい secret 保管方式は発明しない。
4. **append-only 監査ログ**: すべての tool 呼び出しを `{ts, tool, bucket,
   capability, outcome}`（秘密値を含まない）として JSONL に追記してから
   結果を返す。

### 実装方針

- MCP のマニフェスト/JSON-RPC dispatch は自前実装せず
  **`kotoba-lang/mcp`（mcp-clj、`orgs/kotoba-lang/org-anthropic-mcp`）を
  `:local/root` 依存として使う** — zero third-party runtime dep の portable
  `.cljc` kernel で、transport（stdio framing）だけをこの repo が
  host-injected port として実装する。
- 実行 runtime は **nbb**。root CLAUDE.md のランタイム優先順位
  （kotoba wasm > clojurewasm > cljs > nbb）のうち、stdio 常駐プロセスを
  現実に hosting できる経路は今のところ nbb しかない
  （`scripts/kotobase-ingest-cloud-itonami-lei.cljs` と同じ判断根拠）。
- B2 の実データ操作（list/upload/download/delete）は S3 署名等を
  再実装せず、**既にインストール済みの公式 `b2` CLI をサブプロセスとして
  呼ぶ**（Rust エンジン同様「既存インフラを消費するだけ」の原則）。

## Repo layout

```
com-backblaze-secure/
├── deps.edn                     # :local/root ../org-anthropic-mcp、clj test/lint alias
├── bin/com-backblaze-secure      # stdio 起動シェバン（kagi の bin/kagi と同型）
├── config/example.edn           # bucket allowlist の記入例（:list/:read のみ既定）
├── src/com_backblaze_secure/
│   ├── redact.cljc               # ★ 秘密値 denylist 再帰 redaction（最重要関数）
│   ├── credentials.cljc          # env→1password→keychain 解決（bucket 一般化）
│   ├── config.cljc               # allowlist 読み込み・capability 検証
│   ├── b2_cli.cljc               # `b2` サブプロセス呼び出し + redact 強制適用
│   └── server.cljs               # mcp-clj manifest 構築 + stdio transport
├── test/                          # redact() / capability gate の unit test
└── README.md                      # security model + MCP client 設定例
```

## Consequences

- この workspace から Claude/agent が Backblaze B2 を MCP 経由で操作する時は、
  コミュニティ製 `backblaze-mcp` ではなく `com-backblaze-secure` を優先する
  （bucket allowlist・capability gate・redaction が無い前者は、この ADR の
  invariant を満たさない）。
- 新しい bucket を MCP から触れるようにするには、ローカル設定への明示追加
  （人間の判断）が必要 — BMC/canvas-ledger の「登録は自動化がやらない大きな
  決定」と同じ原則。
- kotoba-lang に repo がもう1つ増える。維持コストの中心は
  `redact.cljc`/`config.cljc` のテストカバレッジ。

## Verification

- `test/`: 再帰ネスト（map/vector 混在）から denylist フィールドが
  漏れなく除去されることを assert。
- `test/`: allowlist に無い bucket/capability への呼び出しが、`b2` CLI
  サブプロセスを一切起動せず reject されることを assert。
- 手動スモーク: `tools/list` が設定で許可した capability だけを返す、
  `b2_list_buckets` の戻り値に `applicationKey`/`keyId` 系フィールドが
  一切含まれない。
