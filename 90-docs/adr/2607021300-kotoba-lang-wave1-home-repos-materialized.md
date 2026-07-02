# ADR-2607021300: Wave-1 home 46 repo の実体化と west.yml stale entry 3 件の除去

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ADR-2607021230 の監査 follow-up として kotoba-lang org の GitHub 実体を全数照合
（west.yml の kotoba-lang entry 313 件 vs org リポ 272 件 + rename redirect 解決）
した結果:

- **49 entry のリポが GitHub に存在しない**（pin も全て解決不能 = `west update`
  不能）。うち 46 は clj-wgsl migration Phase 4（ADR-2607010930）で repos.edn に
  登録された「~58 kami-engine port の home」。pin の SHA は Wave-1 セッションの
  agent worktree 内 scaffold commit とみられ、**worktree 掃除で消失**（ローカル
  checkout `orgs/kotoba-lang/<n>` は全て空 git・remote 未設定を実測確認）。
- 残り 3 件は repos.edn（SSoT）にもう存在しない stale entry:
  `verify`（6246e89 で model-checking へ retire 済み）/ `kotoba-lang-svgraph` /
  `kotoba-lang-kotodama`（svgraph / kotodama の旧名）。
- `kami-webgpu` は rename redirect（→ kotoba-lang/webgpu）で pin 解決可 = 正常。
- crypto / spice の「regen 前 pin が解決不能」（ADR-2607021230 記載）の根本原因も
  同型と判明: 両リポの GitHub created_at は 2026-07-01（Wave-1 当日）。旧 pin は
  リポ実体化前の worktree ローカル commit で、その後 push された履歴に含まれず消失。

## Decision

1. **46 home repo を実体化**: `orgs/kotoba-lang/repos` API で private + auto_init
   （initial commit のみ、description に ADR-2607010930 の reservation を明記）で
   作成し、west.yml の pin を **実在する initial commit SHA へ張り替え**る。
   repos.edn（SSoT）は変更なし（既に登録済み。Wave-2 port はこの home に push して
   pin を前進させる）。
2. **stale 3 entry（verify / kotoba-lang-svgraph / kotoba-lang-kotodama）を
   west.yml から除去**（repos.edn に無い = 生成器を通せば消える entry の手動反映。
   diff は当該 entry のみ）。
3. crypto / spice は現 pin が有効のため変更なし（根本原因を本 ADR で記録し
   ADR-2607021230 の未解決事項をクローズ）。

## Consequences

- (+) kotoba-lang の west entry 全 313 件が「リポ実在 + pin 到達可能」になり、
  `west update` の全面回復。Wave-2 port の着地先（home）が実体として揃う。
- (+) crypto/spice の pre pin 欠落の根本原因が特定され、監査事項がクローズ。
- (−) 46 home は README のみの scaffold（中身は Wave-2 で届く）。空 repo が org に
  46 個並ぶ（description で reservation と分かるようにした）。
- (−) Wave-1 worktree で作られた scaffold commit の内容自体は復元不能（消失）。
  Wave-2 は ADR-2607010930 の手順で作り直す。

## References

- ADR-2607021230（Wave-1 pin 退行監査）/ ADR-2607010930（clj-wgsl migration Phase 4）
- `6246e89`（verify → model-checking retire）
- 本 ADR とペアの .edn
