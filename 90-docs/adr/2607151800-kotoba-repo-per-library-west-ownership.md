# ADR-2607151800: Kotoba org は repo-per-library、aiueos は OS repo、west は統合面

**Status**: accepted
**Date**: 2026-07-15
**Scope**: `kotoba-lang` organization / root west workspace

## Context

`kotoba-lang/kotoba` の `os/aiueos/` に bare-metal kernel、UEFI loader、drivers と
boot evidence が蓄積した。一方、Kotoba の本来の境界は言語 apex であり、aiueos は
Kotoba compiler を使って作られる OS である。OS 実装を言語 repo に置き続けると、
compiler/runtime ABI と製品 OS の release lifecycle が結合する。

また `kotoba-lang` には `compiler`、`kotobase`、`kototama`、`browser` など既存の
独立 repo がある。これらを monorepo の source tree として統合するのではなく、
repo ごとの authority と release history を維持し、root の west manifest で再現可能な
workspace を構成する。

## Decision

- `kotoba-lang/kotoba` は言語仕様、標準 runtime ABI、freestanding contract の apex。
- `kotoba-lang/compiler` は compiler/codegen/packaging の authority。
- `kotoba-lang/aiueos` は bootloader、kernel、drivers、storage、desktop transport、
  boot/release artifacts と VM/実機 evidence の authority。
- `kotobase` と `kototama` は libraries/services、`browser` は application shell として
  独立 repo を維持する。
- repo 間統合は root の west pin で行う。library source を `kotoba` に vendor しない。
- 現在存在する remote だけを登録する。将来 library を分割する場合は、remote 作成後に
  `manifest/kotoba-workspace.edn` へ明示的に追加する。
- `kotoba/os/aiueos` の移転は、aiueos 側の CI/evidence が同等になった後に削除する
  two-step migration とし、この ADR 自体は履歴を失う一括移動を認めない。

machine-readable source of truth は `manifest/kotoba-workspace.edn` とする。
`scripts/gen-west-manifest.cljs` はそこに宣言した既存 component を west project の
登録集合へ union し、`manifest/west.yml` は従来どおり生成物とする。

## Dependency direction

```text
browser ───────> kotobase / kototama ───────> kotoba
aiueos ────────> compiler ───────────────────> kotoba
root west.yml: exact repository revisionsを統合
```

aiueos から browser/kotobase/kototama を利用する場合も、source ownership は移さず、
versioned protocol/ABI と west pin で結合する。

## Consequences

- OS と言語の release/CI authority が分離される。
- 各 library は独立した review、versioning、rollback を持つ。
- workspace の整合は west pin 更新が必要になり、cross-repo compatibility gate が必要。
- 既存 `kotoba/os/aiueos` は移行完了まで重複するため、同時変更を避け、aiueos を
  canonical とする cutover commit/ADR が別途必要。

## Verification

- `nbb scripts/gen-west-manifest.cljs --check`
- `manifest/west.yml` に `kotoba`、`compiler`、`aiueos`、`kotobase`、`kototama`、
  `browser` の既存 remote/revision/path が存在すること。
- 未作成 remote を manifest に追加していないこと。
