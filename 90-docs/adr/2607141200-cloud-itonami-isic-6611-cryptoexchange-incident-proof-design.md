# ADR-2607141200: cloud-itonami cryptoexchange 設計企画 — 事故クラス駆動 incident-proof 暗号資産取引所（design-only）

**Status**: accepted（設計のみ。scaffold は L1、実装は M1 以降 — 下記 Decision 9）
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki
**Scope**: 新規衛星 `orgs/cloud-itonami/cloud-itonami-isic-6611-cryptoexchange`（本 ADR ではコード変更なし）

## Context

### 監査: cloud-itonami に cryptoexchange は未設計（2026-07-14 実測）

- 金融 Wave 0 の 18 repo（ADR-2607121200）に crypto exchange は存在しない:
  6611 = Administration of financial markets（汎用市場運営）、6612 = 証券
  ブローカレッジ、6619 = カード決済、6499 = VC ファンド。いずれの blueprint.edn /
  src にも暗号資産・交換業への言及ゼロ。
- `kotoba-lang/industry` registry にも crypto エントリなし。
- ADR-2607121000（逆トポソート 5-wave）にも crypto exchange の明示計画なし
  （6611/6612 は「kotoba log は本質的に取引台帳」との注記のみ）。

オーナー依頼（2026-07-14）: FTX のような事件、またそれ以外の cryptoexchange 事案が
**再発しないようにした** cryptoexchange を設計企画する。

### 事故レジスタ（設計の駆動源 — 事故クラスと根本原因）

| # | 事故クラス | 代表事例 | 根本原因 |
|---|---|---|---|
| A1 | 顧客資産の流用・混蔵 | FTX (2022, 〜$8B) / Celsius (2022) | 顧客資産と自己資産の混蔵、関連会社（Alameda）への貸出・運用、自社トークン（FTT）の担保算入 |
| A2 | 帳簿の裏口・特権口座 | FTX（`allow_negative` フラグ、Alameda の清算免除） | 残高を手で書き換えられる mutable DB、リスクチェックをバイパスする特権 tier |
| A3 | 支払不能の隠蔽 | Mt. Gox (2014, 850k BTC — 2011 年頃から欠損のまま営業継続) | 準備金と負債の照合が存在せず、割れても営業を続けられる構造 |
| A4 | ホットウォレット侵害 | Coincheck (2018, NEM 580億円) / Zaif (2018) / BitPoint (2019) | ホット比率過大・単一鍵・マルチシグ無し |
| A5 | 署名運用の侵害 | DMM Bitcoin (2024, 4502 BTC → 廃業) / WazirX (2024) / Bybit (2025, 〜$1.5B — Safe UI 改ざんで cold multisig が blind signing) | 署名対象を独立検証しない（UI を信頼した blind signing）、大口一括流出を止める速度制限なし |
| A6 | 単一人物リスク | QuadrigaCX (2019, C$190M — CEO 単独鍵、実態は流用済み) | 一人が鍵と帳簿を独占、外部から準備金を検証できない |
| A7 | exit scam | Thodex (2021, 〜$2B) / Africrypt (2021) | 非公開コード・非公開台帳・出金停止が外部に即時可視でない |
| A8 | AML/KYC 不備 | Binance (2023, $4.3B 和解) / BitMEX | 法域ゲート・KYC・travel rule・制裁スクリーニングの欠如 |
| A9 | 市場操作・利益相反 | wash trading 全般 / Coinbase insider listing (2022) / 取引所 front-running | 自己勘定取引、非公開の順序付け、listing の COI 不在 |
| A10 | レバレッジ増幅 | FTX デリバ・清算カスケード / Luna 余波の lender 連鎖 | 信用・デリバ・貸暗号資産が custody と同居し、損失が顧客資産に波及 |

## Decision（設計）

1. **位置付け**: ISIC 6611 の役割接尾辞衛星 `cloud-itonami-isic-6611-cryptoexchange`
   （cloud-itonami org, public, AGPL-3.0-or-later, maturity `:blueprint` から開始）。
   governor は `:cryptoexchange-governor`（fleet-wide 一意、grep 検証済み 2026-07-14）。
   既存 6611 repo は汎用市場運営のまま触らない。`-clj` 接尾辞禁止・役割接尾辞の
   命名規約（ADR-2607102200 addendum 14、先例 `cloud-itonami-iso3166-jpn-fsa`）準拠。

2. **新パターンを作らない**: fleet の既存規律にそのまま載せる —
   actor は Governor 封じ込め + append-only 監査台帳 + langgraph StateGraph
   （skill `build-actor`）、HARD violation は人間 approve でも越えられない
   （ADR-2607011000 と同型）、判定核は integer-coded fail-closed の safety kernel
   （ADR-2607121200 の 6511 パターン）、手続き実行は propose→approve→merge effects
   lifecycle（ADR-2607122500）、runtime は CLJS-first の portable `.cljc`（ADR-0016）。

3. **不変条件 INV-1..14 と事故クラスの対応（traceability — 本設計の核）**:

   | INV | 不変条件 | 防ぐ事故 | enforcement |
   |---|---|---|---|
   | INV-1 | **full-reserve solvency**: 資産種別ごとに attested 準備金 ≥ 顧客負債。割れたら自動で新規預入・取引を halt（出金処理は継続） | A1 A3 | solvency kernel（HARD、人間 approve で override 不可） |
   | INV-2 | **no-rehypothecation**: 顧客資産の貸出・運用・担保化・関連会社移転ゼロ | A1 | conflict kernel の lending/transfer フラグ = 定数 0 |
   | INV-3 | **自社発行トークン不算入**: 準備金にも担保にも数えない | A1 (FTT) | solvency kernel の入力規則 |
   | INV-4 | **特権ゼロ**: リスクチェックをバイパスする口座 tier・清算免除が存在しない。自己勘定取引ゼロ | A2 A9 | conflict kernel（prop_trading / privileged_bypass = 0） |
   | INV-5 | **残高 = append-only event log の純 fold**: 手動 UPDATE 経路がコードベースに存在しない。補正は dual-control effect として台帳公開 | A2 | kotoba-datomic event sourcing（アーキテクチャ上 mutation API が無い） |
   | INV-6 | **conservation**: Σbalance == Σdeposit − Σwithdrawal − Σfee（資産種別ごと、常時検証） | A2 A3 | conservation kernel |
   | INV-7 | **PoR + PoL 日次公開**: Merkle-sum liability tree（利用者が inclusion proof で自分の残高を検証可能）+ on-chain 準備金 attestation + solvency kernel 出力の公開 | A3 A6 A7 | 公開生成物（liabilities 側を含む — reserves だけの「PoR」は不合格） |
   | INV-8 | **custody 分散**: cold ≥ 95%（資金決済法水準）、M-of-N quorum、オペレータ・デバイス独立、単一人物では資金移動不可 | A4 A6 | custody kernel + 鍵運用設計 |
   | INV-9 | **出金ガード**: 宛先 allowlist、大口 time-lock、時間窓 velocity cap（絶対量）、hot 残高 cap | A4 A5 | custody kernel（fail-closed） |
   | INV-10 | **WYSIWYS**: 署名 payload を独立 2 系統で再構成し byte 照合してから署名。ウォレット UI を信頼しない | A5 (Bybit/WazirX) | 署名手順設計 + custody kernel の verifier 一致フラグ |
   | INV-11 | **決定論 matching + 全順序公開監査ログ**: price-time priority の単一シーケンサ、順序が event log で再現可能、第三者が wash-trade / front-running を検出可能 | A9 | matching engine 設計 + 公開台帳 |
   | INV-12 | **法域ゲート + intake KYC**: spec-basis の無い法域は 422 で正直拒否（6910 RegistrarGovernor と同型の捏造禁止 HARD）。KYC / travel rule / 制裁スクリーニングは intake gate | A8 | edge propose 時の先行 HARD check（spec-basis は `cloud-itonami-iso3166-jpn-fsa` 等の regulator 衛星から引用） |
   | INV-13 | **scope fence: spot-only full-reserve**: 信用取引・デリバティブ・貸暗号資産・ステーキング運用は本 blueprint に存在しない。導入は別 ADR + 別衛星 + 別 governor でしか不可能 | A10 | blueprint の明示的非目標（本 ADR が正） |
   | INV-14 | **実資金ゲート**: 実資金・実顧客での運用は、当該法域の交換業登録（日本: 資金決済法の暗号資産交換業者登録）+ owner の明示判断まで永久にスコープ外。agent が実資金の送金・trade を行わないのは standing authorization の安全床②でありこの ADR でも不変 | 全クラス | 運用ゲート（HARD、本 ADR + CLAUDE.md 安全床） |

4. **safety kernels 4 本**（ADR-2607121200 規律: safe-kotoba emit-ready subset、
   integer-coded、fail-closed、battery + case-count lock、façade full-matrix parity、
   `portable-cljs-test-runner`）:
   - `cryptoexchange.kernels.solvency` — 入力（資産別 reserve_minor, liability_minor,
     self_token_flag）→ 0=ok / 非0=halt-intake。self_token_flag≠0 の準備金算入は violation。
   - `cryptoexchange.kernels.custody` — 入力（hot_bp, quorum_sigs, quorum_m,
     allowlist_flag, timelock_ok, verifier_match_flag, window_outflow_minor,
     velocity_cap_minor, amount_minor）→ 0=allow / 非0=deny コード。非 0/1 flag は violation。
   - `cryptoexchange.kernels.conservation` — 入力（Σbalance, Σdeposit, Σwithdrawal,
     Σfee）→ 0/1。
   - `cryptoexchange.kernels.conflict` — 入力（prop_trading, self_token_collateral,
     privileged_bypass, lending）→ いずれか非 0 で HARD violation。
   金額はすべて整数 minor unit（浮動小数を kernel に入れない）。

5. **台帳**: kotoba-datomic append-only event sourcing を唯一の状態層とする
   （deposit / withdrawal / order / trade / fee / attestation が event、残高・板は
   純 fold）。`langchain.db` の `:db-api` 境界、MemStore ≡ DatomicStore contract test
   （Wave 0 M1 と同一物差し）。ADR-2607121000 の「kotoba log は本質的に取引台帳」を
   ここで実体化する。

6. **出金 actuation**: edge は `POST /api/{org}/{repo}/cryptoexchange/propose-withdrawal`
   で effect を enqueue するまで（`:itonami.effect/risk :external-send`）。実 broadcast は
   actor 本体の StateGraph（custody kernel 通過 + 大口は interrupt-before human
   sign-off）のみが行う。LLM→actuator 直結禁止（ADR-2607011000 同型）。approve は
   HARD violation を越えられない。

7. **PoR/PoL 生成器**: 日次 snapshot から Merkle-sum liability tree と inclusion proof、
   on-chain reserve attestation 参照、solvency kernel の判定コードを公開する静的成果物
   として emit（nbb）。「reserves のみ公開して liabilities を隠す」形式は INV-7 違反として
   design 段階で禁止。

8. **regulator spec-basis**: 日本の要件（信託保全・cold 95%・履行保証暗号資産・
   分別管理監査）は `cloud-itonami-iso3166-jpn-fsa` を citable source として
   formation.facts 方式で持つ（作り話でカバレッジを膨らませない — 6910 と同じ規律）。
   非対象法域への propose は effect を作らず 422。

9. **マイルストーン**:
   - **L0（本 ADR）**: 事故レジスタと INV-1..14 の固定。
   - **L1**: blueprint 衛星 scaffold（blueprint.edn + honest-default README + community
     files。src/test を持たず `:implemented` を僭称しない）+ `kotoba-lang/industry`
     registry `:blueprint` 登録 + industry west pin 前進。衛星自体は west 非登録が
     fleet 慣例（ishokuju/iso3166 衛星と同型 — west 管理は gftdcojp/cloud-itonami
     本体のみ）。skill `new-project-scaffold` の常時許可範囲。
   - **M1**: kernels 4 本 + battery + parity、CLJS green（6511 パターン複製）。
   - **M2**: event-sourced 台帳 + 決定論 matching + store contract + PoR/PoL 生成器。
   - **M3**: custody 統合の詳細設計（MPC/multisig プロバイダ選定、鍵セレモニー、
     WYSIWYS 手順書）。**実資金は扱わない**（INV-14 のまま）。

## Consequences

- (+) 「FTX を防ぐ」を標語でなく検証可能な不変条件に落とした: 流用は conflict kernel
  の定数 0、裏口は mutation API の不存在、支払不能は solvency kernel の自動 halt、
  blind signing は WYSIWYS フラグ — いずれも battery で回帰固定でき、HARD は人間
  approve でも越えられない。
- (+) 新規機構ゼロ。kernel 規律・effects lifecycle・governor 契約・registry/maturity
  ladder という既存 fleet 資産の複製で全 INV が enforce でき、監査面が増えない。
- (−) full-reserve + 自己勘定ゼロ + spot-only は収益性を意図的に犠牲にする
  （手数料収入のみ）。FTX 型の収益（運用・レバレッジ）を構造的に放棄するのが本設計の
  意図であり、bug ではない。
- (−) 実運用には交換業登録という法的前提があり、blueprint→実装が進んでも INV-14 が
  最後のゲートとして残る（解除はコードでなく owner + license）。
- (−) PoR/PoL は攻撃者への情報でもある（アドレス公開）。M2 で attestation の
  粒度（アドレス直公開 vs 監査人署名）を選定する follow-up。

## Addendum 1（2026-07-14）: L1 実行済み

オーナー指示「l1」により同日実行:

- 衛星発行: https://github.com/cloud-itonami/cloud-itonami-isic-6611-cryptoexchange
  （public, initial `3ebc72f`。blueprint.edn には INV-1..14 を機械可読で格納）。
- registry 登録: `kotoba-lang/industry` に `:id "6611-cryptoexchange"` を追加
  （**registry 初の role-suffix 衛星 entry** — 既に `:implemented` な class 6611 の
  下に別事業を併記する新形式。`wave-of` は division 接頭辞 "66" で wave 0 に解決、
  テストで corroborate）。`:blueprint` tier 41 → 42。15 tests / 937 assertions
  green、clj-kondo 0 errors。main 着地 `50b7f732`（feature branch → サーバ側
  マージ、branch 削除済み）。
- west pin 前進: industry `c3999a72` → `50b7f732`（API single-entry commit
  `8bb9cdb8`、blob SHA 楽観ロック、diff は当該 revision 行のみ、純前進）。

## Addendum 2（2026-07-14）: M1 実行済み

オーナー指示「next」により同日実行。衛星 repo main `7bcb482`:

- **safety kernel 4 本**（`cryptoexchange.kernels.{solvency,custody,conservation,
  conflict}`）を safe-kotoba subset（integer-coded・fail-closed・named combinators、
  ADR-2607121200 規律）で実装。battery + case-count lock は 14/22/12/13 cases、
  独立 oracle との parity matrix は 90/15120/256/81 combos、定数 drift pin
  （hot-cap 500bp == cold≥95%、min-quorum-m 2 == 単独人物禁止）。
- **façade** `cryptoexchange.governor`: keyword↔wire-code 変換のみ（判定ロジックは
  kernel 側）。boundary の boolean 変換も fail-closed — permission facts
  （allowlisted?/timelock-ok?/verifier-match?）は明示 `true` のみ許可、attestation
  facts（prop-trading? 等）は明示 `false` のみ clean（キー省略 = violation）。
- **検証**: CLJS（primary gate、`cljs.main --target node`）**24 tests / 16,605
  assertions 0 failures**、JVM（compat）同値、clj-kondo 0 errors。
- maturity は `:blueprint` のまま（kernel ≠ actor。registry 変更なし、INV-14 不変）。
- 設計上の追加確定: custody kernel の deny ladder 順序（invalid → hot-ratio →
  quorum → allowlist → timelock → verifier → velocity）、conservation kernel は
  負の残高集計を「broken でなく invalid」に分類（allow_negative 裏口の signature、
  A2）、conflict code は bit-sum（prop=1 / self-collateral=2 / bypass=4 / lending=8）。

## Addendum 3（2026-07-14）: M2 完了（/loop self-paced 実行）

オーナーの /loop 指示による self-paced 実行で、M2 の 4 切片を同日完遂。
衛星 repo main: M2a `6718197` → M2b `331cdd6` → M2c `ae28fdb` → M2d `a8b6119`。

- **M2a `cryptoexchange.ledger`**: append-only event log を唯一の状態層に
  （INV-5）。残高は純 fold、mutation API 不存在、負残高は構造的に不可能
  （overdraw を append 時拒否）、手動補正は dual-control `:adjustment` のみ。
  改竄ログは replay が、ログ迂回の残高偽造は conservation kernel が検出
  （defense in depth をテストで実証）。
- **M2b `cryptoexchange.matching`**: 全順序 order log の純 fold（INV-11）。
  price-time priority は複合キー `[price seq]` で sort stability 非依存、
  maker 価格規則（執行価格にエンジン裁量なし、INV-4）、**reject-taker 型
  自己約定防止**（crossing 領域に自 account の resting があれば 1 fill も
  執行せず拒否 — wash trading をエンジンで拒絶、A9）、cancel は所有者のみ、
  `replay-book` が第三者再計算経路。`fill->trade-event` で台帳に接続し
  約定→決済→conservation 保持を実証。
- **M2c `cryptoexchange.attest`**: Maxwell 型 Merkle-sum liability tree
  （INV-7）。root `:sum` = 顧客負債総額、利用者 inclusion proof、負の中間
  sum 拒否（sum-shrinking 対策）、奇数ノードは carry-up（複製は sum 二重
  計上のため禁止）。**reserves-only の PoR は構造的に生成不能**（artifact は
  liability root + solvency kernel 判定を必ず含む）。fixture root hash を
  テストに pin し、JVM/CLJS がバイト一致で再現することを強制。自社発行
  トークン準備金は額に関わらず insolvent（INV-3）、attestation 省略も同様。
- **M2d `cryptoexchange.store`**: MemStore ≡ DatomicStore（langchain.db =
  kotoba-datomic seam）。store は**イベントのみ**永続化し、残高・板・root は
  常に protocol read 上の fold（どのバックエンドにも materialized balance が
  存在しない = INV-5 が backend 横断で成立）。append は必ずドメイン fold の
  検証を通過（無効イベントはどのバックエンドにも痕跡を残さない）。両バック
  エンドが同一スクリプトから events/balances/book/fills/attestation root
  まで完全一致することを contract test で証明。kotoba-server への retarget
  は `:db-api` swap（langchain.kotoba-db）。
- 検証: CLJS（primary）+ JVM（compat）**47 tests / 16,810 assertions
  0 failures**（DatomicStore の CLJS 実行含む）、clj-kondo 0 errors。
- maturity は `:blueprint` のまま（actor 未実装、INV-14 不変）。残る M3 =
  custody 統合詳細設計（MPC 選定・鍵セレモニー・WYSIWYS 手順書、実資金なし）。

## Addendum 4（2026-07-14）: M3 完了 — 本 ADR の全マイルストーン完遂

衛星 repo main `7248356`（衛星内 `docs/adr/0001-custody-integration-design.md`）。
design-only、実資金・実鍵・mainnet custody なし（INV-14 不変）。要点:

- **方式選定**: cold は on-chain 検証可能な multisig（BTC descriptor/miniscript
  3-of-5、ETH Safe 3-of-5）— quorum ポリシー自体が公開検証可能で、PoR の
  `:reserve-refs` と併せて「単独人物では動かせない」ことをアドレス形式が証明する
  （anti-QuadrigaCX を运用約束でなくチェーンに置く）。hot（≤5% cap）は per-chain
  native 2-of-3。MPC-TSS は評価済み（ZF FROST / dfns cggmp21 / tss-lib）だが
  採用は保留、closed-SaaS custody は設計前提にしない。
- **鍵セレモニー**: 5 keyholder（組織・地理分散）、鍵は各自の air-gapped signer 上で
  生成し**いかなる時点でも一箇所に集まらない**、SLIP-39/steel 分散バックアップ、
  セレモニー記録（descriptor + firmware hash）を監査台帳へ。
- **dead-man recovery**: miniscript timelock（〜6ヶ月不活性で代替 4-of-7 が有効化）/
  Safe recovery module。**チェーンが強制する**dead-man switch で、発動 = incident
  として intake halt に配線。
- **WYSIWYS 手順書**: `verifier-match-flag` を 1 にできるのは「独立 2 実装が
  raw unsigned bytes から payload を再構成し byte 一致」の手順のみ。UI/PSBT
  メタデータからの導出は禁止、署名デバイス上で導出値を確認、broadcast は kernel
  全行 0 + interrupt-before human sign-off の後のみ（Bybit/DMM/WazirX 対策）。
- **kernel wire-code 対応表**: hot-bp（運用目標 ≤300 / HARD 500）、quorum、
  allowlist（追加は 48h cooling の propose→approve）、timelock（大口 24h）、
  velocity（台帳 fold の 24h rolling — DMM 型一括流出は署名が揃っても code 7）。

これで本 ADR の L0→L1→M1→M2→M3 は全て完遂。以降の実装（actor 本体・testnet
リハーサル・:implemented 昇格）と実運用は、新規スコープの ADR と INV-14 ゲート
（交換業登録 + owner 判断）の管轄であり、本 ADR からは自動連鎖しない。

## Addendum 5（2026-07-14）: actor 本体 + WYSIWYS ハーネス + PoR/PoL 公開パイプライン

オーナー指示「ok, do it」（3 項目: ①actor 本体 ②testnet WYSIWYS リハーサル
③PoR/PoL nbb 公開スクリプト）を同日実行。衛星 repo main: M4 `5534297` →
M4b `8c37ac7`。すべて design/testnet-only、実資金・実鍵・実チェーンブロード
キャストなし（INV-14 不変）。

- **actor 本体（M4）** `cryptoexchange.actor` = langgraph-clj StateGraph:
  advisor（封じ込め Exchange-LLM、proposal のみ）→ store evidence 組み立て →
  **ExchangeGovernor**（`cryptoexchange.censor`、4 kernel を合成し
  HARD>escalate>commit）→ rollout gate（`cryptoexchange.phase`）→
  commit | hold | 人手サインオフ。`interrupt-before #{:request-signoff}` で
  actuation op（出金ブロードキャスト・二重統制補正）はどの phase でも
  auto-commit しない — 出金は必ず人手で一時停止し、HARD custody 拒否
  （WYSIWYS mismatch 等）は人手にすら到達しない。commit ノードのみが store に
  書き、その append はドメイン fold で再検証。censor/phase は pure + CLJS test、
  graph は JVM 駆動（6511 と同型 — interrupt/resume を end-to-end 検証）。
- **WYSIWYS ハーネス（②の自動化可能部分）** `cryptoexchange.wysiwys`:
  custody ADR §4 の核を機械化。**独立 2 デコーダ**が raw unsigned tx バイトから
  署名 payload を再構成し byte 比較 + operator intent と照合、全一致時のみ
  custody kernel の verifier-match flag = 1。Bybit 型攻撃（intent は正しい
  アドレス、バイト列は別アドレス）は intent-mismatch → flag 0 → kernel code 6。
  物理儀式（air-gapped signer・鍵セレモニー）はテスト不能なので範囲外と明記し、
  「raw バイトから再構成して byte 一致でしか flag を立てない」規律だけを固定。
- **PoR/PoL 公開（③）** `cryptoexchange.publish` + `scripts/publish_attestation.cljs`:
  日次 artifact を生成 — 台帳を資産別 Merkle-sum attestation + 全口座の
  inclusion proof に fold し、canonical EDN + Markdown サマリを出力。
  reserves-only artifact は構造的に生成不能（INV-7）。ロジックは portable
  `.cljc`（両ゲート test 済み）、nbb シェルは I/O のみ（`npx nbb -cp` で
  end-to-end 実行確認 — overall-solvent 判定 + EDN/MD 出力を検証）。
- 検証: CLJS（primary）71 tests / 16,927 assertions、JVM（compat）77 tests /
  16,951 assertions、いずれも 0 failures、clj-kondo 0 errors。
- maturity は依然 `:blueprint`（registry 変更なし）。actor は存在するが実資金
  運用は INV-14 ゲート（交換業登録 + owner 判断）の管轄で、`:implemented`
  昇格は別途 owner 判断による。

## Addendum 6（2026-07-14）: PoR/PoL を第三者検証可能にする（JSON + 検証仕様）

衛星 repo main `8368bee`。INV-7 の「利用者が自分で検証できる」を、プロジェクトの
コードに依存せず成立させた:

- `cryptoexchange.publish/render-json` — 決定論的 JSON 出力（キーソート・
  keyword は name）。browser/CLI の検証器が EDN reader 無しで消費できる。nbb
  シェルは `.json` も `.edn`/`.md` と併せて出力。
- **重要な独立検証性の修正**: leaf preimage を colon 付き keyword 文字列
  （`:alice`）から**公開名（`alice`）**に変更（`attest/pname`）。従来は JSON が
  `"alice"` を公開する一方ハッシュは `:alice` を食っており、第三者が公開 JSON から
  leaf を再構成すると不一致になる欠陥があった。修正で「JSON の文字列 = ハッシュ
  対象」が成立。fixture root hash を再 pin。
- `docs/verify-inclusion.md` — leaf/node ハッシュ preimage・検証ウォーク・
  sum-shrinking 拒否を正確に規定。worked example は**プロジェクトコードを一切
  使わず素の `sha256sum` で root まで再現**（`leaf|alice|btc|300` と node ハッシュ
  が emit された artifact とバイト一致することを実測確認）。
- 検証: CLJS 72 tests / 16,935 assertions、JVM 78 tests / 16,956 assertions、
  0 failures、clj-kondo 0 errors。design/testnet-only、INV-14 不変。

follow-up（未着手）: 公開 JSON を消費する self-contained ブラウザ検証ページ
（第 4 の独立実装）と、artifact を配信する discovery surface。

## Addendum 11（2026-07-14）: Gnosis Safe execTransaction の WYSIWYS デコード（ETH cold 完成）

Addendum 10（ETH 直接送金）の残タスク、Safe multisig 経路を実装（衛星 `6b02c19`）。
Safe からの出金は直接送金でなく、外側 tx は Safe コントラクト宛で**実宛先は calldata
内**にある。`wysiwys-eth/decode-safe` が Safe `execTransaction` calldata を ABI デコード
して実 intent を復元:
- selector `0x6a761202`（eth-crypto の keccak256 で検証）を確認 → head 10 word +
  dynamic bytes を解析。
- **native** move → `(to, value)`。**ERC-20** `transfer(address,uint256)`
  （`0xa9059cbb`）→ `(token, to, amount)`。
- **DELEGATECALL（operation 1）と未知の inner call は fail-closed**（宛先に還元できない
  署名はしない）。
- `verify-safe` が custody kernel の verifier-match flag を駆動 → Safe 署名者は Safe
  アドレスでなく**真の受取人**を確認する。Bybit 型（外側は Safe 宛だが実宛先が intent と
  相違）は flag 0 → code 6。
- JVM 専用（`.clj`）、CLJS primary は非巻き込みを検証。テストは実 execTransaction
  calldata を組み立て native / ERC-20 / delegatecall / 未知 inner を往復検証。
- 検証: JVM 91 tests / 17,011 assertions（+4）、CLJS 71、clj-kondo 0。
- これで custody ADR §1 の ETH cold（Safe multisig）の WYSIWYS が完成。BTC/ETH 両鎖の
  cold custody 検証が直接送金 + Safe/multisig まで揃った。

## Addendum 10（2026-07-14）: real Ethereum WYSIWYS デコーダ（kotoba-lang/eth-crypto 活用）

Addendum 9（BTC）の ETH 版（衛星 `a0dd379`）。`cryptoexchange.wysiwys-eth` が
**実際の raw unsigned ETH tx（legacy / EIP-2930 / EIP-1559）をパース**して
`(to address, value)` を復元。`kotoba-lang/eth-crypto` に無い **RLP デコード方向**を
足し、アドレス整形は eth-crypto の `eip55-checksum` を再利用。`verify` が custody
kernel の verifier-match flag を駆動（Bybit 型 → flag 0 → code 6）。contract 作成
（to 空）・value/address 改竄・不正バイトはすべて fail-closed。

- **JVM 専用（`.clj`）**、wysiwys-btc と同じ actuation 境界 compat 層。CLJS primary は
  eth-crypto を巻き込まないことを検証済み。テストは実 tx を eth-crypto 自身の
  rlp-encode + eip55-checksum に対して round-trip 検証。
- **残る follow-up**: Safe multisig の `execTransaction`（実宛先が calldata 内の
  SafeTx 構造体ハッシュにある）経路。それまでは fail-closed。
- 検証: JVM 87 tests / 16,993 assertions（+4）、CLJS primary 71、clj-kondo 0 errors。
- これで BTC/ETH 両鎖に実 WYSIWYS デコーダが揃った（cold=BTC descriptor multisig /
  ETH Safe、custody ADR §1 の 2 鎖）。

## Addendum 9（2026-07-14）: real Bitcoin WYSIWYS デコーダ（kotoba-lang/btc-crypto 活用）

wysiwys の意味論スタンドインを、BTC については実装に置き換えた（衛星 `2bef035`）。
`cryptoexchange.wysiwys-btc` が**実際の raw unsigned Bitcoin tx をパース**して出力の
`(destination address, value)` を復元（P2WPKH/P2WSH/P2TR/P2PKH/P2SH 分類）、
script→address 変換に `kotoba-lang/btc-crypto` の bech32/base58 エンコーダを再利用
（btc-crypto に無い「デコード方向」だけを足す）。未知スクリプトは fail-closed。
`verify` が custody kernel の verifier-match flag を駆動し、Bybit 型攻撃
（バイト列が intent と別アドレスを支払う）は flag 0 → kernel code 6。

- **JVM 専用（`.clj`）**: btc-crypto は `byte-array` ベースなので、JVM の actuation
  境界（actor broadcast 経路は JVM）に置く意図的な compat 層。portable CLJS kernel
  経路には乗せない。CLJS primary gate は btc-crypto を一切巻き込まないことを検証済み。
- テストは実 tx を btc-crypto 自身のエンコーダと実秘密鍵で round-trip 検証。
- 正直な範囲: 「独立 2 ツールチェーン」は production 目標で、現状は実デコーダ 1 系統 +
  portable スタンドインの byte-compare 規律。**ETH（RLP/EIP-712）は follow-up**。
- 検証: JVM 83 tests / 16,971 assertions（+6）、CLJS primary 71、clj-kondo 0 errors。

## Addendum 8（2026-07-14）: 共通化 — Merkle-sum を kotoba-lang に抽出

`cryptoexchange.attest` の Merkle-sum ツリー（tree/inclusion-proof/verify）を
共有 lib **`kotoba-lang/merkle-sum`**（zero-dep portable `.cljc`、hasher 注入）へ
抽出し west 登録、attest はそれに依存化（PoR ドメイン部分のみ保持、挙動不変・pin
済み root 不変）。あわせて crypto/merkle プリミティブは既に kotoba-lang に在ること、
actor Store seam が 263 repo で複製されている構造課題（一括移行は別スコープ）を記録。
詳細は **ADR-2607141400**。

## Addendum 7（2026-07-14）: JSON レンダリング撤去 — EDN 単一正本に戻す

オーナー指摘「json? データ自体は edn ですよね?」を受けた訂正（衛星 repo main
`0af38ca`、上書き追加コミット）。Addendum 6 で足した JSON 出力は不要だった:

- fleet は EDN-first（registry・ADR・blueprint すべて EDN）で、このリポの runtime
  優先順位（kotoba wasm > clojurewasm > ClojureScript > nbb）で書く検証器は EDN を
  ネイティブに読む。→ follow-up の「ブラウザ検証ページ」も cljc で EDN を読めば足り、
  JSON を持つ必要が薄い。二重シリアライズは保守面の負債。
- `cryptoexchange.publish/render-json` とそのテスト、nbb シェルの `.json` 出力を撤去。
  `docs/verify-inclusion.md`・README は canonical `.edn` を参照するよう修正。
- **維持（JSON と無関係な正しい修正）**: leaf preimage の colon 除去（`attest/pname`）。
  EDN artifact のままでも、公開フィールド値から `docs/verify-inclusion.md` の手順で
  独立検証でき、worked example は素の `sha256sum` で root を再現する。
- 将来 public な多言語 discovery surface が要る場合の JSON 化は、その時点で
  discovery-surface として**意図的に決める**ステップであり、ここに恒常的に持たない。
- 検証: CLJS 71 tests / JVM 77 tests、0 failures、clj-kondo 0 errors。INV-14 不変、
  maturity `:blueprint`。

## Addendum 12（2026-07-15）: self-contained ブラウザ検証ページ（Addendum 6 の follow-up 解消）

Addendum 6 が記録していた「未着手（follow-up）」の1つ、`公開 JSON/EDN を消費する
self-contained ブラウザ検証ページ（第4の独立実装）」を実装した（衛星 repo main
`27fccfd7`）。`docs/verify.html` — 依存ゼロの単一 HTML ファイル（vanilla JS、
ビルドステップ無し）が、`docs/verify-inclusion.md` のハッシュウォークを完全に
クライアントサイドで再現する: 自前の最小 EDN リーダー（このartifact形状に絞った
スコープ）+ ブラウザ標準 Web Crypto API の SHA-256。ページ内にネットワーク
リクエストは一切無く、どこにも送信しない。

**Addendum 7 の提案からの意図的な逸脱を記録する**: Addendum 7 は「follow-up の
『ブラウザ検証ページ』も cljc で EDN を読めば足りる」と述べていたが、実装は
**あえて cljc/ClojureScript を使わず vanilla JS で独立に書いた**。理由:
第三者検証の価値は「プロジェクト自身のツールチェーンを一切信用しない」ことに
あり（本 ADR の worked example が素の `sha256sum` を使うのと同じ精神）、
プロジェクト自身の EDN リーダー/ハッシュ実装を（cljc経由であっても）再利用する
ことは、その独立性を弱める。vanilla JS + ブラウザ標準 Web Crypto は、
"view-source" で誰でも監査できる最も疑わしくない実装であり、既存の
「独立実装」基準（sha256sum by hand）と同じ格の「第4の独立実装」になる。

**着地前の3段階の実検証**（「動きそう」で済ませない）:
1. ページ自身の埋め込み `<script>` ロジックを抽出し、素の Node で
   `docs/verify-inclusion.md` の worked example（alice/bob、root
   `761d744d...`）に対して実行 → バイト完全一致。
2. 同じ抽出ロジックを、**実際に `cryptoexchange.publish/build-artifact` を
   呼んで生成した本物の attestation artifact**（手組みのfixtureではない、
   alice/bob/carol/dave/erin 5アカウント）に対して実行 → 全員 PASS。改竄
   シナリオ2件（金額改竄・他人のproofを騙る）は正しく FAIL。
3. jsdom によるheadless DOM テストで、ページ自体の実際の file-input/
   textarea/dropdown/button 配線（抽出ロジックの単体テストではなく）を
   駆動 → parse・populate・verify・PASS/FAIL報告、および不正EDN/空
   artifactのエラーパスも含めて正しく動作することを確認。

`scripts/verify_docs_verify_html.cljs`（本 repo の慣習通りnbbスクリプト）を
追加し、上記2番を実際の `test/fixtures/verify-html-fixture.edn`（本物の
artifact）に対して再実行する回帰チェックとして checked-in——実 nbb 実行で
確認済み。既存の cognitect-test-runner / portable-cljs-test-runner には
配線していない（静的HTML成果物のテストであり、`.cljc` namespace 用の
両ハーネスの契約に合わないため）。

`README.md`・`docs/verify-inclusion.md` を新ページへの参照込みで更新。
このsatelliteはwest非登録（fleet慣例通り）のため west pin前進は不要。

## References

- ADR-2607121000（逆トポソート 5-wave — 6611/6612「取引台帳」注記）
- ADR-2607121200（Wave 0 safety kernel 規律 — 6511 reference implementation）
- ADR-2607122500(market-entry design-only — effects lifecycle と 422 honest refusal の先例)
- ADR-2607122200（blueprint 衛星 + maturity ladder の先例）
- ADR-2607011000（governor HARD 不変条件 / LLM→actuator 直結禁止）
- skill `build-actor` / skill `new-project-scaffold`
- 本 ADR とペアの `.edn`
