# ADR-2607022900: west.yml pin のサーバ側検証(存在 / main 到達性 / 前進)を生成・CI・hook の3層で強制する

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

2026-07-02、`west update` が 75 project で失敗していることが判明した。原因の分類:

1. **44 件: pin が上流にもローカルにも存在しない commit を指していた。**
   同日 17:39 の superproject commit `90852b86`「feat(manifest): register
   kami-articulated」が、1 project の登録のつもりで west.yml を **wholesale 再生成**
   して commit しており、その環境の子リポ群にあった **未 push のローカル HEAD** を
   そのまま pin 化していた(子リポの push 漏れ。当該 commit はその後どこからも
   取得不能)。`gen-west-manifest.bb` は「ローカル working HEAD で pin する」設計
   なので、未 push / 遅れた子リポがあると壊れた pin / pin 退行を黙って生成する
   (CLAUDE.md で「pin 退行の罠」として警告済みだったが、規律頼みで強制が無かった)。
2. **30 件: pin は正しいが fetch が一時失敗**(バッチ https fetch の rate limit 系)。
   再 fetch で解消する transient。
3. **別件: net-kotobase の submodule pin `b214b15`** が上流 branch の rewrite で消失
   (`upload-pack: not our ref`)。「rewrite されうる未 merge branch 上の commit を
   pin する」ことの危険の実例。

いずれも「**pin を書く側が、上流に存在し続ける保証のない SHA を west.yml に
書けてしまう**」ことが根本原因。また判定をローカル shallow の ancestry で行うと
偽陽性(`(forced update)` 表示・`unrelated histories`)と誤判定が混ざるため、
検証はサーバ側(GitHub API、full 履歴)で行う必要がある(CLAUDE.md「マージ /
ancestry 判定」節と同じ原則)。

## Decision

**pin 検証スクリプト `scripts/verify-west-pins.bb` を単一の正とし、生成器・CI・
hook の3層から同じ検証を強制する。**

検証ルール(baseline との diff で revision が変わった / 新規の entry のみ対象):

1. **存在**: pin が上流 repo に存在する(= push 済み)。未 push のローカル HEAD の
   pin 化を機械的に禁止する。
2. **default branch 到達性**: `compare <default>...<pin>` が `identical|behind`。
   rewrite されうる未 merge branch 上の commit(net-kotobase 型)を禁止する。
3. **前進**: 旧 pin が上流に存在するなら `compare <旧>...<新>` が `ahead`。
   `behind`(静かな pin 退行)/ `diverged` を弾く。旧 pin 自体が消失している場合は
   「壊れた pin の修復」とみなし WARN で通す(修復をブロックしない)。

判定はすべて GitHub API。検証**不能**(private repo が読めない・API 障害)は
WARN で素通し(fail-open)、検証**失敗**のみブロックする。緊急スキップは
`--no-verify-remote` / `WEST_PIN_VERIFY_SKIP=1`(使用時は理由を commit に残す)。

強制の3層:

- **生成器** `gen-west-manifest.bb`: 生成時に自動検証、失敗したら west.yml を
  書かない。併せて `--entry <name>` を追加し、登録 / rename / pin 前進は当該
  entry のみの最小 diff を生成する(**wholesale 再生成 commit の禁止**。
  `:manifest-workflow :never :wholesale-regen-commit`)。
- **CI** `.github/workflows/west-pin-verify.yml`: PR と push to main で検証。
  API single-entry commit は pre-merge で止められないため、main 破損は即 issue
  起票で検知を数分に短縮する。
- **hook** `.claude/hooks/west-pin-verify-guard.bb`(PreToolUse/Bash): `git push`
  (HEAD の west.yml が origin/main と異なる時)と `gh api -X PUT
  contents/manifest/west.yml`(API single-entry 経路)の両方で push 前に検証する。

## Consequences

- 未 push HEAD の pin 化・pin 退行・非 main branch pin は、生成時 / push 時 /
  main 着地時の3箇所いずれかで機械的に止まる。今回の 44+1 件はすべて防げた。
- 生成器が数 entry の変更ごとに数回の API call を行う(1 entry ≈ 2-3 call)。
  wholesale 再生成の検証は entry 数に比例して重いが、それは正に精査すべきケース。
- CI の `github.token` では private org(gftdcojp / com-junkawasaki)の子リポが
  見えず WARN 素通しになる。厳密化するには org read PAT を
  `WEST_PIN_VERIFY_TOKEN` secret に設定する(follow-up)。
- 既存の壊れた 44 pin の修復(上流 main HEAD への一括前進)は本 ADR の範囲外の
  運用作業として別途行う(検証ルール3が「旧 pin 消失からの修復」を許すので、
  修復 commit はこのガードを通過できる)。

## References

- CLAUDE.md「## Git operations」west.yml 節 / 「マージ / ancestry 判定」節
- `manifest/repos.edn` `:manifest-workflow :pin-verification`
- ADR-2606272237(API single-entry workflow)/ ADR-2606302100(heavy のみ shallow)
- 実事故: superproject `90852b86`(44 pin 破損)、net-kotobase `b214b15`(branch rewrite)
