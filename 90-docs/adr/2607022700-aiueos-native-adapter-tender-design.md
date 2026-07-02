# ADR-2607022700: aiueos native adapter（kototama tender）を、旧 Rust CLI の機能履歴を仕様として再設計する — 意思決定は常に CLJC に問い合わせ、Rust は二度と capability を判定しない

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ADR-2607022500 までで aiueos の意味論/決定ロジック（policy/graph/surface/manifest/
signing/audit/topic/broker の判定部分/cli 契約）は CLJC（`aiueos-cljc-contract`）に
完全移行した。一方、旧 Rust crate が担っていた **実行系**（wasmtime hosting、CLI
バイナリ本体、virtio driver、computer-use backing bridge）は commit `961dee4` と
`cade551` で削除されたまま、Rust でも CLJC でも一切再実装されていない
（`90-docs/adr/2607022500` の Consequences で確認済み）。

削除直前の Rust 実装を `restore/rust-native-reference` worktree に復元し
（`961dee4^` = commit `79ad05e`、`cargo build --lib` 成功を確認）、git log で
ファイル単位の開発履歴を調査した結果:

- **`bin/aiueos.rs`（CLI バイナリ）は25コミットにわたり ADR 番号を明記しながら
  機能ごとに丁寧に積み上げられている**（Phase-0 → rename → device binding →
  verify/inspect/run/up の `--edn` 対応 → structured EDN errors → hash → periodic
  control loop(`up --rounds`) → audit query → `inspect --dot` → topic 隔離表示 →
  `up --dry-run` → sign(ADR-0003) → admit(ADR-0004) → kotoba-clj 配線）。usage
  docstring は 13 コマンド（`verify inspect run admit image vm compile check hash
  sign audit` + `up`）を正確に記述しており、これは既に `aiueos.cli.edn`
  （このセッションで作成済み）が捕捉している 13 コマンドと **1:1 で一致**する。
- **`virtio.rs`（4533行）は commit `9217435`（"raise aiueos vm and kotoba
  coverage"）で丸ごと1回で追加され、以後3日後の削除まで一度も手が入っていない**。
  他のファイル（broker.rs 17コミット、host.rs 16コミット）と比べて実運用で
  鍛えられた形跡が無く、信頼度が低い。
- Cargo.toml の feature flag 構成（`wasm-runtime` `signing` `computer-backing`
  `kototama`）は、何が「常にビルドされる意味論core」で何が「実行系オプトイン」
  だったかを正確に切り分けている。
- `examples/computer/backing/` は commit `cade551` で `surface.mjs` だけが
  削除され、`Dockerfile`/`run.sh`/`README.md` が**壊れた参照として現存**して
  いる（ADR-2607022500 で確認済み）。

この履歴を仕様として使い、native adapter（ADR-2607022400 で「kototama = Solo5
tender パターン」と決めた native runtime）を設計する。

## Decision

### 1. CLI 機能履歴を native adapter の機能仕様として採用する

`bin/aiueos.rs` の25コミットの feature 履歴 + usage docstring を、native adapter
が最終的に満たすべき**機能一覧の正典**とする。これは既に `aiueos.cli.edn` の
13コマンド × `:coverage` 分類（`:full` 4 / `:decision-only` 3 / `:adapter-only` 6）
と1:1で対応しているため、新たに仕様を書き起こす必要はない — **`aiueos.cli.edn`
が機能仕様、旧 Rust の commit 履歴がその設計根拠・変更理由の記録**という関係で
再利用する。

### 2. native adapter は capability を二度と判定しない — 常に CLJC に問い合わせる

旧 Rust 実装は `policy::verify_component`/`Broker::verify_one` を **Rust 自身の中で**
実行していた。これは ADR-2607022200/2607022500 の「意味論は CLJC が正本」という
原則の後付け違反になる（native adapter を書く時点で、うっかり判定ロジックを
Rust に再実装してしまうリスクが最大化する瞬間だからこそ、明文化する）。

**native adapter は capability の grant/deny を一切自分で計算しない。** 代わりに、
`aiueos.broker`/`aiueos.cli` を呼び出す **decision subprocess**（`bb` = babashka
経由、EDN を stdin/stdout でやり取りする newline-delimited プロトコル）に必ず
問い合わせる。これは新規パターンではなく、**同じリポジトリに既に実例がある**:
`examples/computer/backing/surface.mjs`（Rust 側から newline-JSON で外部プロセスに
委譲する computer-use backing bridge、ADR-0007）と同じ「gated action を
プロセス境界の外側に委譲する」形。それを capability 判定そのものにも適用する。

```
Rust tender (native adapter)              bb decision subprocess (CLJC authority)
  argv 解析、file I/O                        aiueos.cli/dispatch
  manifest/policy を EDN として読む    -->    aiueos.broker/verify-one 等を実行
                                       <--    policy-decision EDN (grant/deny + caps)
  :grant なら実行（wasmtime hosting）
  :deny なら実行せず終了
```

`bb` は GraalVM native-image でビルドされた実行体なので起動が速く、
per-invocation で shell out しても CLI ツールとして許容できるレイテンシに収まる
（後日、長命プロセス化してレイテンシを詰めるのは最適化として later、今は
シンプルな per-invocation を V1 とする）。

### 3. モジュール計画: 何を復元し、何を書き直し、何を二度と Rust に戻さないか

| 旧 Rust モジュール | 扱い |
|---|---|
| `policy.rs` `graph.rs` `manifest.rs` `broker.rs`(判定部分) `signing.rs`(検証部分) `audit.rs`(pure shape) `topic.rs` `surface.rs` | **二度と Rust に実装しない**。全て `aiueos-cljc-contract` が正本。native adapter は decision subprocess 経由でのみ触る |
| `host.rs`（1879行、16コミット） | **復元・改修**。ただし内部で `policy::verify_component` を直接呼んでいた箇所は decision subprocess 呼び出しに置き換える。wasmtime Linker/host-call 実装自体（fuel/memory 執行、capability gate の実効チェック＝decision subprocess が返した capability set との突き合わせ）は温存 |
| `runtime.rs`（143行、5コミット） | **復元・改修**。wasmtime engine 初期化、sha256_hex はそのまま使える。`kotoba_policy_from_caps` は decision subprocess の応答を受けて host.rs に渡す形に変更 |
| `bin/aiueos.rs`（1999行、25コミット） | **復元・改修**。argv 解析は温存（`aiueos.cli.edn` の `:full`/`:decision-only` コマンドは decision subprocess へ委譲するよう書き換え、`:adapter-only` コマンドは元のロジックをほぼそのまま使う） |
| `signing.rs`（87行）のうち **署名生成**（`aiueos sign`、秘密鍵操作） | **復元**。`aiueos.signing`（CLJC）は検証のみを持つ設計（namespace docstring に明記済み）なので、鍵カストディを要する署名生成は native/adapter 側の責務のまま |
| `backing.rs`（155行、2コミット）+ `examples/computer/backing/surface.mjs` | **復元して修復**。`cade551` で壊れた `Dockerfile`/`run.sh`/`README.md` の参照を直す、最も着手コストが低い具体的な follow-up |
| `virtio.rs`（4533行、**2コミットのみ**） | **信頼度を下げて扱う。verbatim 再利用しない。** virtio 仕様から改めて設計し、`aiueos.policy` の kernel-caps（`mmio/map` `dma/map` `irq/subscribe` `pci/config`）と Solo5 tender の application-manifest パターン（ADR-2607022400）に合わせて書き直す。旧コードは語彙・構造の参考程度に留める |

### 4. 依存関係の一方向性を native adapter にも適用する

ADR-kotoba-shell-aiueos-safety-clj.md の `kotoba-shell → aiueos → kototama/kotoba-clj
→ kotoba-edn` という一方向依存を、native adapter（kototama の実体）にも適用する:
native adapter（Rust）は `aiueos-cljc-contract` の CLJC 関数を**プロセス境界越しに
呼ぶだけ**で、逆方向の依存（CLJC が Rust の型やロジックに依存する）は発生しない。
decision subprocess の入出力は EDN のみで、Rust 側の構造体を経由しない。

## Consequences

- (+) native adapter を書く作業そのものが、うっかり capability 判定ロジックを
  Rust に巻き戻してしまうリスクを構造的に防げる（decision subprocess を挟む
  という制約が、レビューなしでも自然に強制される）。
- (+) `aiueos.cli.edn` の13コマンド×coverage分類がそのまま実装のチェックリストに
  なる。旧25コミットの feature 履歴は「なぜその挙動なのか」の設計根拠として
  参照できる。
- (+) `examples/computer/backing/` の壊れた参照という具体的な小さいバグが、
  この ADR のスコープ内で拾われた。
- (+) virtio.rs の信頼度が低いという評価が記録され、将来「動いていたコードだから
  そのまま使おう」という早合点を防げる。
- (−) 本 ADR は**設計**であり、decision subprocess の実装（`bb` スクリプト）・
  host.rs/runtime.rs/bin/aiueos.rs の改修・virtio.rs の書き直し・backing.rs の
  修復は全て follow-up。復元 worktree は `cargo build --lib` が通る状態で
  temporary に保持しているのみで、まだ decision subprocess 呼び出しへの
  置き換えは行っていない。
- (−) per-invocation で `bb` を shell out する V1 設計はレイテンシ面で
  最適ではない（起動コストが CLI 用途には許容範囲でも、`up --rounds N` の
  ような周期実行には向かない可能性がある）。長命プロセス化は later の
  最適化として明示的に先送りした。
- (−) `signer.rs` の署名生成（秘密鍵操作）を native 側に残す判断は、鍵カストディを
  CLJC の pure data 層に持ち込まないという既存方針（`aiueos.signing`
  namespace docstring）に沿ったものだが、鍵管理の実装自体（生成・保管・
  ローテーション操作）はまだ未設計のまま。

## References

- ADR-2607022500（signer revocation実装、Consequences で「実行系は空白のまま」と
  明記した対象）
- ADR-2607022400（unikernel調査、kototama = Solo5 tender パターンの決定）
- ADR-2607022200（三層アーキテクチャ、CLJC意味論の正本原則）
- `com-junkawasaki/orgs/kotoba-lang/aiueos-cljc-contract/resources/aiueos/cli.edn`
  （13コマンド×coverage分類、本ADRの機能仕様）
- `com-junkawasaki/orgs/kotoba-lang/aiueos-cljc-contract/src/aiueos/cli.cljc`
- restore worktree: `restore/rust-native-reference` ブランチ
  （`961dee4^` = commit `79ad05e`、cargo build 確認済み）
- `orgs/kotoba-lang/aiueos` commit `9217435`（virtio.rs 一括追加、"raise coverage"）
- `orgs/kotoba-lang/aiueos` commit `cade551`（surface.mjs 削除、backing破損の原因）
- `examples/computer/backing/surface.mjs` の newline-JSON プロトコル
  （decision subprocess の EDN over stdio 設計の先例）
