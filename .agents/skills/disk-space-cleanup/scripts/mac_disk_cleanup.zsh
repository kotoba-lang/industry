#!/bin/zsh

set -u

mode=${1:-audit}
case "$mode" in
  audit|apply|apply-extended) ;;
  *)
    print -u2 "usage: $0 [audit|apply|apply-extended]"
    exit 64
    ;;
esac

task_home=$(cd "$HOME" 2>/dev/null && pwd -P) || {
  print -u2 "cannot resolve user home"
  exit 1
}
case "$task_home" in
  /Users/*) ;;
  *)
    print -u2 "refusing unexpected home: $task_home"
    exit 1
    ;;
esac

typeset -a conservative_targets extended_targets active_dependency_targets
conservative_targets=(
  "$task_home/.npm/_cacache"
  "$task_home/.npm/_logs"
  "$task_home/.cache/kotoba-native"
  "$task_home/.cache/zig"
  "$task_home/.cache/gh"
  "$task_home/Library/Caches/Homebrew"
  "$task_home/Library/Caches/Mozilla.sccache"
  "$task_home/Library/Caches/ollama"
  "$task_home/Library/Caches/Google"
  "$task_home/Library/Caches/com.apple.python"
  "$task_home/Library/Caches/cursor-compile-cache"
  "$task_home/Library/Caches/node-gyp"
  "$task_home/Library/Caches/Codex"
  "$task_home/Library/Caches/go-build"
  "$task_home/Library/Caches/claude-cli-nodejs"
  "$task_home/Library/Caches/pip"
  "$task_home/Library/Caches/ms-playwright"
  "$task_home/Library/Caches/BytecodeAlliance.wasmtime"
  "$task_home/Library/Logs/com.openai.codex"
  "$task_home/Library/Logs/Claude"
  "$task_home/Library/Application Support/Cursor/CachedData"
  "$task_home/Library/Application Support/Google/Chrome/component_crx_cache"
  "$task_home/Library/Application Support/Google/Chrome/extensions_crx_cache"
  "$task_home/Library/Application Support/Microsoft Edge/component_crx_cache"
  "$task_home/Library/Application Support/Microsoft Edge/extensions_crx_cache"
  "$task_home/Library/Application Support/Microsoft/EdgeUpdater/crx_cache"
  "$task_home/Library/Application Support/Zed/node/cache"
  "$task_home/Library/Application Support/Claude/Cache"
  "$task_home/Library/Application Support/Claude/Code Cache"
  "$task_home/Library/Application Support/Code/CachedExtensionVSIXs"
  "$task_home/Library/Application Support/Google/DriveFS/Logs"
  "$task_home/Library/Application Support/Google/DriveFS/cef_cache"
)
extended_targets=(
  "$task_home/Library/Application Support/Google/Chrome/screen_ai"
  "$task_home/Library/Application Support/Google/Chrome/optimization_guide_model_store"
  "$task_home/Library/Application Support/zoom.us/asr/asr_model"
)
active_dependency_targets=(
  "$task_home/.m2/repository"
  "$task_home/.gradle/caches"
)

size_kib() {
  local target=$1
  [[ -e "$target" ]] || {
    print 0
    return
  }
  if (( $+commands[gdu] )); then
    gdu -sk "$target" 2>/dev/null | awk '{print $1}'
  else
    du -sk "$target" 2>/dev/null | awk '{print $1}'
  fi
}

audit_class() {
  local class=$1
  shift
  local target kib total=0
  print "[$class]"
  for target in "$@"; do
    kib=$(size_kib "$target")
    [[ -n "$kib" ]] || kib=0
    (( total += kib ))
    (( kib > 0 )) && printf '%10s KiB  %s\n' "$kib" "$target"
  done
  printf 'total: %s KiB\n' "$total"
}

delete_class() {
  local target delete_rc failures=0
  for target in "$@"; do
    [[ -e "$target" ]] || continue
    case "$target" in
      "$task_home"/*) ;;
      *)
        print -u2 "refusing out-of-home target: $target"
        (( failures += 1 ))
        continue
        ;;
    esac
    rm -rf -- "$target"
    delete_rc=$?
    if (( delete_rc != 0 )); then
      print -u2 "left in place (rm exit $delete_rc): $target"
      (( failures += 1 ))
    fi
  done
  return $(( failures == 0 ? 0 : 1 ))
}

print "[filesystem]"
df -h /System/Volumes/Data
audit_class conservative "${conservative_targets[@]}"
audit_class extended "${extended_targets[@]}"
audit_class active-dependency-preserved "${active_dependency_targets[@]}"
runtime_kib=$(size_kib "$task_home/.cache/codex-runtimes")
printf '[active-runtime-preserved]\n%10s KiB  %s\n' "$runtime_kib" "$task_home/.cache/codex-runtimes"
print "[preserved] sessions, archived sessions, memories, worktrees, repositories, git history, DataLad, databases, documents, browser profiles, cloud-sync data, Docker volumes/containers"

if [[ "$mode" == audit ]]; then
  exit 0
fi

delete_class "${conservative_targets[@]}" || true
if [[ "$mode" == apply-extended ]]; then
  delete_class "${extended_targets[@]}" || true
fi

print "[filesystem-after]"
df -h /System/Volumes/Data
