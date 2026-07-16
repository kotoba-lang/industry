# ADR-2607170900: yabai を独立リポジトリに統合（etzhayyim/root の重複コピー撤去）

## Status

Accepted, implemented（2026-07-16）。オーナー指示「yabai を独立した repo にして, etzhayyim で ok」に基づく。独立リポは既存の `com-etzhayyim-yabai`（GitHub `etzhayyim/root` org 傘下、west project `orgs/etzhayyim/com-etzhayyim-yabai`）。撤去対象は `etzhayyim/root` リポ内に vendored された重複コピー `20-actors/yabai/`。

## Context

ADR-2607170800（Cloudflare zone scanner ingest 配線）の fleet 自走を実装しようとした過程で、**yabai actor が2箇所に独立して存在し、drift していた**ことが判明した:

1. `orgs/etzhayyim/com-etzhayyim-yabai` — west 標準の独立リポ（`actor.edn`/`deps.edn`/`src/`/`test/`/`methods/`/`data/` を持つ完全形）。ADR-2607170800 の配線（`methods/cf_sweep.cljc`・ingest bridge・`test_cf_scanners.cljc`・scanner/email-phishing IOC・再生成 merged）が landed 済み。
2. `orgs/etzhayyim/root/20-actors/yabai` — `etzhayyim/root` リポに**直接 tracked された vendored copy**（gitlink でも submodule でもない plain dir）。2026-07-09 で更新停止。ADR-2607170800 の配線も email-phishing IOC も持たない stale コピー。

両者は私の 2607170800 変更前まで完全同期していた（`ingest.cljc` の差分＝追加分そのもの）。自動 vendoring 機構は存在せず、**手動ミラーが drift した**状態だった。

**撤去安全性の確認（コード直読）**:
- **runtime/classpath 消費者ゼロ**: `20-actors/yabai` を参照するのは自身の test コメント（`bb --classpath 20-actors 20-actors/yabai/methods/...`）のみ。`etzhayyim/root` の fleet/runtime コードで `yabai.methods.*` を import する箇所は vendored copy 外に無い。
- **fleet 稼働セルは別コードベース**: 実スケジューラ登録簿 `50-infra/cluster/murakumo/cell-runner/cells.edn` の唯一の稼働 yabai セル `YabaiTorTorrentCtiPersistenceCell` は module `kotodama.primitives.yabai_murakumo`（lan-api、issachar）を呼ぶ。vendored `methods/*.cljc` は呼ばない。よって撤去は稼働に無影響。
- **独立リポは厳密な上位集合**: `diff -rq` で vendored 固有ファイルはゼロ（`Only in` は全て `com-etzhayyim-yabai` 側）。撤去で失う内容は無い。
- 補足で判明した stale doc: `fleet.edn` の node cells リストと yabai CLAUDE.md が謳う `yabai_cti_ingest (cron 22)`/`weave (27)`/`persist (32)` は `cells.edn` に**実在しない**（`.toml` という記述も誤り、実体は `fleet.edn`）。本 ADR の対象外だが Consequences に残す。

## Decision

**`com-etzhayyim-yabai` を yabai の唯一の source of truth（独立リポ）とし、`etzhayyim/root` 内の vendored 重複 `20-actors/yabai/` を撤去する。**

1. `etzhayyim/root` から `20-actors/yabai/` を `git rm -r` で削除。
2. 同位置に marker（`20-actors/yabai-MOVED.md`）を置き、独立リポ `github.com/etzhayyim/com-etzhayyim-yabai`（west path `orgs/etzhayyim/com-etzhayyim-yabai`）へ移設した旨・日付・本 ADR を記す。
3. `etzhayyim/root` の変更は feature branch → push → サーバサイド merge で main 着地、west pin を前進。
4. 将来 `etzhayyim/root` の tooling が yabai コードを要する場合は、**vendoring でなく** west sibling checkout（`orgs/etzhayyim/com-etzhayyim-yabai`）を classpath/deps 依存として参照する（重複を再発させない）。

## Consequences

**Good**:
- yabai の source of truth が1箇所に集約。drift 再発を構造的に防ぐ。以後の更新（ADR-2607170800 の配線含む）は独立リポのみで完結。
- fleet 稼働（`YabaiTorTorrentCtiPersistenceCell`）は別コードベースゆえ無影響。撤去は low-risk・可逆（git 履歴に残る）。

**Bad / 負債（follow-up）**:
- **fleet の真の自走はまだ**。ADR-2607170800 で作った `cf_sweep` は独立リポにあるが、それを fleet セルとして走らせるには (a) `cells.edn` に `kotodama.primitives.*` 相当の cron セルを追加、(b) fleet runner が独立リポの `methods/cf_sweep.cljc` を解決する経路、が必要。本 ADR は「重複撤去」まで。セル化は別 ADR。
- **stale doc**: `fleet.edn` の node cells リストの `yabai_cti_ingest/weave/persist`（cells.edn に不在）と、yabai CLAUDE.md の「fleet.toml / cron 22-32」記述は実態不一致のまま。実態は `YabaiTorTorrentCtiPersistenceCell`（lan-api）。別途 doc 修正が要る。
- 当面の honeypot 収集の自走は、fleet 統合を待たず claude.ai routine（`/schedule`、日次 `cf_sweep --live`）で回せる（ADR-2607170800 の軽量経路）。

## Alternatives considered

1. **cross-repo symlink `20-actors/yabai -> ../../../com-etzhayyim-yabai`** — 却下。`etzhayyim/root` は fleet が standalone clone する（KaizenPrAgentCell の repo-clone initContainer、repo_root `/workspace/root`）ため、sibling west checkout の無い環境で symlink が dangling する。marker + 依存参照の方が健全。
2. **vendored copy を前進同期して残す（ADR-2607170800 の option 2）** — 却下。drift の再発を許す。オーナー指示は「独立リポ化」= 重複解消。
3. **west nested path で `20-actors/yabai` を west project 化** — 却下。west project は superproject manifest 管理で、別 west project（`etzhayyim/root`）配下への nested checkout は運用が複雑。independent repo は既に存在するので不要。

## References

- ADR-2607170800（yabai Cloudflare zone scanner ingest — 本統合のきっかけ、独立リポに landed）
- ADR-2605301400 §T3（yabai CTI の kotoba-native 化、fleet セル設計）
- 独立リポ: `orgs/etzhayyim/com-etzhayyim-yabai`（`etzhayyim/root` GitHub org）
- 撤去対象: `orgs/etzhayyim/root/20-actors/yabai/`
- 稼働セル実体: `orgs/etzhayyim/root/50-infra/cluster/murakumo/cell-runner/cells.edn` の `YabaiTorTorrentCtiPersistenceCell`（module `kotodama.primitives.yabai_murakumo`）
