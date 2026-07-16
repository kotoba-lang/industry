# (d) W3C DID アイデンティティ層 — 組織前提 + アクセスDAG

## 立ち位置

文書 a/b/c の土台となる「誰が誰か」を **W3C DID Core** で定義する。
kotoba は既に DID 寄り（`did:key`/`did:pkh`/`did:web`/`did:plc` を扱い、`DidDocumentResolver` を持つ）。
本書はそれを「組織前提・アクセスDAG」に合わせて使う方針。

## 決定事項

1. **org も account も DID subject**（account = org ノードなので両方 org = 両方 DID）。
2. **所有=木 / アクセス=DAG** を W3C DID の語彙にマップ：
   - `controller` … 所有/統制の背骨（`:org/parent` 木）。Private graph の owner を一意に決める。
   - `capabilityDelegation` / `capabilityInvocation` … アクセスの DAG（CACAO 委任で実現）。
   - `service` … org の data-graph CID / 同期エンドポイント（DID 解決で文書の在処が分かる）。
3. **DID メソッド戦略**：
   - 匿名/ローカル → `did:key`（自己完結・ネット不要・回転不可）。
   - アカウント/org → **回転可能・content-addressed DID**（genesis 鍵 + 署名付き更新ログ。
     kotoba の `commitHeadSigned`/`verifyIpnsRecord` = IPNS 式署名ヘッドで自前構築。did:plc/Sidetree 同型）。
   - `did:web:*.gftd.ai` は `alsoKnownAs` の人間可読エイリアスのみ（DNS 依存=遮断環境で壊れるため信頼の根にしない）。

## verification relationships の対応

| W3C DID relationship | 用途 | 関連文書 |
|---|---|---|
| `authentication` | passkey / WebAuthn ログイン | b |
| `capabilityDelegation` | org → メンバー委任（CACAO delegation, depth-2） | b |
| `capabilityInvocation` | メンバーが能力を行使 | b |
| `keyAgreement` | `signal:v1:` / storage DEK（本文 E2E 暗号） | a |
| `assertionMethod` | クレーム署名（将来: メンバーシップ VC） | 将来 |

## datom 表現（P0 スキーマ）

DID Document を datom 化する（verification method を reify）。`p0/office.cljc` の `schema` に対応。

```clojure
;; org / account（account = org ノード）
[org :org/did          "did:key:zAlice"]   ; 論理キー（unique :identity）
[org :org/kind         :org.kind/account]  ; root | team | account
[org :org/display-name "Alice"]
[org :org/parent       parent-org]         ; 単一。所有/統制の背骨（= DID controller）
[org :org/data-graph   "g-alice"]          ; DID service endpoint（文書の在処）
[org :org/created-at   1719000002000]

;; verification method（W3C DID Document の鍵）
[vm :vm/of   org] [vm :vm/vid "did:key:zAlice#k1"] [vm :vm/type :Ed25519]
[vm :vm/public-key-multibase "zAlice"]
[vm :vm/rel :authentication] [vm :vm/rel :capabilityInvocation] [vm :vm/rel :keyAgreement]  ; cardinality many

;; membership grant（アクセス DAG。reified capability ≒ CACAO）
[grant :grant/org acme] [grant :grant/subject "did:key:zAlice"]
[grant :grant/cap :cap/transact] [grant :grant/scope "g-acme"]
[grant :grant/role :role/member] [grant :grant/issued-at ...]
```

**DAG の要点**：同一 subject DID を持つ grant が複数 org からぶら下がれる（多重所属/ゲスト）。
`:org/parent` は単一（木）だが、`grant` は多数（DAG）。前者が所有、後者がアクセス。

## CACAO との接続（実装時）

- grant datom は**オングラフの記録**。実際の認可は CACAO delegation chain（`iss`=org-DID → `aud`/subject）が担う。
- depth-2 制約：各 org が直接メンバーへ委任（org-DID → member, depth-2）。親 org のアクセスは
  「親-DID をメンバーとして委任」する形で表し、深い transitive chain を避ける（DAG が depth-2 に収まる）。
- `:grant/cap` ↔ CACAO `resources: kotoba://op/{cap}`、`:grant/scope` ↔ `kotoba://graph/{cid}`。

## 既知のギャップ

- **回転可能 DID（did:kotoba）は未実装** … genesis + 署名付き更新ログを `commitHeadSigned` で組む設計はあるが
  メソッド仕様・リゾルバは要実装。当面は `did:key`（回転不可）で動かし、回復が要る所だけ後付け。
- **VC は将来** … メンバーシップ/ロールを `assertionMethod` 署名の Verifiable Credential にすると可搬になるが P0 範囲外。
- **did:web の誘惑** … 組織名可読で便利だが信頼の根にしない（エイリアスのみ）。
