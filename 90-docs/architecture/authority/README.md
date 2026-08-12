# Kotoba / Kotobase authority model

Kotoba の安全性の中心は、「identity を言語組み込みの特権にする」ことではありません。
identity、要求、静的 effect、委譲された grant、policy を一つの判定へ正規化し、許可時に
だけ目的限定の runtime capability を発行し、結果を receipt に残すことです。

```text
credential ──verify──> VerifiedPrincipal
request    ──────────> Intent(action, resource, context)
program    ──────────> Effects
delegation/payment ──> Grants
database   ──────────> Policy + immutable basis
                              │
                              ▼
                     Authority Decision
                    allow / deny / challenge
                              │ allow
                              ▼
              purpose-bound runtime Capability
                              │
                              ▼
                   operation ──> Receipt
```

正規の判定式は次です。

```text
Decision = decide(Principal, Intent, Effects, Grants, Policy, Context)

effective authority =
  authenticated principal
  × valid grants
  ∩ declared or inferred effects
  ∩ deployment policy
  ∩ resource policy
  ∩ tenant boundary
  ∩ audience / time / nonce constraints
  ∩ separation-of-duties constraints
```

## 用語

| 用語 | この設計での意味 | 同一ではないもの |
|---|---|---|
| entity | 人、組織、service、agent、device などの存在 | identity の表現 |
| identity | entity に関する属性、claim、binding の集合 | account、DID文字列 |
| identifier | entity や record を参照する名前。DID、UUID、CID など | 本人性の証明 |
| account | product 内の契約、設定、回復、請求の管理容器 | 人間そのもの、principal |
| credential | identity や鍵の支配を証明する材料。Passkey、CACAO、mTLS など | 長期 identity、authorization |
| signer | signature を生成した鍵の controller。検証可能な事実の一つ | 常に end-user principal とは限らない |
| subject | assertion や token が指す対象 | 実行した actor |
| principal | 今回の security decision が「誰の権限として」行われるかを示す正規化済み subject | principle（原則）、account、DID |
| actor | browser、agent、service、code など実際の実行主体 | 委譲元 principal |
| tenant | resource と policy を隔離する管理境界 | account の別名 |
| intent | action、resource、入力、context を含む要求 | 許可そのもの |
| resource | 読む、書く、実行する、支払う対象 | identifier だけの文字列 |
| ability / action | `read`、`write`、`transact` など intent と grant を照合する動詞 | resource、role |
| role | grant の集合を割り当てるための管理上の名前 | 実行時 capability |
| effect | code が試み得る／必要とする作用の静的な記述 | 権限、grant |
| grant | 誰が何をどの制約で許したかを表す署名・保存・委譲可能な権限記述 | runtime handle |
| capability | 許可後に runtime が渡す偽造困難で目的限定の handle | effect、role、plan |
| shared authority | quorum、threshold、共同承認など複数 principal の条件を満たす authority | 共有 password、共有 identity |
| DataCap | Filecoin storage allocation に用途を限定した grant の具体例 | 汎用 identity、任意の runtime capability |
| policy | bounded data として表す組織・deployment・resource の規則 | 任意コードの `eval` |
| decision | `allow`、`deny`、`challenge` と理由・不足条件 | UI の表示状態 |
| entitlement | 契約・購入・plan から生じる利用資格。grant の供給源の一つ | payment 成功そのもの |
| settlement | Stripe 等で検証済みの外部決済事実 | product 内の利用権 |
| receipt | intent、principal、actor、code/policy/grant CID、判定、結果、basis、時刻の証跡 | mutable な一般ログだけ |

`principal` を使う理由は、認可対象を human user に限定しないためです。同じ decision
kernel が人、service、agent、device、匿名主体を扱えます。また account と identity を
切り離せるため、一人が複数 account を持つ場合、service が account を持たない場合、agent が
人から委譲される場合も表現できます。公開 access も例外ではなく anonymous principal と
空の grants として扱います。

## 似た語の境界

- `effect` は「この code は network write を必要とする」という静的要求です。
- `grant` は「この principal に、この resource への write を、期限まで許す」という
  委譲可能な権限記述です。
- `capability` は decision 後に provider が受け取る runtime handle です。raw secret や
  ambient filesystem/network access を渡しません。
- `actor` は実際に動いた主体、`principal` はその実行が誰の権限として評価されたかです。
  agent delegation では両方を receipt に残します。
- DoDAF の `Capability` は mission/business 上の「達成可能性」です。この文書の runtime
  capability とは別 namespace であり、混同しません。
- Ethereum/Filecoin の `signer` は署名鍵を制御した主体を示しますが、relayer、session key、
  multisig を考えると product の principal や実行 actor と常に同一ではありません。

## Clojure / Datomic としての実装形

policy、intent、grant、decision、receipt は EDN/data として表し、任意の policy code を
network から `eval` しません。Kotobase/Datomic 側は immutable basis における事実を Datalog
で検索し、Kotoba の純粋で bounded な `.cljc` kernel が最終判定します。これにより同じ入力と
basis は同じ decision になり、再現・監査・time travel ができます。

```clojure
{:decision/status :challenge
 :decision/reason :authn/step-up-required
 :decision/missing [{:grant/action :payment/refund
                     :grant/resource [:payment/id "pi_..."]}]
 :decision/policy-cid "bafy..."
 :decision/basis 104233}
```

`challenge` は deny の曖昧な別名ではなく、Passkey step-up、追加承認、利用者同意などで
満たせる不足条件を機械可読に返します。UI はこの decision を描画しますが、UI 自体を
enforcement point にはしません。

### R0–R1 implementation（2026-08-12）

authority の semantic owner である `kotoba-lang/aiueos` に、純粋CLJC kernel
`aiueos.authority/decide` と既存broker統合 `aiueos.broker/decide-authority` が着地しました
（aiueos `f4c492a53ce81d212089ced5c1154f0975018d1e`、PR #145）。

- closed EDN inputでprincipal、actor、intent、effect、grant、policy、contextを分離する。
- `:allow` / `:deny` / `:challenge`を返し、許可時はruntime handleそのものではなく、host
  brokerがhandle化するpurpose-bound capability specificationを返す。
- 既存のcode/capability admissionを必ず先に実行し、そのdenyをidentity、role、payment、
  別grantで昇格できない。
- effectはcaller入力から受け取らず、admission済みmanifestのimports/effectsからのみ導く。
- canonical receiptはclosed shapeで、raw credential/private key/bearer tokenのfieldを持たない。

R1では `network-awai/net-kotobase` PR #410（merge
`4fed6101c74fbde11a10e385daac13aa76ce44d2`）に credential adapter が着地しました。

- authn は Passkey、email、OAuth/OIDC、CACAO、service token、legacy session を、raw
  credential を含まない closed `principal` wire projectionへ正規化する。
- gateway は nested actor/account/tenant を既に検証済みのviewerへ再bindingし、unknown field、
  method、assurance、identity disagreementをfail closedにする。
- direct CACAO/JWT と authn service binding は同じnamespaced principal constructorへ収束する。
- assuranceは Passkey=`high`、low method + TOTP=`substantial`、legacy=`low`。旧sessionを
  logoutせずstep-up可能な縮退として扱う。

authn 43 tests / 283 assertions、gateway 385 tests / 1882 assertions、両release build、
87-input bundle manifest整合を確認済みです。まだ未移行なのは、gatewayからR0 kernelを呼ぶ
adapter、Kotobaseでのbasis-bound policy/grant検索とreceipt永続化、payment settlementから
entitlement grantへの変換、UIのchallenge描画です。

## 各層への投影

| 層 | 責務 |
|---|---|
| language/compiler | effect、resource、import、limit、code identity を manifest 化し、grant を静的に近似検査する |
| KIR/runtime/OS | decision 済みの purpose-bound handle だけを broker し、fuel、memory、network、filesystem を制限する |
| UI/UX | reason、missing grant、step-up、approver、cost、destination、expiry、code CID、revocation を表示する |
| communication | audience、action、resource、body CID、grants、nonce、issued/expires を持つ canonical signed envelope を使う |
| web edge | cookie、Passkey、CACAO、mTLS を一度だけ検証し `VerifiedPrincipal` と `Intent` に正規化する |
| service | raw credential を再解釈せず decision を呼び、provider 実行後に receipt を返す |
| Kotobase | immutable basis で tenant、resource、grant、revocation、idempotency、schema invariant を再検査する |
| payment | signed webhook を settlement fact にし、product policy が entitlement grant へ変換する |

edge で credential を一度だけ正規化することと、多層防御は矛盾しません。内部層は
cookie/JWT/CACAO の parser を複製せず、runtime と database は expiry、revocation、resource、
tenant、cost など変化し得る authority を再検査します。

wire canonical form は DAG-CBOR、Clojure/Kotoba 上の projection は EDN とします。
credential や secret は append-only journal に保存せず、検証結果、key reference、proof CID、
decision、receipt を保存します。

## 冗長性を減らす対象

統合するのは防御層ではなく、語彙と decision logic の重複です。

- `did`、`accountDid`、`activeDid`、`actorDid`、`viewer`、service principal を、新しい
  domain API では `VerifiedPrincipal`、`Actor`、`AccountRef`、`TenantRef` に正規化する。
- credential parser、CACAO verifier、role/route/tenant/plan policy の個別判定を edge adapter
  と一つの kernel に収束させる。
- grant、effect、runtime handle、plan entitlement をすべて `capability` と呼ばない。
- audit log、execution record、payment event を canonical receipt から用途別に projection する。
- Stripe を settlement の正本とし、ローカル DB に独立した billing truth を作らない。

compiler check、runtime check、OS isolation、edge authentication、database final check、payment
signature verification は残します。同じ意味の規則を複数実装することと、異なる trust boundary
で再検査することは別です。

## NIST CSF 2.0 との関係

このモデルは次の control を実装・検証しやすくします。

| CSF 2.0 | 寄与 |
|---|---|
| GV.RR / GV.PO | policy owner、version、role、separation of duties を data 化 |
| ID.AM / ID.RA | resource、provider、artifact、credential inventory と effect–grant 差分 |
| PR.AA-01..05 | principal/credential lifecycle、binding、authentication、assertion protection、least privilege |
| PR.DS | secret と証跡を分離し、resource policy を最終 storage 境界でも適用 |
| PR.PS-04..05 | unified receipt と未承認 artifact/provider の拒否 |
| DE.CM / DE.AE | receipt stream による監視と anomaly detection |
| RS.MI | grant/provider/code の revoke、quarantine |
| RC.RP | immutable basis、snapshot、receipt replay による復旧検証 |

ただし、言語機能やこの ADR だけで NIST CSF 適合にはなりません。identity proofing、鍵の
custody/recovery、物理 access、教育、vendor risk、incident communication、法務・privacy、
backup exercise、policy review は product/OS/運用/組織側の責務として残ります。

参照: [NIST Cybersecurity Framework 2.0 (NIST CSWP 29)](https://doi.org/10.6028/NIST.CSWP.29)

正本の決定、invariant、移行規則は
[ADR-2608120400](../../adr/2608120400-authority-kernel-principal-intent-decision-receipt.edn) を
参照してください。
