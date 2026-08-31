#!/bin/zsh

set -u

mode=${1:-overview}

usage() {
  print -u2 "usage: $0 overview | measure <absolute-path> [depth] [limit] | candidate <absolute-path>"
  exit 64
}

[[ "$(uname -s)" == "Darwin" ]] || {
  print -u2 "refusing non-macOS host"
  exit 69
}

data_volume=/System/Volumes/Data
[[ -d "$data_volume" ]] || {
  print -u2 "data volume unavailable: $data_volume"
  exit 69
}

size_kib() {
  local target=$1
  if (( $+commands[gdu] )); then
    gdu -x -sk -- "$target" 2>/dev/null | awk 'NR == 1 {print $1}'
  else
    du -x -sk "$target" 2>/dev/null | awk 'NR == 1 {print $1}'
  fi
}

canonical_dir() {
  local target=$1
  [[ "$target" == /* && -d "$target" ]] || return 1
  (cd -P -- "$target" 2>/dev/null && pwd -P)
}

case "$mode" in
  overview)
    print "schema=disk-space-audit-overview-v1"
    print "observed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    print "[capacity-kib]"
    df -k "$data_volume"
    print "[apfs-volumes]"
    diskutil apfs list 2>/dev/null | awk '
      /APFS Container Reference:/ || /Capacity Ceiling/ || /Capacity In Use By Volumes/ || /Capacity Not Allocated/ || /APFS Volume Disk/ || /Name:/ || /Capacity Consumed:/ {print}'
    print "[fixed-areas-to-measure]"
    for target in /Users /private /opt /Applications /Library /System/Volumes/VM /System/Volumes/Preboot; do
      [[ -d "$target" ]] && print "unmeasured\t$target"
    done
    print "note=measure one area per command; /Users may be slow in a west/DataLad workspace"
    ;;

  measure)
    (( $# >= 2 && $# <= 4 )) || usage
    target=$(canonical_dir "$2") || {
      print -u2 "refusing missing or non-absolute directory: $2"
      exit 66
    }
    depth=${3:-1}
    limit=${4:-40}
    [[ "$depth" == <0-6> ]] || {
      print -u2 "depth must be 0..6"
      exit 64
    }
    [[ "$limit" == <1-9>* && $limit -le 500 ]] || {
      print -u2 "limit must be 1..500"
      exit 64
    }
    print "schema=disk-space-audit-measure-v1"
    print "observed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    print "root=$target"
    print "depth=$depth"
    print "[largest-kib]"
    if (( $+commands[gdu] )); then
      gdu -x -k -d "$depth" -- "$target" 2>/dev/null | sort -nr | head -n "$limit"
    else
      du -x -k -d "$depth" "$target" 2>/dev/null | sort -nr | head -n "$limit"
    fi
    ;;

  candidate)
    (( $# == 2 )) || usage
    target=$(canonical_dir "$2") || {
      print -u2 "refusing missing or non-absolute directory: $2"
      exit 66
    }
    kib=$(size_kib "$target")
    [[ -n "$kib" ]] || kib=unverified
    modified_epoch=$(stat -f %m "$target" 2>/dev/null || print unknown)
    open_pids=unknown
    if (( $+commands[lsof] )); then
      open_pids=$(lsof -t +D "$target" 2>/dev/null | sort -u | wc -l | tr -d ' ')
    fi
    print "schema=disk-space-audit-candidate-v1"
    print "observed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    print "path=$target"
    print "size_kib=$kib"
    print "modified_epoch=$modified_epoch"
    print "open_process_count=$open_pids"
    print "symlink_root=$([[ -L "$2" ]] && print true || print false)"
    print "[git-markers-max-depth-4]"
    find "$target" -maxdepth 4 \( -name .git -o -name .gitmodules \) -print 2>/dev/null | head -n 40
    print "[root-git-state]"
    if git -C "$target" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      git -C "$target" status --short --branch 2>/dev/null | head -n 80
    else
      print "not-a-root-git-worktree"
    fi
    print "classification=unverified"
    print "proposed_action=none"
    ;;

  *) usage ;;
esac
