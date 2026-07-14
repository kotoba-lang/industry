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

## References

- ADR-2607121000（逆トポソート 5-wave — 6611/6612「取引台帳」注記）
- ADR-2607121200（Wave 0 safety kernel 規律 — 6511 reference implementation）
- ADR-2607122500(market-entry design-only — effects lifecycle と 422 honest refusal の先例)
- ADR-2607122200（blueprint 衛星 + maturity ladder の先例）
- ADR-2607011000（governor HARD 不変条件 / LLM→actuator 直結禁止）
- skill `build-actor` / skill `new-project-scaffold`
- 本 ADR とペアの `.edn`
