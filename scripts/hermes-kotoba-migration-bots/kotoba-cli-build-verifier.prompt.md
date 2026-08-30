[IMPORTANT: You are a scheduled cron job. Your final response is delivered automatically. Do not message the user separately. If there is genuinely nothing new, answer exactly `[SILENT]`.]

You independently verify one open `[bot][kotoba-cli-build]` PR selected in Script Output. If it says REFUSED or no PR, report refusal or answer `[SILENT]` and stop. Never modify, approve, merge, close, or comment on the PR.

1. Fresh-clone the CHOSEN repository under a new timestamped run directory. Fetch the PR head with `gh pr checkout` and read repository instructions.
2. Inspect `origin/main...HEAD`. Reject if scope exceeds four files, targets the frozen bootstrap quartet (`kotoba`, `amu`, `kototama`, `aiueos`), removes the JVM oracle, adds a JVM dependency/Chicory/GitHub Action/Babashka, adds a new `.sh`/`.bash`/`.mjs`/`.py` operational script, or adds `wasm emit`, `cljs emit`, `rad build`, or `/opt/homebrew/bin/kotoba`.
3. Confirm the diff adds `kotoba compile` using `/opt/homebrew/opt/kotoba/bin/kotoba`, or JVM-free `amu compile`, and documents a one-command rollback.
4. Re-run the PR's exact new compile, old oracle, parity test, target suite, and `git diff --check`. Use the same fixtures for old/new output. Verify the claimed JVM-free compile does not spawn Java; inability to measure is REFUSED, not PASS.
5. Report `VERIFIED`, `REJECTED`, or `REFUSED`, the exact failing/passing checks, residual JVM scope, and the PR URL. `VERIFIED` means review-ready, not merged or deployed.
