# ADR-2607021230: Wave-1 west.yml regen の pin 退行/不達 pin の監査と修復

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

`5323da6`「chore(manifest): regenerate west.yml for Wave 1 subagent ports」は
`gen-west-manifest.cljs` の全体再生成で main に入ったが、生成器は **ローカル working
HEAD で pin する**ため（CLAUDE.md が警告する「pin 退行の罠」）、実行セッションの
ローカル checkout 状態がそのまま main の pin に写った。ai-gftd-newscaster の退行
（37f7c53→c8ac397、`2b23f48` で修復済み）を契機に**全数監査**を実施（2026-07-02）。

監査方法: regen 直前（`5323da6^` = `7c27999`）と現 main の west.yml を GitHub API で
取得し pin 差分 21 件を抽出、各子リポの compare API で ahead/behind/存在を判定
（shallow ローカルの ancestry 判定は使わない）。

**結果**（21 件中）:
- 前進（正常）11 件 — bim/dft/geo/liquid-glass-ui/model-checking/kami-genko/
  kotoba/kotoba-lang/security/kami-nv-compat/ai-gftd-newscaster。
- **退行 3 件** — murakumo（remote より 10 遅れ）/ net-kotobase（2 遅れ）/
  kami-engine-sdk（6 遅れ）。
- **不達 pin 2 件（最重症）** — kami-engine / mst の現 pin がリモートに**存在しない**
  （未 push のローカル commit を pin。`west update` が `not our ref` で壊れる）。
- **リポ自体が GitHub に未存在 3 件** — expr / gpu / physics（pre/cur 両 pin とも
  解決不能。Wave-1 port の子リポが push されていない）。
- **pre 側欠落 2 件** — crypto / spice（現 pin はリモートに存在し fetch 可。
  regen 前の pin の方が解決不能 = それ以前からの上流 rewrite 疑い）。

## Decision

1. **修復 5 件**（本 ADR と同 commit の west.yml single-purpose 変更。全て
   「リモート default branch HEAD に一致 or その祖先→HEAD へ」の純前進で、
   compare API で ahead/identical を検証済み）:

   | project | 退行 pin（現 main） | 修復 pin | 根拠 |
   |---|---|---|---|
   | kotoba-lang/murakumo | `ab68810c`（HEAD-12） | `06125846`（= remote HEAD） | pre は HEAD の祖先(+2) |
   | gftdcojp/net-kotobase | `d5388a33`（HEAD-2） | `699384fc`（= pre = remote HEAD） | identical |
   | com-junkawasaki/kami-engine-sdk | `91ff23bb`（HEAD-6） | `379b9550`（= pre = remote HEAD） | identical |
   | kotoba-lang/kami-engine | `34b72266`（**remote に無い**） | `83ee14ae`（= pre = remote HEAD） | 不達→復元 |
   | kotoba-lang/mst | `2dc31325`（**remote に無い**） | `ab049be5`（= pre = remote HEAD） | 不達→復元 |

2. **記録のみ（修復せず）**: expr / gpu / physics は GitHub リポ未存在のため pin
   修復では直せない — **Wave-1 実行セッションが子リポを push するか entry を
   取り下げるまで west update 対象外**とする follow-up。crypto / spice は現 pin
   有効のため温存（pre 欠落は上流 rewrite の可能性として記録）。

3. **再発防止**: 全体 regen を main に入れる前に「pin ⊆ リモート到達可能 &&
   前進のみ」を検証する（`gen-west-manifest.cljs --check` は canonical 一致しか
   見ない。regen 実行者は各 pin の remote 存在確認 + 退行チェックを通すこと。
   CLAUDE.md の該当節を参照）。

## Consequences

- (+) kami-engine / mst の `west update` 不能が解消。murakumo / net-kotobase /
  kami-engine-sdk の履歴消失（他 clone から見た退行）が修復。
- (+) 監査手順（pre/cur blob 取得 → compare API 分類）が再利用可能な形で残る。
- (−) expr / gpu / physics は依然 west update 不能（follow-up 明記）。
- (−) crypto / spice の pre pin 欠落の根本原因（上流 rewrite?）は未特定。

## References

- `5323da6`（Wave-1 regen）/ `7c27999`（pre-regen）/ `2b23f48`（newscaster 単体修復）
- CLAUDE.md「west.yml への変更」「pin 退行の罠」節、ADR-2606272237
- 本 ADR とペアの .edn
