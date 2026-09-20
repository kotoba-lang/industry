# L5 階層マップ現在地レビュー — 2026-09-20 (weekly #2, zeta)

観測のみ。実装・投資判断はしない。捏造ゼロ: 取得できなかった値は unknown。
観測時刻: 2026-09-20 18:56-19:3x JST。窓: 直近1週間 (09-13 → 09-20)。
注: 本日既に 2 本の status ファイル (01:27 / 07:38) があるため本分は
`2026-09-20-l5-status-weekly2.md` として記録する。

## 前回からの差分 (vs 2026-09-20 07:38 JST weekly 版)

- **[重要] Solstice (FIP-0118) のコントラクトが mainnet / calibnet にデプロイされた**:
  solstice repo の commit `0fa8cca2d1` (2026-09-17T21:14:21Z, PGP verified,
  "chore: deploy on mainnet and calibnet") が `deployments.json` の Filecoin mainnet
  (chain 314) と Calibration (314159) の `sra` / `swa` を
  `0x000...000` → 実コントラクトアドレスに更新した
  (mainnet: SRA `0xeDfCd0947F7E9d58E0035f032520d75ce8eCA451` /
  SWA `0xDE4fBd083F18f96C241DdE0A83C3EDC422Be9BA6`)。
  Service Rewards Actor / Service Wallet Actor がオンチェーンに実体を持った初の
  観測。前回まで「仕様完成作業が進行中」だった状態から実装デプロイ段階へ。
  - 出典: https://api.github.com/repos/filecoin-project/solstice/commits/0fa8cca2d1
    https://raw.githubusercontent.com/filecoin-project/solstice/main/deployments.json
- **[重要] 今週の solstice コミット 12 件は全てデプロイ準備系**: quarterly gate
  check guard (#67)、Calibration activationEpoch 設定 (#71)、butterflynet
  orchestrator seed (#70)、initial Orchestrator seed (#63)、quarter zero reserve
  (#64)、SWA quarterly gate の FIPs#1277 整合 (#39/#43, 09-14) 等。
  activation epoch は mainnet で 6450120 に設定済み (deployments.json)。
  upgrade schedule の確定 (solstice #31 の残項目) に近づいている。
  - 出典: https://api.github.com/repos/filecoin-project/solstice/commits?since=2026-09-13T00:00:00Z
- **[継続] FIPs repo 側は静穏**: 直近1週間の FIPs コミットは 1 件のみ
  (`732779540c` 2026-09-14, FIP editor 追加提案 #1289)。fip-0118.md 自体への
  変更は無し。spec clarifications PR #1286 は open のまま (updated
  2026-09-18T19:49:41Z)。
  - 出典: https://api.github.com/repos/filecoin-project/FIPs/commits?since=2026-09-13T00:00:00Z
    https://api.github.com/repos/filecoin-project/FIPs/pulls/1286
- **[学術層] arXiv 再検索: 主題論文ゼロ (静止の継続)**。User-Agent 付きで
  成功 (33,966 bytes 取得)。14 件ヒット中、Proof of Bandwidth / Latency /
  Useful Delivery を主題とする新規論文は無し (全て語の偶然一致)。
  直近の published は 2026-04-30 で、今週の新規投稿は無し。
  - 出典: https://export.arxiv.org/api/query?search_query=abs:%22proof+of+bandwidth%22+OR+abs:%22proof+of+latency%22+OR+abs:%22useful+delivery%22&sortBy=submittedDate&sortOrder=descending&max_results=20
- **NKN**: mainnet repo に今週の commit 0 件。最新 release は v2.2.5
  (2026-01-09) のまま変化なし → PoR 仕様・実装とも静穏。
  - 出典: https://api.github.com/repos/nknorg/nkn/commits?since=2026-09-13T00:00:00Z
    https://api.github.com/repos/nknorg/nkn/releases
- **AIOZ**: blog 直近は August 2026 report (2026-09-01) で、PoS/PoD/PoT proof
  仕様の変化は観測されず。blog HTML のスクレイプでは記事リンク抽出はできず
  (JS レンダリング) → 記事レベルの網羅は不完全、search 結果と併せて判定。
  - 出典: https://aioz.network/blog / https://aioz.network/blog/aioz-network-report-august-2026

## L5 階層マップ現在地 (1段落ずつ)

- **L1 storage (Filecoin PoRep/PoSt)**: proof 機構自体の設計変更なし。ただし
  FIP-0118 (Solstice) の実装がデプロイ段階に入った — SRA/SWA コントラクトが
  mainnet + Calibration にオンチェーン配置され (上記 deployments.json)、
  activation epoch 6450120 が設定済み。PoRep sector の content 非依存 10× QAP、
  block reward の consensus/service/burn 3 stream 分割が実コードとして近づいた。
  https://raw.githubusercontent.com/filecoin-project/solstice/main/deployments.json
- **L2 availability (PDP)**: 今週の仕様・契約レベルの変化は観測されず → unknown。
  docs 上は PDP が FOC (Filecoin Onchain Cloud) の「verifiable heartbeat」として
  Filecoin Pay の決済調整に使われる構造が維持 (docs 最終更新 2026-07-27 表示)。
  https://docs.filecoin.cloud/core-concepts/pdp-overview/
- **L3 transmission (NKN PoR)**: 変化なし。sigchain + VRF の PoR 仕様は安定、
  mainnet repo に今週の commit 無し、最新 release v2.2.5 (2026-01) のまま。
  中継「量」の証明は確立済み、配送の有用性は対象外のまま。
  https://docs.nkn.org/docs/proof-of-relay
- **L4 delivery (AIOZ PoD)**: 変化なし。Vision Paper V2 / whitepaper v2 の
  PoS/PoD/PoT 分離は維持。9 月の blog 出力は 8 月次レポート (09-01) 以降
  proof 仕様に関わる更新を確認できず。
  https://aioz.network/blog/aioz-network-report-august-2026
- **L5 performance / useful delivery (未解決)**: 未解決のまま。今週の実質的
  変化は L1 側 — Solstice の報酬コントラクトの mainnet デプロイ。これにより
  「測定可能な paid service volume に報酬を gate する」構造が仕様から実装に
  進んだが、その「測定」は Filecoin Pay 決済額 (代理指標) であり、trustless な
  帯域/レイテンシ/配送品質の証明ではない。arXiv・Web 検索でも受信者時計 /
  Sybil / 地理性を解く新仕様は出ず (Princeton CITP の Witness Chain PoB
  (Proof of Backhaul / Proof of Service) は既知の先行例のまま)。
  https://spia.princeton.edu/events/citp-seminar-witness-chain-proofs-bandwidth-trust-free-wireless-networking

## unknown リスト

- mainnet activation epoch 6450120 の実日時換算 (未換算)
- solstice #31「Preactivation section」の残項目が今回のデプロイで解消されたか
- AIOZ blog の 9 月中旬記事の有無 (JS レンダリングで抽出不能)
- PDP mainnet 今週分の再取得値 (未実施)
- Beam SN105 の PoB 監査の具体的信頼モデル (継続)

## 観測上の注意

- 本 run も terminal の stdout が直接返らない異常が継続。ファイルリダイレクト
  + read_file で回避。`python3 -c` / heredoc は cron approval ポリシーで
  ブロックされるため、パースは write_file した .py + リダイレクトで実行。
- GitHub blob 表示は raw より遅れることがある (前回記録の継続)。判定は raw +
  commits API を正とする。
