# zeta L5 delivery observations — 2026-09-17 (zeta-l5-pulse baseline run)

First run of zeta-l5-pulse: no prior state file existed, so all items recorded as baseline (no diff vs. previous state is possible; every value below is directly observed from the cited URL).

## Baseline: 3 監視対象

- **NKN Proof of Relay** — https://docs.nkn.org/docs/proof-of-relay
  - headline: "Proof of Relay (PoR) · NKN Docs" / 日付: unknown (ページに日付表記なし)
  - proof 名: Proof of Relay (PoR) / signature chain / secure path / session-based transmission (deterministic + probabilistic)。
  - 所感: PoR は relayed bytes の暗号経済的証明 (L3 transmission) を signature chain で解く。ただし証明対象は「中継した」ことであり「宛先に届いた (delivered)」また「期限内に届いた (useful)」ではない — L3 のまま。

- **AIOZ Vision Paper V2 (PoD 関連)** — https://aioz.network/blog/aioz-network-vision-paper-v2-building-a-unified-depin-layer-powered-by-people
  - headline: "AIOZ Network Vision Paper V2: Building a Unified DePIN Layer, Powered by People" / 日付: 2026-02 (本文 'At the beginning of 2026', 画像パス /2026/02/)
  - proof 名: **この post 内に PoD / PoT / PoS という明示名は出現しない**。表現は "verifiable proof systems" (stored / delivered / computed を確認し、validated work のみ settlement) の一般記述のみ。dBFT + unified task orchestration + reputation。
  - 所感: V2 での proof 分離 (PoS/PoD/PoT) の明文は本 post では確認できず。将来の個別 proof 記事を追う必要あり。unknown 扱いとする。

- **Filecoin PDP** — https://www.filecoin.io/blog/introducing-proof-of-data-possession-pdp-verifiable-hot-storage-on-filecoin
  - headline: "Introducing Proof of Data Possession (PDP): Verifiable Hot Storage on Filecoin" / 日付: 2025-05-01
  - proof 名: PDP (mainnet live), PoRep (cold)。特徴: 160 bytes/challenge SHA-2 sampling, mutable collections, 日次 scheduled challenges。FilOz 記事 'PoRep, PDP, Proof of Delivery: Different Proofs for Different Use Cases' への言及あり (https://medium.com/@filoz/porep-pdp-proof-of-delivery-different-proofs-for-different-use-cases-e6daede195fb)。
  - 階層位置: PDP は L2 (availability) の確立。L5 delivery には未達。

## 新規論文・白書 (Proof of Bandwidth / Latency / Useful Delivery 主題)

- arXiv:2609.12727 "Fresh-Challenge VDF Attestations for Model-Relative Response Latency" (2026-09-11 提出, 本日時点で最新) — https://arxiv.org/abs/2609.12727
  - VDF + 予測不能 challenge で応答レイテンシの model-relative 証明。受信者時計問題への VDF 経由のアプローチ。ただし claimant 特定はしない (relaying/outsourcing は排除できないと本文明記)。
- Signet: Scalable Network-Driven Proof of Notification (ICDCS 2026, ETH Zürich) — https://netsec.ethz.ch/publications/papers/Signet_ICDCS_2026.pdf
  - AS (Autonomous System) を delivery の証人とし TESLA 対称鍵で line-rate の proof-of-notification。L4/L5 境界に最も近い実装志向設計。100 万 notification/sec/core, μs オーダーの per-packet オーバーヘッド。
- arXiv:2603.20426 "Pricing Innovation Under Latency Constraints: A Mean-Field Analysis of Coded Payload Delivery" — https://arxiv.org/html/2603.20426v2
  - デコード完了時刻分布=経済価値という定式化。RLNC rateless shard が「有用性 (innovativeness)」を直接価格に変換 — L5 'useful' delivery の経済モデルとして注視。
- arXiv:2502.10637 "Proof of Response" (Polosukhin/Skidanov, NEAR, 2025-02-15) — https://arxiv.org/abs/2502.10637
  - bounded time 内の応答、または edge 切断 proof、または遅延比例の streaming payment。
- NDSS 2024 "Proof of Backhaul" — https://doi.org/10.14722/ndss.2024.24764 (multi-challenger trustfree bandwidth 証明, 1000 Mbps を <10% error / 100ms)
- arXiv:2403.13230 "BFT-PoLoc" — https://arxiv.org/abs/2403.13230 (Internet delay による Byzantine 耐性位置証明 — latency 証明の地理性問題への既存アプローチ)

## L5 階層マップ現在地

L1 storage (Filecoin PoRep, 確立) → L2 availability (PDP mainnet, 確立) → L3 transmission (NKN PoR, 運用) → L4 delivery (AIOZ は "proof of delivery" を一般記述のみで明示 proof 名未確認) → **L5 useful/performance delivery (未解決)**。Signet (AS を証人にする) と arXiv:2603.20426 (decoding-time distribution を経済価値に直結) が現時点で L5 に最も近い 2 本。
