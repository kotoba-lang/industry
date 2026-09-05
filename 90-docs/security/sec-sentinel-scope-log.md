# sec-sentinel 記録 (scope ログ)

- 2026-09-04T14:43Z scope=kototama cargo-audit 0.22.2 (advisory-db 1239 advisories): 検出 19 件 (critical 2 / high 1 / medium 9 / low 7 内訳は RUSTSEC 参照; wasmtime 22.0.1 に 17 件集中)。npm audit (package-lock) 0 件。対策: crossbeam-epoch 0.9.18→0.9.20 (RUSTSEC-2026-0204, medium) を lockfile-only bump → PR kotoba-lang/kototama#131。breaking major (wasmtime 22.0.1, critical 2 / high 1 含む 17 件) は issue #132 に起票。cargo-audit 未導入だったため brew で導入 (0.22.2)。PR #131, issue #132。
- 2026-09-05 scope=net-kotobase npm audit --package-lock-only: engine 0 件 / control-plane 0 件。検出 0、対策不要。superproject に既存の未 commit 変更があるため PR は出さず本ログのみ記録。
