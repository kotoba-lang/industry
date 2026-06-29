# ADR-0010: Life Graph — EDN事実層 + Datalogビュー + PROV-O来歴の3表現アーキテクチャ

> **更新 (2026-06-13, ADR-0013)**: L1 の JSONL は EDN-lines(1 行 1 EDN マップ)へ全面移行し JSONL は除去。360,999 レコード round-trip 検証済み。クエリは kotoba-datomic(埋め込み)+ clojure ローダー。以下の本文中「JSONL」は歴史的経緯。

- Status: Accepted (2026-06-11; 一部実装済 2026-06-13 — 下記「実装ログ」参照)
- 関連: ADR-0001 (orchestrator), ADR-0003 (warehouse), ADR-0005 (証拠保全), ADR-0009 (mail ingest), ADR-0013 (clj-agent-stack), analysis/260611-wellbecoming-minimax-shannon.md

## 課題

メール・通知・請求・訴訟・事業・家族の情報が複数形式(JSONL / JSON-LD / Python リテラル / Markdown / TOML)に分散し、
「人生で大事なこと(のどか・健康・訴訟防御・収益化)」への注意が、受信ストリームのノイズに食われている。
膨大な処理量を前提に、**人間が raw ストリームを見ない**で済む情報処理環境を作りたい。

現状の表現の分散:

| 情報 | 現在の置き場 | 形式 |
|---|---|---|
| 生メール/添付 | orgs/personal/mail/messages/<cid>.eml | RFC822 + annex |
| 事実(txn/loan/person…) | orgs/personal/bin/datomic/{schema,model}.edn | EDN/EAV ✅ |
| 訴訟状態 | orgs/kawasakijun/litigation_state.edn | EDN(スナップショット型) ✅ (旧 JSON-LD, 2026-06-13 移行) |
| 人生目標DAG(28ノード) | orgs/kawasakijun/reverse_topo_pregel.py | **Pythonリテラル** ❌ |
| 対応判断 | manimani data/decisions.jsonl | JSONL ✅ |
| アクション | analysis/action-register.json | JSON(annex) |
| KPI仕様 | phase4_living_system.md | Markdown ❌ |

## 決定

**「1事実1表現」の原則で3つの表現に役割を固定する。**

1. **EDN datoms (Datomic Local) = 事実層の正規表現。** すべての導出事実(取引・人物・関係・目標・期限・判断・KPI測定値)は EAV datom。JSONL append-only ログが ingest の SSoT、Datomic は再構築可能な索引(現方針を維持)。
2. **Datalog = ビューの正規表現。** 「人間が見るべきもの」は全て名前付き Datalog クエリ(`queries/*.edn`)の結果であり、受信箱ではない。クエリはコードレビュー可能・バージョン管理される。
3. **PROV-O JSON-LD = 来歴の正規表現。** 証拠封緘・派生関係(どのメールからどの事実が導出されたか、誰が・何が・いつ処理したか)は W3C PROV-O。対外提出(裁判所・税理士・将来の家族)に持ち出せる標準形式は来歴だけで良い。

```
L0 raw      .eml/.pdf/blob   CID=sha256, git-annex → B2/IPFS     (不変・暗号化)  ✅既存
L1 facts    EDN datoms       facts/*.jsonl → Datomic Local        (再構築可能)   ✅既存+拡張
L2 prov     PROV-O           orgs/personal/prov/<cid>.prov.jsonld      (封緘・派生)   ★新設
                             @context = orgs/personal/prov/context.edn (語彙SSoT, B2復号して移行)
L3 views    Datalog queries  bin/datomic/queries/*.edn            (名前付きビュー)★新設
L4 attention manimani + living actions                            (人間が見る唯一の面) ✅既存
```

依存方向は L4→L3→L1→L0 の一方向。L2 は L0↔L1 の全変換に横断的に付く。
**人間(河崎)が直接見るのは L4 と L3 の結果だけ**。L0/L1 を人間が読む必要が生じたら、それは欠けているビューの仕様である。

## スキーマ拡張 (schema-life.edn)

既存 schema.edn に追加する新しい実体(別ファイル `schema-life.edn`、load.clj は両方読む):

- **goal** — 28ノードDAGをデータ化(`reverse_topo_pregel.py` から移行)。`:goal/depends-on`(ref many)、`:goal/w-contribution`、`:goal/tau`、`:goal/kind` に `:guardrail`を追加 — **`00_nodoka_wellbeing` は最大化対象ではなく制約ノード**として表現できる。
- **obligation** — 期限付き義務(裁判期日・書面〆切・支払期日・更新期限)。attention の第一の駆動源。`:obligation/due`、`:obligation/severity`、`:obligation/case`/`:obligation/goal` ref。
- **decision** — manimani decisions.jsonl と Claude/人間の意思決定を datom 化。`:decision/policy`、`:decision/input`(ref→email等)、`:decision/agent`。判断履歴がクエリ可能になり、「過去の自分の捌き方」が学習データになる。
- **kpi** — 測定値の時系列(`:kpi/metric :nodoka.time`、`:kpi/value`、`:kpi/at`)。living system の sensor 出力先。
- **person 拡張** — `:person/id`(slug, unique)+`:person/emails`(many)。**iCloud Hide-My-Email リレー(`*_2kwm5vmzyx9343_*@icloud.com`)や `jun784+tag@` で同一人物が分裂している現状のエンティティ解決**が目的。`:person/relation`(:family/:lawyer/:counterparty/:vendor/:friend/:self)と `:person/attention-class` を持つ。
- **prov 最小埋め込み** — `:prov/derived-from`(ref many)、`:prov/activity`(string)、`:prov/agent`。datom 側にも最小限の来歴を持たせ、詳細は L2 サイドカーへ。

## 注意ファイアウォール (L4 ポリシー)

クラスごとに SLO を宣言し、manimani の自動分類とラベル体系をこれに合わせる:

| attention-class | 例 | SLO | 経路 |
|---|---|---|---|
| `:family` | 学校・保健室・のどか関連・美加恵 | **即時通知、絶対に自動アーカイブしない** | push |
| `:legal-deadline` | 期日・書面〆切・弁護士からの質問 | 当日 | 朝のattention-queue先頭 |
| `:money-anomaly` | 決済失敗・延滞・残高警告 | 24h以内に**集約して**1件 | 日次ダイジェスト |
| `:business` | 顧客・パートナー | 48h | attention-queue |
| `:vendor-routine` | 領収書・利用明細・定期通知 | 人間は見ない | 自動 datom 化のみ |
| `:cold-inbound` | 営業・探りメール(例: openanyip) | 週次バッチ、自動返信しない | 週次レビュー |

原則: **通知の数は受信量ではなくクエリ結果の行数で決まる。** RunPod が同文面を10通送っても `:money-anomaly` は1行。

## 採用しない案

- 全部 JSON-LD: 来歴・対外交換には最適だが、クエリ層が貧弱でアドホック分析の往復が遅い。
- 全部 RDF/SPARQL: ツールチェーンが重く、ローカル単独運用(本warehouseの制約)と相性が悪い。
- Datomic を SSoT 化: 既方針どおり JSONL ログを SSoT に維持。索引は使い捨て・再構築可能であることがスケールと災害復旧の要。
- W Protocol/AT Protocol への統合: gftdcojp 事業基盤と個人 warehouse の結合は係争中はむしろリスク(ADR-0005 の分離原則)。

## スケール方針

- ingest は ADR-0009 の launchd 日次 + CID 重複排除のまま。datom 化はバッチ(`clojure -M -m warehouse.load`)で冪等再構築。
- Datomic Local (:mem) は ~10^6 datoms まで現実的。超えたら :storage-dir をディスクに切替→年次パーティション。エンジン交換(datahike/datascript/XTDB)は schema.edn が宣言的である限り低コスト。
- known_issues の解消を L1 ルール化: txn dedup-key = (date, vendor, amount, account)、loan は明細から再計算。

## 移行ステップ

1. `schema-life.edn` + `queries/attention.edn` 追加(本ADRと同時) ✅
2. `prov/context.edn` 追加(語彙SSoT)、`seal-evidence.sh` が封緘時に `<cid>.prov.jsonld` を吐くよう拡張
3. `reverse_topo_pregel.py` のノード定義を `orgs/kawasakijun/goals.edn` へ抽出(Python は EDN を読む側に)
4. manimani decisions.jsonl → decision datoms の loader、attention-class を manimani の分類プロンプトに接続
5. living sensors の出力先を kpi datoms に統一(最初の実装は `nodoka.time`)
6. litigation_state.edn を「case datoms + PROV サイドカー」から生成される派生物に変更(手書きスナップショットの廃止)

## 実装ログ

- **2026-06-13 — 手書きSSoTの形式統一 (JSON-LD / TOML → EDN)。** 「1事実1表現」を表現形式にも適用し、
  人手管理のSSoTを EDN に寄せた(既存 `goals.edn` / `facts/*.edn` と同流儀)。
  - `orgs/kawasakijun/{profile,activities,roadmap,financial_state,litigation_state,jk_state,gftd_state,subscriptions_state,photos_timeline}.jsonld` → `*.edn`
  - `orgs/personal/prov/context.jsonld` → `orgs/personal/prov/context.edn` (B2/git-annex `encryption=hybrid` を GPG鍵 `09EE8413…` で復号して移行)
  - `orgs/personal/accounts/registry.toml` → `registry.edn`、ルート `deps.toml` は deprecated/pruned 済みで `deps.edn` が現行正本
  - 変換規則: `@id→:id` / `@type→:type`(schema.orgクラスはkeyword) / `kj:X→:kj/X` / schema.org語彙→bare keyword / 日本語ラベルは文字列。元データと逆変換JSONの深比較で値の無損失を確認。
  - **消費側 Python を babashka へ移植 (ADR-0013)**: `orgs/kawasakijun/pregel_planner.py`→`pregel_planner.clj`、`orgs/personal/bin/registry`(tomllib→bb edn)。出力等価をテスト確認。
  - 未了: 手書きスナップショット(state.edn)の datoms+PROV派生物化(ステップ6)、`reverse_topo_pregel.py`/`phase2_*.py`/ingest系Pythonの clj/bb 化。

## 帰結

- 良: 人間の注意がクエリ駆動になり、受信ストリーム量と認知負荷が切り離される。証拠来歴が標準形式になり訴訟・税務・相続(generation-τ)に持ち出せる。目標DAG・判断・KPIが同一グラフに乗り、minimax/シャノン分析が手作業からクエリになる。
- 悪: 表現が3つあること自体の学習コスト。loader の保守。エンティティ解決(alias統合)は継続的な人手修正が要る。
