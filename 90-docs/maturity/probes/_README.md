# probes — 3D スタックの behavior probe

各ファイルは `scripts/threed-maturity-audit.cljs` が
`nbb --classpath <repo>/src <probe>.cljs` で起動する。契約は stdout に **1 行**:

    PROBE <axis-behavior-id> PASS|FAIL|UNMEASURABLE <detail>

- **PASS** — 不変条件が成り立った。その軸は `:working`
- **FAIL** — 呼べたが不変条件が破れた。その軸は `:hollow`（名前だけ在って動かない）
- **UNMEASURABLE** — 実行できなかった（require 失敗・GPU が要る等）。
  **pass にも fail にも数えない。** 詳細に受け取ったエラー本文を残すこと。

probe の中で例外を握り潰して PASS を出さないこと。`try` は
「UNMEASURABLE を出すため」にだけ使う。
