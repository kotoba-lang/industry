# (c) svgraph / office-causal → kotoba datom 統合設計

## 目的

svgraph（SVG→編集可能 PPTX / IR）と office-causal（Office 文書の因果グラフ抽出）の出力を、
**ブラウザ内で kotoba datom に取り込む**。これにより：

- オフィス文書の**因果構造 / MECE / so-what** を kotoba の `datomicQ`（Datalog）で横断クエリできる。
- 文書間（コーパス）の因果リンクを content-addressed なグラフとして蓄積できる。
- **アップロードゼロ**（解析もインジェストもブラウザ内）→ 既存の「制限環境で動く」楔を維持。

これが gftd.ai オフィスの**差別化機能**：「ただのオフィス互換」ではなく「意思決定の構造を見せるオフィス」。

## 出力スキーマ（実コードより）

### office-causal（統合の主役 — content-addressed で datom 主体に最適）

`src/types.ts`：

```ts
type DataId = `ocz1:${string}`;                 // FNV-1a(`${part}/${path}|${stableKey}`) → base32
interface OoxmlNode { id: DataId; meta: Meta }
interface Meta {
  kind: "document"|"slide"|"shape"|"sheet"|"cell"|"range"|"chart"|"paragraph"|"table"|"image"|"entity"|"claim";
  part: string; path: string; label?: string; text?: string; value?: string|number;
  bbox?: BBox; source?: {app,ooxmlTag}; tags?: string[]; provenance: string[];
}
interface Edge { id: string; kind: "contains"|"references"|"derives-from"|"mentions"|"causes";
                 from: DataId; to: DataId; weight?: number; causal?: CausalAnnotation }
interface CausalAnnotation { polarity:"+"|"-"|"?"; mechanism:string; confidence:number;
                 evidence:Evidence[]; lag?:string; status:"hypothesis"|"supported"|"refuted" }
interface Evidence { nodeId: DataId; quote: string }
```

公開 API：`analyze(input, opts) -> AnalyzeResult{ graph, export(fmt), report() }`、
`buildStructuralGraph()`（LLM なし・決定的）、`embedFile()`、`payloadToGraph()`。
埋め込みは zip 内 `ocz/causal.jsonl`（**1 行 1 レコード、append-only**）：

```
{"t":"meta","version":1,"generator":"office-causal"}
{"t":"node","id":"ocz1:...","kind":"shape","part":"...","path":"...","text":"..."}
{"t":"edge","id":"...","kind":"causes","from":"ocz1:...","to":"ocz1:...","causal":{...}}
{"t":"node-del","id":"ocz1:..."}
```

### svgraph（補助 — 識別子が不安定なので扱いに注意）

`docs/app.d.ts`：

```ts
type SVGraphNode = { node_id:string; tag:string; attributes:Record<string,string>;
  data:Record<string,string>; metadata:{text?,json?}; dependencies:Dependency[];
  children:SVGraphNode[]; text:string|null };
type SVGraphDocument = { kind:"svgraph"; version; root:SVGraphNode; presentation:...; coverage:... };
```

公開 API：`buildSVGraph(svg)`、`svgToPptx(svg)`、`buildOfficeCausalPayload(svgraph)`、
`buildOfficeCausalJsonl(svgraph)`（**svgraph → office-causal 形式へ橋渡し済**）。

## 識別子の安定性（datom 主体に使えるか）

| 値 | 安定 | content-addressed | datom 主体に使う？ |
|---|---|---|---|
| `office-causal DataId (ocz1:)` | ✅ | ✅ FNV-1a | **YES（主キー）** |
| `office-causal Edge.id` | ✅（from/to/kind 由来） | 実質 | reify した edge の主キー |
| `meta.kind / part / path` | ✅ | implicit | 属性・スコープに |
| `meta.text / tags / confidence / evidence` | ✗（編集で変化・ML 生成） | ✗ | 属性に（識別には使わない） |
| `svgraph node_id (n0.1)` | ✗ 連番・DOM で再採番 | ✗ | **NO** |
| `svgraph tag` | ✅ | implicit | 属性に |

**結論**：office-causal の `ocz1:` をそのまま kotoba エンティティ ID に使うのが正解。
svgraph は `buildOfficeCausalPayload()` で **office-causal 形式に変換してから**取り込み、`ocz1:` を得る。
svgraph 単独の `node_id` は datom 主体にしない（取り込むなら content-address を別途計算）。

> 注意：FNV-1a は暗号学的ハッシュではない（衝突耐性が低い）。kotoba は内部で sha2-256 CID を使う。
> 取り込み時は `ocz1:` を**論理キー属性 `:ocz/id`** として持ち、kotoba エンティティ（CID）は
> `commit()` が払い出す content-addressed CID にする（下記マッピング参照）。

## datom スキーマ

```clojure
;; ノード（OoxmlNode）。entity は kotoba CID、:ocz/id に ocz1: を保持して再取込で一致させる
[e :ocz/id        "ocz1:abc..."]        ; 論理 ID（idempotent 取込のキー）
[e :ocz/kind      :ocz.kind/claim]      ; document|slide|shape|...|entity|claim
[e :ocz/part      "ppt/slides/slide5.xml"]
[e :ocz/path      "p:sld/.../p:sp[3]"]
[e :ocz/label     "原材料費"]
[e :ocz/text      "原材料費が前年から上昇した"]   ; 機密なら assertEncrypted
[e :ocz/value     12.3]
[e :ocz/tags      "cost"] [e :ocz/tags "material"]   ; cardinality many
[e :ocz/provenance "structural"] [e :ocz/provenance "causal"]
[e :ocz/source-doc doc-e]               ; 取込元文書（コーパス横断用）

;; エッジは属性を持つので reify（エッジ自体をエンティティ化）
[edge-e :edge/id        "causes:ent:...->ent:..."]
[edge-e :edge/kind      :edge.kind/causes]   ; contains|references|derives-from|mentions|causes
[edge-e :edge/from      e-src]               ; ノードエンティティ参照
[edge-e :edge/to        e-dst]
[edge-e :edge/weight    0.7]
;; 因果注釈（kind=causes のみ）
[edge-e :causal/polarity   "+"]
[edge-e :causal/mechanism  "原材料費→製造コスト"]
[edge-e :causal/confidence 0.7]
[edge-e :causal/status     :causal.status/supported]
[edge-e :causal/lag        "Q1"]
;; 証拠（複数）も reify
[ev-e :evidence/of edge-e] [ev-e :evidence/node e-q] [ev-e :evidence/quote "…上昇した"]
```

`causal.jsonl` の各行が datom 群に素直に対応する（`t:node`→ノード datom、`t:edge`→reified edge、
`t:node-del`→`:ocz/deleted true` の tombstone）。**append-only JSONL ≒ kotoba の tx ログ**なので相性が良い。

## インジェスト経路（すべてブラウザ内・アップロードなし）

```
[ユーザーが .pptx/.xlsx/.docx をドロップ]
   │  office-causal analyze(file)  （transformers.js + Gemma, ローカル WebGPU）
   ▼
CausalGraph { nodes, edges }
   │  graph → datom 変換（ocz1: を :ocz/id に、edge を reify）
   ▼
KotobaNode.transact(datoms_json)  →  commit()  →  commitToIdb()
   │
   ▼  以後 datomicQ で横断クエリ可能（下記）。同期はログイン時のみ（文書 b）
```

idempotent 取込：同じ文書を再解析しても `ocz1:` が一致 → `:ocz/id` で既存エンティティに
retract+assert（重複しない）。kotoba の dedup キー（entity CID + attr + value）とも整合。

## ラウンドトリップ（.ocz.pptx ⇄ datom）

- **取込**：`.pptx` → `analyze()` / `readDataPart()`（既存 `ocz/causal.jsonl` があればそれを `payloadToGraph()`）→ datom。
- **書き戻し**：kotoba datom → `EmbeddedPayload` → `embedDataPart()` で `.ocz.pptx` に埋め込み（OOXML 互換のまま）。
  → PowerPoint で開ける普通の pptx + 解析が同梱。**ベンダーロックインなし**。
- svgraph 経由：kotoba 上で編集した slide を `svgToPptx()` で**編集可能 PPTX** として書き出し（他社にない）。

## 差別化が効くクエリ例（datomicQ）

```clojure
;; 1) so-what: あるエンティティから因果連鎖（N-hop）を辿る
[:find ?to ?mech ?pol
 :in $ ?from
 :where [?edge :edge/kind :edge.kind/causes] [?edge :edge/from ?from]
        [?edge :edge/to ?to] [?edge :causal/mechanism ?mech] [?edge :causal/polarity ?pol]]

;; 2) refuted な因果主張（監査）= 文書中の怪しい主張
[:find ?label ?mech
 :where [?e :causal/status :causal.status/refuted] [?e :causal/mechanism ?mech]
        [?e :edge/from ?n] [?n :ocz/label ?label]]

;; 3) コーパス横断: 複数文書にまたがる同一エンティティの因果（tensor network 的）
[:find ?doc1 ?doc2 :where [?e1 :ocz/label ?L] [?e2 :ocz/label ?L]
        [?e1 :ocz/source-doc ?doc1] [?e2 :ocz/source-doc ?doc2] [(not= ?doc1 ?doc2)]]
```

MECE チェック・top-effects・サイクル検出は office-causal の `report()` を取り込み時に走らせ、
結果も datom 化（`:analysis/*`）すれば UI で再利用できる。

## 段階計画

| Phase | 内容 | 完了条件 |
|---|---|---|
| 0 | `CausalGraph` → datom 変換器（TS or CLJS） | サンプル corpus.graph.json が round-trip 一致 |
| 1 | ブラウザで `analyze()` → `transact()` → `commitToIdb()` | ドロップ→ローカル datom 化（無送信を DevTools で確認） |
| 2 | `datomicQ` で so-what / refuted / コーパス横断クエリ | 上記 3 クエリが返る |
| 3 | svgraph `buildOfficeCausalPayload()` 経由の取込 | SVG/slide も同一スキーマで入る |
| 4 | 書き戻し（datom → `embedDataPart()` → `.ocz.pptx`） | PowerPoint で開けて解析同梱 |
| 5 | 編集可能 PPTX 書き出し（`svgToPptx`）+ オフィス UI 連携 | slides.gftd.ai で「分析付き編集」 |

## 既知のリスク / 正直なギャップ

- **FNV-1a は非暗号学的** … 衝突可能性。`:ocz/id` は論理キーに留め、物理 ID は kotoba CID（sha2-256）に。
  必要なら取込時に `part/path/text` から sha2-256 を再計算して `:ocz/cid` を併記。
- **svgraph node_id 不安定** … 単独取込は避け、office-causal 形式に変換してから。
- **LLM 注釈はブラウザ内** … Gemma/WebGPU 依存。非対応端末は WASM フォールバック（遅い）か構造グラフのみ
  （`buildStructuralGraph()`、LLM 不要・決定的）に切替。
- **暗号化と検索の緊張** … 機密 `:ocz/text` を `assertEncrypted` にすると `datomicQ` で全文検索不可。
  構造/因果メタ（kind/edge/polarity 等）は平文に保ち、本文だけ暗号化する分離が現実的。
- **大規模コーパス** … office-causal は O(n) 構造層 + LSH だが、kotoba 取込のコミット粒度を文書単位に
  まとめないと CommitDag が肥大化する。文書ごと 1 commit 推奨。
