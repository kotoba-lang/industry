# L5 階層マップ現在地レビュー — 2026-09-20 (weekly, zeta)

観測のみ。実装・投資判断はしない。捏造ゼロ: 取得できなかった値は unknown。
観測時刻: 2026-09-20 06:53 JST 前後 (UTC 2026-09-19 21:53)。窓: 直近1週間 (09-13 → 09-20)。

注: 同日 01:27 JST に別実行 (pulse 系) が `2026-09-20-l5-status.md` を作成済みのため、
本 weekly 分は `-weekly` 接尾ファイルとして記録する。

## 前回からの差分 (vs 2026-09-20 01:27 JST 版 / 09-19 版)

- **[確定] FIP-0118 の Accepted 日時が判明**: 前回まで unknown だった点を解消。
  `filecoin-project/FIPs` の `FIPS/fip-0118.md` コミット履歴 (gh api, パス指定) の
  先頭コミット **9fbc58118435bcbbcbdc75959576f8bde0a908ae** / 日時
  **2026-09-01T20:22:04Z** / メッセージ "Update fip-0118.md Status to Acceptance (#1283)"
  (PGP verified)。以降、当該ファイルへの master 直コミットは無し (全14件中これが最新)。
  - 出典: https://api.github.com/repos/filecoin-project/FIPs/commits?path=FIPS/fip-0118.md
- **[注意] 同一 master ファイルの見え方に齟齬**: raw.githubusercontent.com 版は
  `status: Accepted`、github.com の blob 表示版は `status: Last Call` を返した。
  blob 側は表示キャッシュ/古い rev の可能性が高く、raw + コミット履歴を正として
  Accepted と判定。※この齟齬自体は観測事実として記録する。
  - 出典: https://raw.githubusercontent.com/filecoin-project/FIPs/master/FIPS/fip-0118.md
    https://github.com/filecoin-project/FIPs/blob/master/FIPS/fip-0118.md
- **[継続] 受諾後も仕様完成作業が進行中** (直近1週間内の活動あり):
  - solstice issue #31「FIP-0118 specification completion」open / 最終更新
    2026-09-16T16:21Z。残項目は "Preactivation section" のみ (upgrade schedule 確定待ち)。
    https://github.com/filecoin-project/solstice/issues/31
  - solstice issue #39「Update SWA to spec and vice versa」closed 2026-09-14。
    SWA 実装と FIP §3.1.1 の差分 (setGateParams の steps<=8 検査、veto 欠落等) を整理。
    https://github.com/filecoin-project/solstice/issues/39
  - FIPs PR #1286「FIP-0118: spec clarifications」**open (未マージ)** / 作成
    2026-09-04T11:34Z。当該ファイルへの直近コミット 9fc9e4b は 2026-09-07。
    https://github.com/filecoin-project/FIPs/pull/1286
- **[学術層] arXiv 直近検索: 該当なし**。`abs:"proof of bandwidth" OR abs:"proof of
  latency" OR abs:"useful delivery"` (submittedDate 降順, max 20) は 14 件ヒットしたが、
  全て語の偶然一致 (mRNA delivery、drone delivery、ZK-GPU 性能 等) で、
  Proof of Bandwidth / Latency / Useful Delivery を主題とする新規論文は無し。
  前回の HTTP 406 問題は User-Agent 付与で解消。→ L5 学術前線は「静止」と記録。
  - 出典: https://export.arxiv.org/api/query?search_query=abs:%22proof+of+bandwidth%22+OR+abs:%22proof+of+latency%22+OR+abs:%22useful+delivery%22&sortBy=submittedDate&sortOrder=descending&max_results=20
- NKN / AIOZ: 直近1週間の仕様・白書レベルの変化は今回の検索では確認できず → unknown
  (変化なしの断定はしない)。

## L5 階層マップ現在地 (1段落ずつ)

- **L1 storage (Filecoin PoRep/PoSt)**: FIP-0118 (Solstice, Accepted 2026-09-01) により
  PoRep sector の品質が L1 コンテンツから切り離され、新規 sector は内容に関係なく
  ライフタイム 10× QAP を onboarding 時点で取得。Fil+ datacap は廃止へ。報酬は
  block-reward を burn(w0) / consensus(w1) / service(w2) の stream に分割し、w2 は
  Filecoin Pay 経由の 四半期 AggregatedFPV が USD 目標を満たした場合のみ 5pp/quarter で
  段階増 (bootstrap 後)。「storage を暗号経済的に証明する」現行の縦軸は維持しつつ、
  報酬の重心を storage から service volume へ移す設計に転換。受諾後の仕様完成
  (solstice #31/#39, PR #1286) が今週も動いている。
- **L2 availability (PDP)**: FIP-0118 の service stream は Filecoin Pay で決済された
  deal payments を報酬計算の入力にする。PDP (hot storage / fast retrieval) 側の
  仕様変更は今週の検索では確認できず → unknown。ただし L1 の変更 (QAP 10× 固定) は
  「品質で得点を稼ぐ」動機を消すため、available/hot な供給は引き続き PDP + retrieval
  側の経済に寄る構造になる。
- **L3 transmission (NKN PoR)**: 今週の観測ではプロトコル仕様・mainnet 実装の変化を
  確認できず → unknown。NKN は Proof of Relay の位置づけを維持 (前回からの変更なし)。
- **L4 delivery (AIOZ PoD)**: Vision Paper V2 の PoS/PoD/PoT 3 proof 分離から変化は
  確認できず → unknown。
- **L5 performance / useful delivery (未解決)**: bandwidth・latency・useful delivery を
  trustless に証明して報酬対象化する primitive は、今週も本命の進展なし。arXiv 検索で
  主題論文ゼロ (上記)。受信者時計・Sybil・地理性の問題を解く新仕様も出ず。
  ただし Filecoin 側 (L1) の FIP-0118 は「測定可能な service volume を報酬入力にする」
  点で L5 の問題域に最も近い動きであり、AggregatedFPV という four-quarter ゲートは
  delivery 証明の代替 (決済記録による代理指標) を採用した例として注視。

## unknown リスト

- NKN PoR の直近1週間の変化 (確認できず)
- AIOZ PoD/PoT の直近1週間の変化 (確認できず)
- FIP-0118 の Preactivation section / upgrade schedule (未確定, solstice #31)
- blob 表示と raw の status 齟齬の原因 (キャッシュか rev 差か)

## 観測上の注意

- GitHub blob 表示は raw より遅れることがある。ステータス判定は raw + commits API。
- arXiv API は User-Agent 必須 (無いと 406)。cron での python -c 実行は approval
  ポリシーでブロックされるため、パースは grep/redirect で行う。
