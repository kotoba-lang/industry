# ADR-2607051600: `gftdcojp/manimani` を `gftdcojp/local-manimani` へ改名

**Status**: accepted（実行完了）
**Date**: 2026-07-05
**Deciders**: Jun Kawasaki
**Scope**: `orgs/gftdcojp/manimani`

## Context

ADR-2607050600 で manimani portfolio は `manimani`（OSS、Linear ライクな
ローカル triage アプリ。iOS+Android、fastlane App Store パイプライン進行中）と
`cloud-manimani`（triage Decision Ledger の Cloudflare Worker API）の2系統に
収束済み。しかし `manimani` という名前単体では、この2つがペアであることも
役割の違いも伝わらない。

同種の対比は murakumo ファミリーで既に整理済み（ADR-2607041302）:
`cloud-murakumo`（Sora、クラウド GPU レンタル製品）に対して、手元の Mac-mini
fleet を束ねる側は `local-murakumo` と命名した。manimani も形は同じ —
`manimani` は端末上で動く**ローカルの** triage クライアント、`cloud-manimani`
は外部公開の**クラウド** API — なので、murakumo と同じ `local-`/`cloud-` の
対比命名に揃えるのが自然。

## Decision

`gftdcojp/manimani` を `gftdcojp/local-manimani` へ改名する。`cloud-manimani`
は現状維持（対比の相手側として意味が通っているため変更不要）。

## Execution

1. `gh repo rename local-manimani --repo gftdcojp/manimani`（GitHub 側改名）。
2. ローカル checkout を `orgs/gftdcojp/manimani` → `orgs/gftdcojp/local-manimani`
   へ移動し、`origin` remote URL を更新。
3. `manifest/repos.edn` の `:path-overrides` に
   `"orgs/gftdcojp/manimani" "orgs/gftdcojp/local-manimani"` を追加、
   `:heavy`（shallow clone-depth 1 対象）の該当パスを更新。
4. `nbb scripts/gen-west-manifest.cljs --entry local-manimani` で west.yml を
   最小 diff 再生成。旧 `manimani` エントリは `--entry` splice が「対象名の
   ブロックを差し替える/無ければ挿入する」だけで**改名で使われなくなった
   エントリを自動では退役させない**ため、手動で当該ブロックを削除した
   （ADR-2607050600 で見つかった `ai-gftd-manimani` の stale entry 残留と
   同根の generator 側の gap）。
5. アプリ内部（README タイトル `# manimani`、fastlane の Android
   `applicationId "jp.co.gftd.manimani.mobile"` 等）は**意図的に変更しない**。
   package name は Play Store / App Store Connect の提出継続性に直結するため、
   単純な repo 名変更のスコープを超える。local-murakumo でも README タイトル
   （今も `# cloud-murakumo` のまま）は同様に未着手で残っており、同じ扱いを踏襲。

## Operational note

pin 再生成の過程で、ローカル checkout の HEAD（`fe55550`、2026-06-28 の PR
マージ）が現行 `local-manimani` default branch と**共通祖先を持たない**
（GitHub API `compare` で 404 “No common ancestor”）ことが判明した。west.yml に
記録済みの pin（`93f76a48`）は現行 history から到達可能（`compare` で
`ahead_by 2, behind_by 0`）で無傷だったため、影響はローカル checkout が
過去のある時点（reflog 上、履歴巻き戻り以前）で止まったまま更新されずに
残っていただけと判断。ローカル checkout をこの正しい pin へ
checkout し直してから regen した（pin 自体は変更していない）。

## Consequences

- (+) `local-manimani` / `cloud-manimani` の対比が murakumo ファミリーと同じ
  命名文法になり、portfolio 全体の taxonomy が一貫する。
- (+) `manifest/repos.edn` / `west.yml` は改名後の実体と一致。
- (−) リポジトリ内部の自己言及（README タイトル等）は `manimani` のまま
  未更新（local-murakumo と同じ既知の先送り）。
- (−) `--entry` splice は改名で使われなくなったエントリを自動除去しない
  （`ai-gftd-manimani` の件と合わせて2件目。generator 側の恒久対応は
  follow-up）。

## Related

- ADR-2607041302: murakumo ファミリーの命名整理（同型の local-/cloud- 対比）。
- ADR-2607050600: manimani portfolio を manimani(OSS)+cloud-manimani の
  2系統へ収束（本 ADR の前提）。
