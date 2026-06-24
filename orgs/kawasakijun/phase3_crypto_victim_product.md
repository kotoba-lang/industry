# Phase 3.B — Crypto Victim Lawfirm Product Spec

`28_crypto_victim_lawfirm` の詳細設計。河崎自身の Rokes 被害を自己実証 (dogfood)
として、同種被害者向けに展開する SaaS + 成功報酬モデル。

## ターゲット市場

| カテゴリ | 推定被害規模 (世界) | 国内顕著事例 |
|---|---:|---|
| Exchange ハッキング被害者 | $7B+ 累計 | NEM 流出 (2018, ¥580億) / Mt.Gox / Liquid / Coincheck |
| DeFi rug pull / exploit | $5B+ 累計 | Cream / Wormhole / Ronin Bridge |
| 個別 wallet ハッキング | $1B+/yr | Phishing / approval-drain / clipboard malware |
| Ponzi / 投資詐欺 | $3B+/yr | 水谷翔太 ケース類似 |

**初期セグメント**: 国内・日本語話者・¥1M-¥1B の中規模被害層 (大手は既に Chainalysis 等を使用、小口は自力で諦める層が市場)。

## プラットフォーム: `crypto-victim.gftd.ai`

`lawfirm.gftd.ai` の 9-actor クラスタに追加する **10 番目の actor**:

| Actor | DID | nanoid | 役割 |
|---|---|---|---|
| `crypto-victim` | `did:web:crypto-victim.gftd.ai` | `cv1m4d0r` | 被害登録 / 追跡 / 訴訟支援 / 回収 |

### 既存 actor との連携

```
crypto-victim ──┬──→ saiban       (民事保全申立、損害賠償訴訟)
                ├──→ lawfirm      (代理人選任)
                ├──→ bengoshi     (国際弁護士マッチング)
                ├──→ adr          (Exchange 仲裁、UNCITRAL)
                ├──→ dispute      (恫喝・脅迫対応)
                └──→ bankruptcy   (加害者破産時の債権届出)
```

## MVP 機能 (Tier 1)

### F1. 被害登録 (Self-Serve)

```
入力:
- TxHash (任意の chain)
- ウォレット住所 (被害者・加害者推定)
- 時刻
- 金額 + 資産
- 被害状況の自然言語説明

出力:
- 自動 case ID 払出
- 初期追跡レポート (1h 以内)
- 法的アクション候補リスト
```

### F2. On-chain Forensics Engine

- Chain hopping 自動検出 (ETH → BSC → Tron → ...)
- Mixing service 検知 (Tornado Cash / Wasabi / Samourai)
- Exchange 出口口座の自動同定 (KYC 紐付け候補)
- Chainalysis Reactor 互換 (内製 + Public KYT データ)
- 河崎 CEH + サイバー経験のナレッジで初期実装

### F3. 法的アクション支援

- 準備書面テンプレート (民事保全 / 損害賠償)
- Exchange への協力要請レター
- FBI / Interpol / 警察庁サイバー犯罪 への通報書類
- 9-actor 内の bengoshi / adr 経由で代理人マッチング

### F4. 回収マッチング

- 執行可能な国 / Exchange の自動選定
- 加害者の資産分布の可視化
- 成功報酬ベースの代理人マッチング

## 料金モデル

### SaaS (月額)

| Tier | 月額 | 機能 |
|---|---:|---|
| Self-Serve | ¥9,800 | 被害登録 + 自動追跡レポート (制限: 月 10 件) |
| Pro | ¥98,000 | + Bengoshi マッチング + 準備書面テンプレ |
| Enterprise | 個別 | + 専属担当 + Exchange 公式パイプ |

### 成功報酬

- **回収金額の 10–20%** (相場: Chainalysis 5-15%, 競争力あり)
- **Floor**: $5,000 (最低成功報酬)
- **Cap**: ¥50M/case (大口は別契約)

## 収益試算 (Year 1)

| 想定指標 | 値 |
|---|---:|
| Self-Serve 月額 加入 | 100 件/月 → ¥980,000 |
| Pro 月額 加入 | 20 件/月 → ¥1,960,000 |
| Enterprise | 5 件 × ¥500,000/月 → ¥2,500,000 |
| 成功報酬 (avg ¥5M × 12 件/yr) | ¥6,000,000 |
| **月商** | **¥11,440,000** |
| 年商 | **¥137M** |

→ Gftd Japan 月次黒字化 (販管費 ¥4.7M/月) を **単独でカバー** + 余剰で借入返済加速。

## Eat Your Own Dogfood

### 自己実証ケース #1: Rokes / HEC

```
1. 河崎 個人ウォレット → Rokes Exchange (HEC 預入)
2. Rokes Exchange ハック → 流出 (TxHash 多数)
3. crypto-victim.gftd.ai が追跡 (chain hopping 解析)
4. 国際捜査協力要請 (FBI ITC / Interpol)
5. 民事保全 → 回収
6. ケーススタディとして公開 (Anonymous OK のケース)
```

### 自己実証ケース #2: 水谷翔太

```
- 既に確定判決 (¥52M 認容, 令和4年(ワ)第7237号)
- 判決金回収手続が次の課題
- crypto-victim.gftd.ai の「執行支援」サブモジュールの実証
```

## 開発スケジュール (1.5y)

| Quarter | Milestone |
|---|---|
| Q1 (2026-06–08) | DID 払出 + actor manifest + Self-Serve MVP |
| Q2 (2026-09–11) | On-chain forensics engine (basic) + Rokes 自己ケース |
| Q3 (2026-12–02) | Pro tier + Bengoshi マッチング + 準備書面テンプレ |
| Q4 (2027-03–05) | Enterprise tier + Exchange 公式パイプ + 公開ローンチ |
| Q5-Q6 (2027-06–11) | Scale: 国内 5 大被害 (NEM/Mt.Gox/Liquid/Coincheck/...) アウトリーチ |

## 競合比較

| 競合 | 強み | 弱み | 我々の差別化 |
|---|---|---|---|
| Chainalysis | データ深度 / 大手顧客 | 個人向け高価 | 中小被害者の個別代理 |
| TRM Labs | 規制機関向け | 個人向けなし | 9-actor 法務クラスタ統合 |
| CipherTrace | 法執行機関 | 民間個人 reach 弱い | bengoshi 経由の代理人マッチ |
| Crystal Blockchain | 欧州中心 | 日本語弱い | 国内特化 + AT Protocol |

## 戦略的優位

1. **lawfirm 9-actor 既存基盤** — 追跡 + 訴訟 + 回収のフルスタック
2. **CEO 自己実証** — Rokes 被害者であり、ケーススタディの強度
3. **W Protocol + atproto** — 被害データの分散保管 (PII 保護)
4. **CEH + サイバー経験** — 河崎の技術ブランド
5. **国際捜査ネットワーク** — DEF CON 33 CHV 2 位 / 経産省 SSS 経由
