# ADR-2607021700: west vs. 子リポ内 git submodule — 現状調査と移行方針

**Status**: proposed — `net-kotobase` pin は修正済み（2026-07-02）。west 化の
移行判断（◎/○/△/✕ の他4件）はオーナー選定待ち
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

`git pull` 実行後（fast-forward 1 commit + `west update --fetch smart` で
542 west project 中 495 成功）、オーナーから「今は west を使っているが
submodule も使っているのか」と問われ調査した。

**superproject（`com-junkawasaki/root`）自体は west のみ。** ルートに
`.gitmodules` は存在しない（`ls .gitmodules` で確認済み）。CLAUDE.md の
「plain な submodule は廃止済み」の通り。

**ただし west が管理する子リポ（`orgs/<org>/<repo>`）の"内部"には、
それぞれ独自の git submodule を使っているものが 5 つ存在する**
（west とは無関係、各リポ自身が持つ依存管理）:

| 子リポ | submodule | 用途 |
|---|---|---|
| `orgs/gftdcojp/net-kotobase` | `etzhayyim/kotoba`（~38GB） | 巨大リポの遅延取得（`.gitmodules` に手動 clone 手順コメント） |
| `orgs/kotoba-lang/kami-engine` | `etzhayyim/kami-engine-sdk` | 単純な他リポ pin |
| `orgs/com-junkawasaki/ghosthacker` | `zen-editor` 系（ローカル絶対パス） | 開発者マシン限定の一時配線 |
| `orgs/gftdcojp/ai-gftd-apps-gftdcojp` | forge-std / solady / smart-wallet / openzeppelin-contracts-v4 | Foundry（スマートコントラクト）標準の依存管理 |
| `orgs/etzhayyim/root` | forge-std / account-abstraction / openzeppelin-contracts（複数） + `spirit-in-physics` + `baien/datasets`（DataLad, `ignore=dirty`） | Foundry 依存 + このリポと同型の DataLad/B2 パターン |

同じ `west update --fetch smart` 実行中に、上記とは別の 2 件の既存不整合も
表面化した（本 ADR の主題ではないが記録として残す）:

- **`kotoba-lang/*` の 47 manifest entry が指す GitHub リポが存在しない**
  （`kotoba-lang/toml` 等をサンプルで `gh api` 確認 → 404）。scaffold 予定
  だったが子リポ作成前に manifest だけ先行登録されたと見られる。
- **`net-kotobase` の `etzhayyim/kotoba` submodule pin
  (`b214b15888f773c1af415898cd0502c16b495561`) が upstream に存在しない**
  （`gh api repos/etzhayyim/kotoba/commits/<sha>` → 404 "No commit found"）。
  CLAUDE.md の「`upload-pack: not our ref` は force-push の確度が高いサイン」
  に該当。west 管轄外（net-kotobase 自身の submodule）のため west 側では
  検知されない。

## Decision

**west の nested workspace 機構（cwd から上方向に一番近い `.west/` を探す
= このリポ自身が既に採用しているディレクトリスコープ方式）を使えば、
子リポは「自分専用の `.west/` + 小さな manifest」を持つことで west 管理に
移行できる。** ただし対象ごとに適否が分かれるため、一律移行はしない:

- **△ 訂正: `net-kotobase`→`etzhayyim/kotoba` は移行不要（当初の「◎移行推奨」を撤回）**。
  net-kotobase 自身の `docs/SUBMODULES.md` / `docs/adr/2606090002-kotobase-net-infrastructure-sovereignty.md`
  を読むと、これは抽出漏れの技術的負債ではなく **Consensys-pattern
  （親組織 ADR-2606011400 継承: etzhayyim = product, gftd = infra vendor）**
  に基づく意図的な supply-chain 参照だった。`kotoba/` は「vendor した runtime
  code ではなく upstream source（かつ ADR reference）」と明記され、gitlink
  変更は当該リポ自身の ADR/`deps.toml`/`CHANGELOG.md` レビュー対象と既に
  ガバナンスされている。west 化するとこの既存ガバナンスと二重管理になるため
  見送り。**残る問題は pin 切れそのものであり、下記「Resolution」で修正済み。**
- **◎ 移行推奨**: `etzhayyim/root`→`spirit-in-physics`/`baien/datasets`。
  このリポが既に通った DataLad/B2 移行と完全に同型（同一著者・同一 ADR 体系）
  で、単に未移行なだけに見える。
- **○ 移行可**: `kami-engine`→`kami-engine-sdk`（単純 pin、west の素の
  用途どおり）。
- **△ 見送り**: `ghosthacker`→`zen-editor`系（ローカル絶対パス submodule、
  west 化する実益が薄い）。
- **✕ 非推奨**: `ai-gftd-apps-gftdcojp` / `etzhayyim/root` の Foundry
  submodule（forge-std 等）。`forge install`/`forge update` が
  `.gitmodules` を直接読み書きする Foundry 標準経路であり、west に
  置き換えると Foundry ツールチェーンと二重管理になり壊れる。

**実施は各子リポ自身の中の作業**（`.gitmodules` 撤去 + `manifest/repos.edn`
相当 + 生成スクリプトの持ち込み + コミット）であり、superproject 側の
変更だけでは完結しない。オーナーに `AskUserQuestion` で対象選定を照会したが
無応答だったため、本 ADR は**調査結果と移行方針の記録のみ**とし、実施は
オーナーが対象を指定してから着手する。

## Resolution（`net-kotobase` pin、2026-07-02 実施）

オーナー承認（「ok, do it」）を得て、net-kotobase 側のガバナンス
（`docs/SUBMODULES.md` の「gitlink 変更は supply-chain change」）に従い実施:

- `orgs/gftdcojp/net-kotobase` を worktree 分離（共有 west checkout は直接
  編集しない）し、壊れていた pin `b214b158`（2026-06-23 `3e2ab9f` が設定、
  upstream で 404）を、直前の「Bump kotoba submodule to **verified main**」
  commit `07cee448`（2026-06-12、`467b57d`）に revert。`07cee448` は
  upstream に現存し、現在の `etzhayyim/kotoba` main の直系祖先であることを
  `gh api .../compare` で確認済み（ahead_by 282, 0 diverged）。未検証の
  最新 HEAD には進めていない。
- `CHANGELOG.md` に経緯を記録し、`fix/kotoba-submodule-pin` ブランチ →
  `gh api .../merges` でサーバ側 merge（commit `699384fc`, net-kotobase main）。
- superproject の `manifest/west.yml` は GitHub API single-entry commit で
  `net-kotobase` の revision を `d5388a3` → `699384fc` に前進（commit `b94f0fd1`,
  diff は当該 1 行のみ）。
- 共有 west checkout（`orgs/gftdcojp/net-kotobase`）はローカルに残っていた
  壊れた pin の submodule 半端 checkout（`kotoba/` が古い commit で dirty）
  が `west update` の checkout をブロックしていたため `git submodule deinit`
  で解消してから `west update --fetch smart net-kotobase` を再実行し、
  `kotoba/` が `07cee448` で正常に checkout されることを確認。

未実施のまま残るのは 47 件の dead `kotoba-lang` manifest entry と、
`etzhayyim/root`→DataLad系の west 移行判断のみ。

## Consequences

- (+) west と子リポ内 submodule の役割分担が明文化され、以後の「これも
  west 化すべきか」判断の参照点になる。net-kotobase については「vendor
  submodule は当該リポ自身のガバナンスに従う」が結論。
- (+) 47 件の dead `kotoba-lang` manifest entry が記録され、見落とされない
  （未対応）。
- (+) `net-kotobase` の pin 切れは修正済み。`west update` は再び全 542
  project を通せる状態に戻った。

## References

- `CLAUDE.md` §リポジトリ構成（`.gitmodules` 廃止の原則）、§大容量バイナリの扱い
  （DataLad/B2 + `group-filter` パターン）
- `90-docs/adr/2606272237-west-manifest-api-single-entry.md`
- `90-docs/adr/2607011345-agent-worktree-west-topdir-fix.md`
  （`.west/` のディレクトリスコープ探索 = 本 ADR の nested workspace 提案の根拠）
- `orgs/gftdcojp/net-kotobase/docs/SUBMODULES.md`,
  `orgs/gftdcojp/net-kotobase/docs/adr/2606090002-kotobase-net-infrastructure-sovereignty.md`
  （Consensys-pattern: etzhayyim = product, gftd = infra vendor — net-kotobase
  の submodule 参照が意図的である根拠）
- 本 ADR とペアの .edn
