# Run 0021 — portfolio / 4 主力ドメインの実収益監査と kotobase 価格確定

**Status:** completed
**Started:** 2026-08-08
**Ended:** 2026-08-08
**Owner:** agent（価格決定のみ human）
**Mode:** cash-first

## Trigger

オーナーからの問い:「いまの kotobase.net, murakumo.cloud, shinshi.club,
itonami.cloud で収益を稼げる状態になっている?」→ 実測確認 →
「最短で 1 本 green にする」を実行に移す指示。

## Capital governor

| Field | Value/evidence |
|---|---|
| Current tranche | T1 |
| Released / committed / spent | ¥300,000 / ¥0 / ¥0 |
| This run cash-at-risk ceiling | ¥0（監査と記録整備のみ） |
| Next tranche release condition | ADR-2607246100 に従う。未変更 |
| Paid acquisition allowed? | no |

## Observed（as-of 2026-08-08、`90-docs/business/metrics/*.edn`）

| Product | 外部実入金 | ファネル |
|---|---|---|
| net-kotobase | 0 | 訪問 2,453 → 登録 13 → checkout 0 |
| cloud-murakumo | 0 | federation active-demand-apps 0 |
| club-shinshi | 0 | 訪問 645 → chatter 1 → scene 0 → 課金 0 |
| cloud-itonami | 0 | 外部 tenant 5（全 free）→ externalPaid 0 |

ポートフォリオ Stripe: `charges-total 90` / `last-charge-epoch 1604224274`
= 最後の課金 2020-11-01。North Star（外部非 owner の実入金）は **0 のまま**。

## Executed

1. **価格矛盾の検出と解消。** SCORECARD（¥980、pricing ADR proposed）と
   execution plan（¥2,980）が同一日付で矛盾し、かつ GO-NO-GO の項目 1-2 が
   完了扱いだった。3 つは同時に真になり得ず、Unblock proof の「ratified price」は
   未達だった。**owner 判断で ¥2,980/月 を正とし解消**（¥980 は superseded）。
2. **COMMERCIAL-GO-NO-GO.md / SCORECARD.md を 2026-08-08 実測へ更新。**
   Portfolio revenue state 節を新設、価格の確定節に検出経緯を記録。
3. **ExoClick の現在形誤記を訂正。** maturity-facts.edn の club-shinshi note が
   「gftd 唯一の :live 収益」と現在形で書いていたが、ledger 2026-07-09 実測は
   7d 再構成 USD 0・imp 0。訂正のうえ `gftd score md` で maturity-scores.edn を
   再生成（スコア行に変化なし、note のみ 292 字差分）。
4. **ADR-2608094000** に監査結果・価格確定・停止点・引き継ぎを記録。

## Blocked

`network-awai/net-kotobase` は com-junkawasaki スコープの session に attach 不可
（`add_repo` が cross-tier add を構造的に拒否）。green 化 7 項目のうち:

- 2・3・4・6（pricing ADR ratify + Stripe 照合、terms 確定、privacy 確定、
  test-mode E2E）→ repo 到達不能で**実行不能**
- 5・7（counsel 承認、live 切替 owner 承認）→ **agent が代行してはならない**

本 run で閉じたのは項目 1 のみ。

## Unit economics

| Unit economics | Hypothesis before | Observed after |
|---|---:|---:|
| Collected revenue | ¥0 | ¥0 |
| New paying customers | 0 | 0 |
| Attributable acquisition spend | ¥0 | ¥0 |
| Founder hours | unknown | unknown |

## Decide

**continue**（stop でも scale でもない）。価格という前提条件が 1 つ閉じたが、
残りは session 境界の外にある。

## Next

`network-awai/net-kotobase` を initial source にした新規 session を開き、
COMMERCIAL-GO-NO-GO.md「Session constraint」の入力で項目 2 → 4 → 6 を進める。
確定入力は **Standard ¥2,980/月**。未決定のまま渡すのは税込/税別・billing cycle・
解約時点・返金条件。
