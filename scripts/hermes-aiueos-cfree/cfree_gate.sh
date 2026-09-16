#!/bin/bash
# cfree_gate.sh — Hermes cron `--script` は .sh を bash で、それ以外を Python で走らせる
# (hermes cron create --help)。この 1 枚は kbb の gate へ渡す shim であり、判断は
# cfree_gate.cljk 側にだけ書く (CLAUDE.md: 新規 tooling は kbb-first、.sh を書かない — この
# shim は Hermes の起動制約が要求する最小限で、logic を持たない)。
set -u
exec kbb --backend sci "$(dirname "$0")/cfree_gate.cljk"
