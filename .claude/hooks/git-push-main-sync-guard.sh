#!/usr/bin/env bash
# PreToolUse(Bash) ガード: git push 前に対象リポが origin/main（既定ブランチ）より
# 遅れていれば push をブロックし、先に同期するよう指示する。
#
# 方針 (CLAUDE.md): 常に main と同期し、乖離を作らない。
# フック自体は破壊的な自動マージをしない（deny + 指示のみ）。
# fail-open: 判定途中のあらゆる失敗時は push を許可する（誤ブロック防止）。

input=$(cat 2>/dev/null) || exit 0
cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // ""' 2>/dev/null) || exit 0

# git push を含むコマンドのみ対象（compound `cd .. && git push` も拾う）
case "$cmd" in
  *"git push"*) ;;
  *) exit 0 ;;
esac
# --dry-run は実際の push ではないので素通り
case "$cmd" in *"--dry-run"*) exit 0 ;; esac

# 対象リポ dir: コマンド先頭付近の `cd <path>` を尊重、無ければ cwd
dir="."
cdpath=$(printf '%s' "$cmd" | sed -n 's/.*cd[[:space:]]\{1,\}\([^[:space:];&|"'"'"']\{1,\}\).*/\1/p' | head -1)
[ -n "$cdpath" ] && dir="$cdpath"

top=$(git -C "$dir" rev-parse --show-toplevel 2>/dev/null) || exit 0
[ -z "$top" ] && exit 0

# 比較先: origin/main があれば優先、無ければ origin/HEAD の指す既定ブランチ
ref="origin/main"
if ! git -C "$top" rev-parse --verify -q "$ref" >/dev/null 2>&1; then
  ref=$(git -C "$top" symbolic-ref -q refs/remotes/origin/HEAD 2>/dev/null | sed 's#^refs/remotes/##')
  [ -z "$ref" ] && exit 0
fi
branch=${ref#origin/}

# 比較ブランチだけ fetch（全体 fetch を避け高速化, best-effort）
git -C "$top" fetch -q origin "$branch" 2>/dev/null

behind=$(git -C "$top" rev-list --count "HEAD..$ref" 2>/dev/null || echo 0)
case "$behind" in ''|*[!0-9]*) behind=0 ;; esac

if [ "$behind" -gt 0 ]; then
  reason="$top is behind $ref by ${behind} commits. Sync $ref before pushing, then retry. Run: git fetch origin && git merge --ff-only $ref (if FF fails, merge or rebase to resolve divergence). Policy (CLAUDE.md): always stay synced with main, never create divergence."
  jq -nc --arg r "$reason" '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:$r}}'
fi
exit 0
