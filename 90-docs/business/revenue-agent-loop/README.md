# Revenue Agent Loop

すべての事業・product・distribution loop に、同じポートフォリオ判断を
組み込むための実行契約。既存の品質、安全、web-service、BMC loopを置換せず、
その上段で「今どの商品・どのアクションに時間を使うか」を決める。

**開始日:** 2026-07-24
**判断ルール:** `.cursor/rules/always/0.mdc`
**初期順位:** `../portfolio-priority-2026-07-24.md`

## North Star

外部の非owner顧客による、検証可能な実入金と継続粗利益を増やす。

次は売上として数えない。

- owner自身または関係者による自己購入
- test mode、fixture、手動で作った架空subscription
- 内部dogfoodまたは内部agent traffic
- checkout表示、ボタンクリック、wallet接続だけで完了していないもの
- on-chainまたはPSPで検証できない支払claim

## State machine

```text
OBSERVE
  → SCORE(all candidate actions)
  → SELECT(max score, WIP=1)
  → EXECUTE(one bounded experiment)
  → VERIFY(external evidence + safety gates)
  → UPDATE(priors and score)
  → DECIDE(scale | continue | change | stop)
  → RECORD(run log)
  → OBSERVE
```

途中で別商品の改善点を見つけても、そのrunでは直さず候補queueへ戻す。
支払、安全、法務のhard gateに失敗した場合だけ、選択中アクションを中断して直す。

## 100 point score

各候補はアクション単位で0–100点を付ける。product全体に曖昧な点数を付けない。

| Axis | Max | 5/5の基準 |
|---|---:|---|
| 30日以内の外部実入金確率 | 20 | 観測済み購入意向またはcheckout開始がある |
| 次の証拠までの速さ | 10 | 1日以内、4人時以下 |
| 既存の外部需要 | 10 | qualified lead、反復利用、または意図のあるtrafficが観測済み |
| 決済経路の完成度 | 10 | live、非ownerによるE2E検証可能、fulfillmentまで接続 |
| 12か月期待粗利益 | 15 | 高単価かつ原価・導入費控除後も十分 |
| 継続・再購入可能性 | 10 | recurringまたは自然な反復購入 |
| 粗利率 | 5 | 限界原価が小さく計測可能 |
| 学習価値 | 5 | 成否どちらでも価格・buyer・channel仮説が大きく更新される |
| 他productへの波及 | 5 | payment/channel/基盤を複数productで再利用できる |
| 証拠信頼度 | 10 | 直近の外部観測で推定幅が狭い |
| **合計** | **100** | |

各axisは0–5で採点し、`axis / 5 × max` で加点する。

### Hard gates

以下は減点で相殺しない。一つでも赤ならscoreに関係なく実行不可。

- 未成年、同意、地域、content、privacyの必須安全策
- payment claimの署名・PSP webhook・on-chain検証
- 実際には提供できない商品・entitlementを販売しない
- 返金、規約、operator表示など当該商品で必須の法務境界
- secret、treasury、個人情報を安全に扱えない状態

gateが黄なら販売範囲を狭めた実験だけを許可し、runに制約を書く。

## Objective mode

外部売上がゼロの間:

```text
selection_score = 0.60 * cash_subscore + 0.40 * profit_subscore
```

最初の外部実入金後:

```text
selection_score = 0.40 * cash_subscore + 0.60 * profit_subscore
```

100点scoreは比較と監査用、上式は同点時の選択用。初回入金後も、
一回限りの少額決済だけで全配分をB2Cへ移さない。

## One run

1 runは最大14日、または20人時の早い方で終了する。開始時に次を固定する。

1. 対象product/vertical
2. 一つのアクション
3. 現在のfunnel実数
4. scoreと各axisの根拠
5. 期待する外部証拠
6. timebox
7. hard gate
8. 成功、継続、停止条件

終了時には次を必ず記録する。

- 実人時
- funnelのbefore/after
- 外部証拠への参照
- 実入金なら金額、手数料、限界原価、粗利益
- scoreのbefore/after
- priorからposteriorへ変えた理由
- `scale` / `continue` / `change` / `stop`

## Decision rules

| Result | Decision |
|---|---|
| 外部実入金 + fulfillment成功 | 同じ経路で再購入/30日継続を検証 |
| checkout開始はあるが入金0 | payment frictionを一つだけ除く |
| 利用はあるがcheckout開始0 | offer、価格、配置を一つだけ変更 |
| trafficはあるがcore利用0 | 課金開発を止めactivationを検証 |
| qualified outreachの返信あり | discoveryを続け具体的paid pilotを提示 |
| 10 qualified contactsで返信0 | segment/message/channelを変更 |
| 14日または20人時で外部証拠0 | 原則停止し次点へ移る |
| hard gate失敗 | 販売停止。gate修復以外を進めない |

## Files

- `SCORECARD.md`: 現在の候補順位
- `RUN-TEMPLATE.md`: 各runの必須項目
- `runs/`: append-onlyの実行記録。過去runの結果を書き換えない

毎回のagent loop開始時にSCORECARDを直近観測へ更新し、終了時にrunを追加する。
