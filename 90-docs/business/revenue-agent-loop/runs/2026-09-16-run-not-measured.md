# run: 2026-09-16 kotobase-stab 反復

## 結論: 本反復は「計測不能」で記録する

## 測定を試みた経路と結果 (捏造ゼロ)

1. `nbb 90-docs/business/revenue-agent-loop/kotobase_lead_loop.cljs funnel-pulse`
   (workdir /Users/junkawasaki/github/com-junkawasaki) → **出力 0 バイト、exit 0**。
   空出力は「 funnel 正常」ではなく **not-measured** として扱う (SOUL 原則)。
2. `curl -s https://kotobase.net/api/funnel` → **出力 0 バイト** (HTTP status も取得不能)。
3. 安定化チェック `curl -w "%{http_code}"` で `/`, `/signup`, `/ipld/v1`, `/ipld/` を
   確認しようとしたが、**curl の stdout がすべて 0 バイトで返った** (status code 含め取得失敗)。
4. 对照試験: `echo hello` ですら terminal tool から **stdout 0 バイト**。
   → 端末出力キャプチャ自体がこのセッションで壊れている (コンテンツ取得層の故障、
   サイト障害との区別がこの経路ではつかない)。
5. 代替経路: background terminal + process_manage poll/log → 同じく 0 バイト。
   web_search / web_extract → **Nous Tool Gateway 不可达** エラー。

## 判定

- funnel 状態: **not measured**
- / / /signup / /ipld/v1 の安定化チェック: **not measured**
- 「サイトが落ちている」とも「落ちていない」とも断定しない。証拠ゼロ。

## SCORE → SELECT

測定が出来ない状態でアクションを選ぶのは canvas の数字なしの推測になるため、
本反復の selected action は **なし (WIP=0)**。次の検証のみ残す。

## 次の検証 (次回 cron にて)

1. terminal tool の stdout キャプチャが復旧しているか、`echo hello` 1 行で確認。
2. 復旧していれば通常フロー: funnel-pulse → curl 安定化チェック → SCORE/SELECT。
3. nbb が依然空出力なら、kotobase_lead_loop.cljs の exit code と stderr を
   ファイルリダイレクトで捕まえて分離する (出力キャプチャと実行結果を切り分ける)。

## 変更したもの

- なし (canvas-ledger.edn は触っていない。コード・デプロイも触っていない)。
