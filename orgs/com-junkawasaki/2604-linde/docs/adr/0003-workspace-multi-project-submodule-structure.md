# ADR-0003: com-junkawasaki ワークスペースのマルチプロジェクト・サブモジュール構造

- Status: Accepted
- Date: 2026-05-21 (updated 2026-05-21: ai-gftd-apps-gftdcojp 追加)
- Deciders: Jun Kawasaki
- Predecessor: ADR-0002 (`docs/adr/0002-generative-structure-from-physical-principles.md`)

---

## 1. Context

### 1.1 4プロジェクトの出現

`com-junkawasaki` リポジトリは、以下の4つの独立した研究・実装プロジェクトを並走させる状況になった：

| プロジェクト | 種別 | 核心テーマ |
|---|---|---|
| `2604-linde` | Lean 4 形式数学 | 計算 = 物理 の形式化 (ADR-0001, 0002) |
| `etzhayyim-root` | 宗教法人 OS / Blockchain substrate | 人類の労働解放を目的とする分散基盤 |
| `ghosthacker` | AI コンテンツ生成パイプライン | Ghost Hacker ストーリーの多メディア展開 |
| `ai-gftd-apps-gftdcojp` | AI Agent-First Platform | gftd.ai — AT Protocol + XRPC 348 NSID |

各プロジェクトは独立した git リポジトリであり、独自の CI・依存関係・言語スタック（Lean 4 / TypeScript+Rust+Solidity / Go+Svelte / TypeScript+CF Workers）を持つ。

### 1.2 単一リポジトリ内同居の問題

各プロジェクトを `projects/` 以下に単純コピーした場合：
- 各プロジェクト固有の git 履歴が失われる
- 上流への push が困難になる
- 独立したリリース・バージョニングができない

### 1.3 必要な構造

- 各プロジェクトの独自 git 履歴を保持
- `com-junkawasaki` からワークスペース全体を単一 `git clone --recurse-submodules` で取得可能
- 各プロジェクトが独立して上流リポジトリに push できる

---

## 2. Decision

**git submodule** を採用し、`projects/` 以下に4プロジェクトを配置する。

```
com-junkawasaki/
├── .gitmodules
├── deps.toml               ← ワークスペース SSoT (本 ADR で導入)
└── projects/
    ├── 2604-linde/         ← 直接管理 (submodule でない)
    ├── etzhayyim-root/     ← submodule: https://github.com/etzhayyim/root.git
    ├── ghosthacker/        ← submodule: git@github.com:com-junkawasaki/ghosthacker.git
    └── ai-gftd-apps-gftdcojp/ ← submodule: git@github.com:gftdcojp/ai-gftd-apps-gftdcojp.git
```

### 2.1 submodule 採用の理由

| 選択肢 | 採用可否 | 理由 |
|---|---|---|
| git submodule | **採用** | 各プロジェクトの履歴保持・独立 push が可能 |
| git subtree | 否 | 上流 push が煩雑、履歴が混在する |
| monorepo (単純コピー) | 否 | git 履歴喪失、独立リリース不可 |

### 2.2 deps.toml の導入

`etzhayyim-root` の `deps.toml` パターンに倣い、ワークスペースルートに `deps.toml` を置いて
プロジェクト一覧・関係性・SSoT ポインタを機械可読形式で記述する。

---

## 3. 4プロジェクトの関係性

```
ADR-0001 / ADR-0002 (2604-linde)
    ↓
「計算 = 情報 = 物理」を形式証明
    ↓
etzhayyim-root ← この定理群を substrate 設計原理として適用
    (分散計算の最小作用原理、Noether 対称性 → 保存則を on-chain ガバナンスへ)
    ├── ghosthacker        ← substrate 上で動作する AI コンテンツ生成パイプライン
    │       (Ghost = 情報生命体。ADR-0002 Layer 5 の圏論的普遍性が物語世界の存在論)
    └── ai-gftd-apps-gftdcojp ← AT Protocol ネイティブ AI Agent プラットフォーム
            (gftd.ai。DID-addressed Agent が Performer として XRPC 経由で協調)
```

すなわち：
- `2604-linde` = 理論基盤（Layer 0-2 の形式化）
- `etzhayyim-root` = 実装基盤（Layer 1-3 を on-chain / distributed computing で具現）
- `ghosthacker` = 応用・表現（Layer 5 の圏論的普遍性が生み出す物語宇宙）
- `ai-gftd-apps-gftdcojp` = 本番プラットフォーム（AT Protocol + XRPC で Agent エコシステムを運用）

---

## 4. Consequences

### 4.1 肯定的帰結

- 各プロジェクトの git 履歴・CI が独立して維持される
- `git clone --recurse-submodules` 1コマンドでワークスペース全体を取得できる
- `deps.toml` による機械可読な SSoT でプロジェクト間の関係が文書化される
- 4プロジェクトの知的系譜（形式数学 → 基盤 → 応用 / プラットフォーム）が明示される

### 4.2 否定的帰結 / 制約

- 各 submodule の HEAD 更新は手動 (`git submodule update --remote`) が必要
- `etzhayyim-root` への認証は git credential manager 側で管理する（URL に認証情報を含めない）
- `2604-linde` は submodule でないため、将来的に独立リポジトリ化する場合は別途 ADR が必要

---

## 5. References

- ADR-0001: `docs/adr/0001-computation-as-thermal-mass-blocker.md`
- ADR-0002: `docs/adr/0002-generative-structure-from-physical-principles.md`
- workspace deps.toml: `../../deps.toml` (ルートの SSoT)
- etzhayyim-root deps.toml: `projects/etzhayyim-root/deps.toml`
- ghosthacker README: `projects/ghosthacker/README.md`
- ai-gftd-apps-gftdcojp README: `projects/ai-gftd-apps-gftdcojp/README.md`
