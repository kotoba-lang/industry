# ADR-2607994000: engi/L1 witness admission — permissionless staking(Base L2 USDC 想定)+ equivocation限定スラッシング(ADR-2607110300 Phase 4 の具体化)

**Status**: accepted(設計 + cljc プロトタイプ実装。実際の外部担保カストディ契約のデプロイは伴わない)
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

ADR-2607993000 は `kotoba-lang/engi` を chained HotStuff 型 BFT の L1 にしたが、
**witness 集合が誰であるかは運営者(com-junkawasaki)が決める**という前提は
残ったままだった。ADR-2607110300 の Phase 4(独立第三者運営者+経済的
stake)は「形だけ示す。実際に第三者運営者が現れる段階で別ADRを起票する」
として意図的に設計を保留していた。

オーナーの 2026-07-15 の指示: 「com-junkawasaki 単一でなく分散型が成立する
ように設計して」。具体化にあたり2点を明確化した:

1. **参加モデルは permissionless staking** — 一定量の担保を bond すれば
   誰でも witness になれる。既存 witness による承認投票は不要にし、
   閾値 bond + スラッシングのみで Sybil 耐性を担保する(reputation-weighted
   admission や PoW/Nakamoto 型への転換は却下、Alternatives 参照)。
2. **実現範囲**: 本ADRが設計・実装するのはメカニズムそのもの(bond・
   stake-weighted quorum・slashing・recruitment 文書)まで。**実在する
   第三者組織の勧誘・契約は agent が実行できる作業ではなく、オーナー側の
   別途の事業活動**として切り分ける。

## 撤回・具体化する範囲

ADR-2607110300 Phase 4 の「shape only、着手する場合は別ADR」を、ENGI/EN の
witness admission に限定して**具体的な設計に格上げする**。ADR-2607110300が
「今も無い」と名指しした「slashing verdict への異議申立て機構」は、
本ADRでは**あえて作らない**ことでむしろ解消する(下記 Decision #5 の
設計そのものが理由)。

## Decision

### 1. Bond資産 — EN 自体ではなく外部担保(Base L2 USDC 想定)

**EN を bond 資産にしない。** EN(縁)は ADR-2607101100 の核心的不変条件
どおり **net-zero・非発行の相互信用** であり、全エージェント間で総量が
ゼロに balance する会計であって、外部で価格が付く希少資産ではない。
misbehavior の罰則として「EN を没収する」は、没収された EN に外部での
交換価値保証が無い以上、実質的な経済的抑止力を持たない。

**採用する bond 資産は外部担保** — ADR-2607101100 §4 が既に明記している
既存の境界をそのまま使う: 「実際に決済される希少資産は境界の外側
(etzhayyim の USDC on Base L2)」。witness 志望者は Base L2 上の USDC を
エスクロー契約に bond し、その bond 額を engi/L1 側が(下記の注記のとおり
検証済みデータとして)参照する。

**本ADRが実装しないもの、明示**: Base L2 上の実際のエスクロー
スマートコントラクト(Solidity、deposit/slash/unbond の実装)は
**本ADRの範囲外・デプロイしない**。理由: 第三者の実資金をカストディする
契約を実際にデプロイする行為は、これまでのタスクより一段重い、単独の
判断で進めるべきでない財務的リスクを伴う(CLAUDE.mdの安全床「②資金の
売買・送金・変換・tradeをしない」の精神に照らし、少なくとも明示確認が
要る領域)。本ADRの cljc 側実装(`engi.stake`)は「`{witness-did →
bonded-amount}` という**既に検証済みのデータ**を受け取る」という形で
このカストディ層と疎結合にしてある — 将来 Base L2 契約を実際に統合する際、
`engi.stake` の再設計は不要で、この map の供給元を差し替えるだけで済む。

### 2. Admission — permissionless(既存 witness の承認投票は不要)

```clojure
(eligible-witnesses bonds min-bond)
;; bonds = {witness-did -> bonded-amount(検証済み、外部から供給)}
;; => bond >= min-bond を満たす did の集合。既存 witness の合意は一切問わない。
```

`com-junkawasaki` はこのルール上、**特別な承認権限を持つ主体ではなく、
bond した数多の witness のうちの1つ**になる。これが「単一運営主体でない」
の構造的な意味 — プロトコル自体が誰かの許可を必要としない。

### 3. Stake-weighted quorum(witness 数ではなく bond 総量で閾値判定)

ADR-2607993000 の `quorum-size`(n=3f+1 witness 数ベース、2f+1 閾値)は
**運営者が witness 数を固定的に管理する前提でのみ安全**。permissionless
admission の下では、閾値額ギリギりの小口 bond を多数のIDに分散させる
(Sybil)ことで、少量の総担保で票数を稼げてしまう。真の経済的安全性は
**stake(bond総量)加重**でなければならない:

```clojure
(stake-quorum-met? voted-dids bonds witness-set)
;; voted-dids の合計 bond が witness-set 全体の合計 bond の 2/3 を超えるか。
```

**安全性の議論**: Byzantine が支配する bond 総量が全体の 1/3 未満である限り、
任意の2つの「2/3超」quorum は合計 bond で最低 1/3 超の重なりを持ち、
その重なりには必ず正直な bond が含まれる(標準的な stake-weighted BFT
— Tendermint 等と同型の議論)。これは ADR-2607993000 の witness数ベースの
議論(2f+1 の交差は f+1 ノード)を bond 総量の言葉に置き換えたもの。

### 4. Epoch — witness 集合と bond 残高はエポック境界でスナップショット

エポック途中の bond 増減(直前に大口 bond して直後に unbond するような
攻撃)を避けるため、そのエポックの witness 集合・投票重みは
**エポック開始時点の bond スナップショット**で固定する。

### 5. スラッシング — equivocation のみ(客観的に証明可能なものだけ)

**核心的な設計判断**: スラッシング対象を「同一 witness 鍵が、同じ
height で異なる block-hash に対して署名した2つの投票」(equivocation)
**のみ**にする。これは署名検証だけで誰でも機械的に判定できる、
**主観的判断の余地が無い不正証拠**である。

```clojure
(detect-equivocation votes)          ; 同一witness×同一height×異なるblock-hashのペアを検出
(verify-equivocation-evidence evidence verify-sig-fn) ; 両votesの署名が本物か検証(注入)
(slash bonds evidence {:burn-fraction 0.95 :whistleblower-fraction 0.05})
;; => 該当witnessのbondを全額没収。95%burn(供給から消滅)+5%を証拠提出者への報奨。
;;    次エポックのwitness集合から即時除外。
```

**あえて slashing 対象にしないもの**: 沈黙(クラッシュ・応答保留)や
censorship のような liveness 障害は、悪意か単なる障害(ネットワーク
分断等)か客観的に区別できない。これらは:

```clojure
(liveness-drop last-active-height current-height witnesses liveness-window)
;; => liveness-window 分の height 連続で投票していない witness を
;;    「次エポックのACTIVE集合から除外」するだけ。bondは没収されず、
;;    通常のunbondフローで引き出せる。再度投票を再開すれば復帰できる。
```

**これが ADR-2607110300 が名指しした「slashing verdict への異議申立て
機構が無い」というギャップを、機構を作らずに解消する理由**: スラッシング
対象を equivocation だけに絞ることで、判定は暗号学的証拠のみで完結し
(2つの有効な署名 + 同一height + 異なる内容 = 議論の余地なく確定)、
**主観的な verdict そのものが発生しない**。異議申立てが必要になるのは
「これは本当に不正か」を巡って意見が割れうる場合だけであり、equivocation
はその割れる余地が構造的に存在しない。liveness 障害はそもそも罰則
(bond没収)を伴わないため、異議申立ての対象にすらならない。

### 6. Unbonding delay — 逃げ得を防ぐ

Witness が unbond を申請してから実際に bond を引き出せるまで
`unbond-delay-epochs`(既定3エポック)を置く。これにより、不正を働いた
直後に unbond して証拠提出前に資金を引き揚げる、という「逃げ得」を防ぐ
— unbond 申請中でも equivocation 証拠は有効に提出・執行できる。

### 7. 報酬モデル — EN を mint できない以上、外部手数料建てにする

ENGI/EN の核心的不変条件(non-minted, net-zero)により、**witness への
報酬として新規 EN を発行することはできない**(それ自体が非発行の原則
違反になる)。witness の経済的インセンティブは、代わりに**外部資産建ての
小額手数料**にする: kotobase.net 上で確定される transfer 1件あたり、
外部担保と同じ資産(USDC等)建ての小額 fee を徴収し、そのエポックの
アクティブ witness に bond 比例で分配する。

**具体的な数字(すべて例示・ガバナンス調整可能、確定値ではない)**:
- `min-bond`: 500(USDC相当)を出発点として提案。根拠: witness 1人が
  1回の equivocation で得られる利得(1件の transfer を二重に確定させる
  ことで得られる不正な EN 移転額)が、通常の ENGI 利用(創作貢献への
  少額相互信用、ADR-2607101100 の想定用途)の規模に対して bond 没収の
  痛みが明らかに上回るよう、実際の transfer 規模の分布を見て
  ガバナンス投票で調整することを前提にした暫定値。
- `unbond-delay-epochs`: 3。
- `liveness-window`: 50 height。
- 手数料率: 未確定(実運用データが無いため本ADRでは数値を固定しない)。

### 8. パラメータ変更のガバナンス — 新しい仕組みを増やさない

`min-bond`/`unbond-delay-epochs`/`liveness-window`/手数料率の変更は、
**ブロック確定と同じ stake-weighted 2/3 quorum 投票機構をそのまま使う**。
パラメータガバナンス専用の別システムは作らない。

### 9. ブートストラップ — 誠実に認める限界

Day 1、bond している witness は com-junkawasaki 自身の1つだけ
(n=1、実質的には従来と同じ運用集中度)。**本ADRが変えるのはこの1点
だけ**: プロトコル上、witness #2 以降が参加するのに com-junkawasaki の
許可は一切必要ない — bond して epoch 境界を迎えれば自動的に witness
集合に入る。「今日から分散化が達成された」とは主張しない。主張できるのは
「今日から、誰かが実際に bond すれば分散化する**メカニズムが存在する**」
という点のみ。実在する独立組織への勧誘・契約は本ADR・本エージェントの
範囲外 — オーナー側の事業活動として別途進める(Decision #10 の
勧誘文書はその材料に留まる)。

### 10. 勧誘文書

外部組織向けの witness 参加要件(bond額・想定リターン・リスク開示)を
`orgs/kotoba-lang/engi/docs/witness-recruitment.md` に用意する(本ADRの
姉妹文書)。投資勧誘と誤解されないよう、保証されたリターンが無いこと・
実験的インフラであること・bond は没収されうることを明記する。この文書の
**実際の配布・送付はしない**(文書の作成のみ)。

## Consequences

- (+) witness 参加の許可権が com-junkawasaki の裁量から「bond 額を満たす」
  という誰でも検証可能なプロトコルルールに移る — 構造的な非中央集権化。
- (+) スラッシング対象を equivocation のみに絞ることで、異議申立て機構を
  新設せずに「slashing verdict の正当性」問題を解消。
- (+) `engi.stake` は bond の実際のカストディ手段(未定・将来 Base L2
  契約)から疎結合 — 既に検証済みの `{did -> amount}` を受け取るだけ。
- (−) 実際の外部資金カストディ契約は未デプロイ(仕様のみ)。これが
  デプロイされるまで、bond は概念的な設計であり実際の経済的抑止力は
  発生しない。
- (−) `min-bond`/手数料率の具体的な数値は実運用データが無いための暫定値。
- (−) liveness/censorship の異議申立ては依然として存在しない(ただし
  これらは無罰則なので実害は限定的)。
- (−) 実在する独立第三者 witness の獲得(事業開発)は本ADRの範囲外。
  Day 1 時点で実質的な集中度は変わらない。

## Alternatives

- **reputation-weighted admission(既存 witness の supermajority 承認制)**:
  却下(オーナー明示判断)。Sybil耐性は既存メンバーの審査に依存し、
  com-junkawasaki が最初の1票である間は「誰の許可も要らない」という
  構造的な非中央集権化を実現できない。
- **permissionless PoW/Nakamoto 型 consensus への転換**: 却下(オーナー
  明示判断)。ADR-2607993000 で実装済みの witness-quorum 署名・chained
  HotStuff コードを破棄する大きな方向転換になり、既存投資を無駄にする。
- **EN 自体を bond 資産にする**: 却下。EN は net-zero・非発行の相互信用
  であり外部での価格保証が無いため、没収に実質的な経済的痛みが伴わない。
- **liveness障害もスラッシング対象にする**: 却下。ネットワーク分断等の
  誠実な障害と悪意を客観的に区別できず、異議申立て機構が必須になる —
  それを避けるために equivocation のみに限定した。

## References

- ADR-2607993000(engi-l1-byzantine-consensus-en-currency)— 本ADRが
  witness admission を permissionless 化する対象の L1。
- ADR-2607110300(kotoba-lang-net-kotobase-cloud-murakumo-decentralization-roadmap)
  — Phase 4「shape only」を本ADRが ENGI/EN witness admission に限定して
  具体化する。
- ADR-2607101100(engi-mutual-credit-kotobase-native-design)§4 — 「実際に
  決済される希少資産は境界の外側(etzhayyim の USDC on Base L2)」— 本ADRの
  bond資産選定がそのまま踏襲する既存の境界。
- `orgs/kotoba-lang/engi/src/engi/stake.cljc` — 実装。
- `orgs/kotoba-lang/engi/docs/witness-recruitment.md` — 勧誘文書(姉妹文書、
  配布はしない)。
