#!/usr/bin/env bash
# personal/bin/seal-evidence.sh — 法的証跡を改ざん不能で封緘し warehouse に取り込む
#
# ADR-0005 の custody パイプライン:
#   1) SHA-256 を計算し manifest に記録
#   2) OpenTimestamps で存在証明 (ots があれば; 無ければ skip し WARN)
#   3) git-annex add (内容アドレス化) → B2 へ暗号化コピー (ADR-0011: 公開網 pin は廃止)
#
# 使い方:
#   personal/bin/seal-evidence.sh <case> <file> [<file> ...]
#     <case> = rokes | aishisystem | ...   (personal/litigation/<case>/ に格納)
# 例:
#   personal/bin/seal-evidence.sh aishisystem ~/Downloads/添付資料.zip ~/Downloads/追加資料_20260226.zip
#   personal/bin/seal-evidence.sh rokes ~/Downloads/takeout-rokes-*.tgz
#
# 注意: ファイル本体は annex 化 (git には平文が乗らない)。manifest(.md/.json) は git 平文。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"          # .../personal
REPO="$(cd "$ROOT/.." && pwd)"                                    # repo root

[ $# -ge 2 ] || { echo "usage: $0 <case> <file> [file ...]" >&2; exit 2; }
CASE="$1"; shift
DEST="$ROOT/litigation/$CASE"
mkdir -p "$DEST"

NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
MANIFEST="$DEST/MANIFEST.md"
[ -f "$MANIFEST" ] || cat > "$MANIFEST" <<EOF
# 証跡 MANIFEST — $CASE (ADR-0005 custody)

| sealed_at (UTC) | filename | sha256 | bytes | ots |
|---|---|---|---|---|
EOF

have_ots=0; command -v ots >/dev/null 2>&1 && have_ots=1
[ $have_ots -eq 1 ] || echo "[seal] WARN: ots 未インストール → OpenTimestamps 省略 (brew install opentimestamps-client / pipx install opentimestamps-client)" >&2

for SRC in "$@"; do
  [ -f "$SRC" ] || { echo "[seal] skip (not a file): $SRC" >&2; continue; }
  BASE="$(basename "$SRC")"
  TGT="$DEST/$BASE"
  cp -p "$SRC" "$TGT"
  SHA="$(shasum -a 256 "$TGT" | awk '{print $1}')"
  BYTES="$(wc -c < "$TGT" | tr -d ' ')"
  OTS="-"
  if [ $have_ots -eq 1 ]; then
    ots stamp "$TGT" >/dev/null 2>&1 && OTS="$BASE.ots" || OTS="(stamp failed)"
  fi
  printf '| %s | %s | %s | %s | %s |\n' "$NOW" "$BASE" "$SHA" "$BYTES" "$OTS" >> "$MANIFEST"
  echo "[seal] $BASE  sha256=$SHA  bytes=$BYTES  ots=$OTS"
done

echo "[seal] done. Next:"
echo "  git -C $REPO annex add personal/litigation/$CASE/"
echo "  git -C $REPO add personal/litigation/$CASE/MANIFEST.md personal/litigation/$CASE/*.ots 2>/dev/null"
echo "  git -C $REPO annex copy personal/litigation/$CASE/ --to b2"
echo "  git -C $REPO commit -m 'evidence($CASE): seal + ingest (ADR-0005)'"
