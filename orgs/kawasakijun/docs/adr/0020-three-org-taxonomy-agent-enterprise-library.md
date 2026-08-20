# ADR-0020: 三組織タクソノミ — agent中心(etzhayyim) / 企業app(gftdcojp) / ライブラリ+個人(com-junkawasaki)

- **Status**: Accepted
- **Date**: 2026-06-23
- **Deciders**: 河崎純真 (jun784@gmail.com)
- **Context tags**: org-taxonomy, repo-layout, agent-centric, enterprise-app, library, personal, placement-policy
- **Supersedes (部分)**: ADR-0007（owner キーの `orgs/<org>/<repo>` レイアウト）に *概念上の配置基準* を追加して上書きするものではなく、補完する
- **Companion (機械可読)**: `orgs/kawasakijun/docs/adr/0020-three-org-taxonomy.edn`
- **SSoT 連携**: `deps.edn :org-taxonomy` / `orgs/personal/facts/orgs.edn`

## 1. Context

`orgs/` 配下は 3 つの GitHub owner — `etzhayyim` / `gftdcojp` / `com-junkawasaki` —
を一次キーに整理されている（ADR-0007）。owner キーは「どこに push されているか」を
表すが、「**その制作物・資産が概念上どこに属すべきか**」は表さない。3 組織の
**役割・主体（誰のための、誰が主体の制作物か）** を成文化した上位 doc が存在せず、
新規リポジトリの配置判断と、既存リポジトリの配置妥当性のレビューができない。

本 ADR は 3 組織の **役割定義 + 配置判断ルール** を確定し、現状の repo 配置・資産が
適切かを判定する。

## 2. Decision

### 2.1 三組織の役割定義（主体軸）

配置の一次基準は「**主体（subject）= 誰が／何が制作物の中心か**」。

| Org | 主体 | 役割 | 産物の受け手 | プロセスへの人間介入 |
|---|---|---|---|---|
| **etzhayyim** | **agent（人工有機体）** | agent を中心に設計された artificial organism。agent が*使命*を持ち、人間に対して activity を自律実行し、loop で自己改善する。データ・レポ設計も agent 中心（actor-as-organism、kotoba Datom log） | 人間が受け取る | **介入しない**（自律プロセス） |
| **gftdcojp** | **顧客組織（企業）** | 企業・組織が使うことを前提にした app / UIUX。ビジネス・契約が関わるもの | 顧客組織（人間が操作） | 人間が操作・意思決定する |
| **com-junkawasaki** | **人 と agent の双方** | 双方が使う再利用可能ライブラリ・substrate、および河崎純真個人の研究・資産 | 人 と agent | 人が直接利用・開発する |

### 2.2 配置判断ルール（decision rule）

新規・既存リポジトリは次の順で判定する（上から最初に該当したものを採用）:

1. **企業・組織が操作する app、または ビジネス・契約に直接結びつく資産か？**
   → **gftdcojp**。（ERP/PLM、経営ツール、企業 SaaS、契約書・営業資料・企業データ archive）
2. **使命を持ち、人間に対して自律的に activity を行う *デプロイされた organism* か？**
   （人間がプロセスに介入しない／agent が主体）→ **etzhayyim**。
3. それ以外（**再利用可能なライブラリ・runtime・substrate**、または **河崎個人の研究・
   サイト・アセット**）→ **com-junkawasaki**。

### 2.3 境界原則 — 「ランタイム(lib)」と「デプロイされた organism」を分ける（最重要）

agent 関連コードは etzhayyim と com-junkawasaki にまたがって見えるが、次の原則で一意に切れる:

- **agent を *作るための* 機械（ランタイム / ライブラリ / substrate）** は、人も agent も
  使う再利用部品 → **com-junkawasaki**。たとえ「agent コード」であっても。
  例: `langgraph-clj` `langchain-clj` `computer-use-clj` `browser-use-clj` `kotodama`
  `kototama-clj`（organism *runtime*）`kotoba` stack。
- **使命を帯びて自律的に動く *organism そのもの* のデプロイ** → **etzhayyim**。
  例: etzhayyim/root の UNSPSC 18,342 actor 群（organism *deployment*）。
  そのランタイム（kotodama/kototama-clj）は com-junkawasaki に置いたままでよい。
- **人間（組織）が操作する UI を持つ app + 契約** → **gftdcojp**。

→ 同じ「actor/agent」語でも、*部品* は com-junkawasaki、*使命を持つ稼働体* は etzhayyim、
*企業が操作する画面* は gftdcojp。kototama-clj（lib, com-junkawasaki）≠ root の organism
（deployment, etzhayyim）が両立するのはこの原則による。

### 2.4 owner キー（ADR-0007）との関係

ADR-0007 の `orgs/<owner>/<repo>` レイアウトは維持する。本 ADR の概念タクソノミが
owner と一致しない場合、**概念タクソノミが「あるべき配置」の正本**とし、差分は
`:relocate` 候補として記録する。物理移動（= GitHub org transfer）は outward-facing で
不可逆なため、本 ADR では**判定のみ**を行い、移送は owner の承認を要する（特に
legal-hold 対象は ADR-0005 によりオーナー承認なしに移動・改名しない）。

## 3. 現状配置の判定（Verdict）

凡例: ✅ fit ／ 🟡 fit（注記あり）／ 🔴 relocate 候補。詳細は companion `.edn` を正本とする。

### etzhayyim（agent 有機体）
| repo | 判定 | 理由 |
|---|---|---|
| `root` | ✅ | organism monorepo 本体（actor-as-organism / Kaizen 自己改善 loop / mission charter / 人間へ自律 activity・人間非介入）。定義そのもの |
| `_modelbake` | 🟡 | etzhayyim 用 model-baking 作業ディレクトリ。機能的には fit。将来 `root/70-tools` へ畳む候補 |

### gftdcojp（企業 app + ビジネス・契約）
| repo | 判定 | 理由 |
|---|---|---|
| `ai-gftd-apps-gftdcojp` | ✅ | gftd.ai の企業向け AI agent platform |
| `app-aozora` | ✅ | 企業向け app |
| `gftd-keiei-sim` | 🕘 | 履歴化。経営判断/HTR 設計は `cloud-itonami` keiei lane へ吸収。standalone source tree は 2026-06-29 retired |
| `kyber-plm` | 🕘 | 履歴化。PLM/ERP/MES 語彙と不変条件は `cloud-itonami` compat/history model へ吸収。standalone source tree は 2026-06-29 retired |
| `m365-archive` | ✅ | gftd 法人の M365 業務データ archive（ビジネスデータ） |
| `minimax-m2-modal` | 🟡 | LLM 自前ホスト評価 harness。`.cljc` 履歴資産として残し、cloud-murakumo の vLLM serve 参照実装に寄せる |
| `ai-gftd-lf-case-lingling` | ✅（2026-06-23 移送）| Gftd Japan 被告の LingLing 訴訟ケース（企業の法務・契約）。com-junkawasaki から org transfer 済 |
| `_intake` | ✅ | 企業の契約書・営業資料・入社書類の staging。gftd の典型 |

### com-junkawasaki（ライブラリ + 個人）
| repo | 判定 | 理由 |
|---|---|---|
| `langchain-clj` `langgraph-clj` `comfyui-clj` `browser-use-clj` `computer-use-clj` | ✅ | portable Clojure agent ライブラリ群（人も agent も使う部品。§2.3） |
| `kotoba` `kotoba-code` `kotoba-topology` `kototama-clj` `kotodama` | ✅ | kotoba stack（compiler / 言語 / organism *runtime*）。再利用 substrate |
| `kami-engine` `kami-engine-sdk` | ✅ | 再利用可能な game/robotics エンジン + SDK |
| `office-causal` `moex` `systemofsystem` | ✅ | ライブラリ / 研究コード（MIT / DOI 公開） |
| `2604-linde` `spirit-in-physics` `260208-spirit-in-physics` | ✅ | 河崎個人の数学・物理研究 |
| `webmaster` | ✅ | junkawasaki.com 個人サイト |
| `manimani` | ✅ | 個人メール triage app（個人の生産性ツール） |
| `tanabe-3d` `yukkuri-assets-*` `mangaka-ghosthacker-assets` | ✅ | 個人アセット |
| `ghosthacker` | 🟡 | AI コンテンツ生成パイプライン。「agent が産物を作り人間が受け取る」点は etzhayyim *パターン*だが、宗教法人の organism ではなく**河崎個人の創作/商業 IP**。個人として com-junkawasaki に留める（§2.1 主体 = 個人）|
| `robotaxi-actor` | 🟡 | langgraph-clj 上の robo-taxi **actor 設計**。再利用可能な設計 lib として com-junkawasaki に留める。将来 *使命を持つデプロイ organism* 化したら etzhayyim へ（§2.3）|
| `ai-gftd-lf-case-lingling` | ✅（移送済）| **概念上 gftdcojp**。Gftd Japan を被告とする LingLing 訴訟ケース（= 企業の法務・契約）。**2026-06-23 owner 承認で com-junkawasaki → gftdcojp へ完全移送済**（GitHub transfer + submodule path/remote。pin 915d4d1 不変、旧 URL は GitHub redirect で chain-of-custody 保全）。下表で gftdcojp 側に再掲 |

## 4. Consequences

- **新規リポジトリの配置**は §2.2 のルールで一意に決まる（owner 作成前に概念で先に決める）。
- **`ai-gftd-lf-case-lingling` は 2026-06-23 owner 承認のもと com-junkawasaki → gftdcojp へ完全移送済**:
  GitHub repo transfer（User com-junkawasaki → Org gftdcojp、pin `915d4d1` 不変）+ submodule
  `git mv`（`orgs/com-junkawasaki/` → `orgs/gftdcojp/`）+ `.gitmodules`/`deps.edn` の remote URL 更新。
  legal-hold（ADR-0005）は git オブジェクト不変・旧 URL の GitHub redirect 維持により chain-of-custody
  を保全。**利益相反規程（PwC × 係争当事者性）の確認は別途継続課題**（移送はそれと独立に owner 判断で実行）。
- agent 関連の見かけ上の重複（kototama-clj 等が etzhayyim っぽい）は §2.3 の lib/deployment
  境界で解消され、**現状配置はほぼ妥当**（root 以外の agent コードは部品なので com-junkawasaki）。
- `_modelbake` と `minimax-m2-modal` は機能 fit だが整理余地あり（root 配下へ畳む / cloud-murakumo へ吸収）。
  優先度低。

## 5. Non-goals / 保留

- `_modelbake`（→ `etzhayyim/root/70-tools`）と `minimax-m2-modal`（cloud-murakumo へ吸収）の整理は優先度低・本 ADR では未実行。
- 利益相反規程（PwC 就業 × 係争当事者性）の整合確認は `orgs/personal/facts/orgs.edn` 記載のとおり継続課題。
  `ai-gftd-lf-case-lingling` の物理移送自体は owner 判断で 2026-06-23 実行済み（上記とは独立）。
