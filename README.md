# com-junkawasaki superproject

この repository は、Kotoba / Kotobase / Cloud Itonami などの component repository を
まとめる superproject です。component の配置と revision は Git submodule ではなく
west manifest で管理します。

```bash
west init -l manifest
west update --fetch smart <project-name>
```

source of truth は [`manifest/repos.edn`](manifest/repos.edn)、生成 projection は
[`manifest/west.yml`](manifest/west.yml) です。`west.yml` は手編集しません。詳しい取得・
pin・DataLad の扱いは [`manifest/README.md`](manifest/README.md) を参照してください。

## Kotoba / Kotobase

基本関係は **kotoba : kotobase = Clojure : Datomic** です。

- **Kotoba** は、effect と capability を明示する言語・実行モデルです。
- **Kotobase** は、Kotoba の不変データと query / transaction を永続化・索引する
  Datomic 型データベースです。
- 依存方向は Kotobase → Kotoba であり、Kotoba は standalone な言語として保ちます。

## Identity と authority

認証・認可を DID、account、role、wallet、UI の個別機能として増殖させず、次の一本の
判定面に集約します。

```text
VerifiedPrincipal + Intent + Effects + Grants + Policy + Context
                              ↓
                    allow | deny | challenge
                              ↓
                 runtime Capability → Receipt
```

ここで `principal` は綴りが似た `principle`（原則）ではありません。認証済みの
「誰の権限として判断するか」を表す security subject です。DID は identifier、account は
product-local な管理単位、credential は証明材料であり、どれも principal そのものとは
限りません。

用語、モデル、各層への投影、NIST CSF 2.0 との対応は次を正本とします。

- [Authority model README](90-docs/architecture/authority/README.md)
- [ADR-2608120400: Principal–Intent–Decision–Receipt authority kernel](90-docs/adr/2608120400-authority-kernel-principal-intent-decision-receipt.edn)
- [ADR-2607032500: kotoba : kotobase = Clojure : Datomic](90-docs/adr/2607032500-kotoba-kotobase-clojure-datomic-relationship.edn)
