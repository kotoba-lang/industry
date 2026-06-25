# personal/prov — PROV-O 来歴サイドカー (ADR-0010 L2)

封緘済み証拠・派生事実の来歴を W3C PROV-O (JSON-LD) で記録する。
ファイル名: `<cid>.prov.jsonld`(対象 blob の sha256)。`context.jsonld` を `@context` に使う。

役割分担 (ADR-0010「1事実1表現」):
- 事実そのもの → EDN datoms (`bin/datomic/`)
- **どこから来たか・誰が・いつ・どう処理したか → ここ (PROV-O)**
- 裁判所・税理士など対外提出時は、このサイドカー + OTS proof + blob で改ざん不能な証拠チェーンになる

## テンプレート

```jsonld
{
  "@context": "context.edn",   // 語彙SSoT (EDN; 必要なら JSON-LD context を生成)
  "@graph": [
    { "@id": "kj:blob/<cid>",
      "@type": "Entity",
      "cid": "<sha256>",
      "annexKey": "MD5E-...",
      "otsProof": "<cid>.ots",
      "case": "kj:lit/lingling",
      "wasGeneratedBy": "kj:activity/seal-2026-06-11" },

    { "@id": "kj:activity/seal-2026-06-11",
      "@type": "Activity",
      "used": "kj:account/jun784",
      "startedAtTime": "2026-06-11T07:30:00+09:00",
      "wasAssociatedWith": "kj:agent/launchd-mail-sync" },

    { "@id": "kj:agent/launchd-mail-sync", "@type": "Agent" }
  ]
}
```

導出事実(例: メールから抽出した obligation)は
`Entity(obligation) --wasDerivedFrom--> Entity(email cid)` を張る。
Datomic 側の `:prov/derived-from` は同じ辺の最小ミラー(クエリ用)であり、正本はこちら。

次の実装: `personal/bin/seal-evidence.sh` が封緘時に本サイドカーを自動生成する (ADR-0010 移行ステップ2)。
