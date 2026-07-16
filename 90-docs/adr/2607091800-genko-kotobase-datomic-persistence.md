# ADR-2607091800: genko doc の永続化を kotobase.net datomic api に移行(localStorage はキャッシュに降格)

**Status**: accepted (implemented + merged this session)
**Date**: 2026-07-09
**Deciders**: Jun Kawasaki

## Context

- aozora studio の genko エディタ(ADR-2607091300 系: `/studio/<slug>/genko`、
  cljs/re-frame ネイティブ化済み)は doc を localStorage
  (`mangaka-aozora-genko-<slug>`)にのみ保存しており、端末・ブラウザに閉じていた。
  オーナー指示(2026-07-09):「localStorage じゃなくて kotobase.net を使った
  datomic api での保存に」。
- kami-genko の genko_app には kotobase.net 同期の先行実装(blob record を
  `at://` に置く方式)があるが、本件は CLAUDE.md `:kotoba` 節の
  **db-api マップ(`{:q :transact! :db :pull :entid}`)経路**を使う。
- doc の datom 化方針には `:yoro.post/record`(record JSON を単一 datom 値で
  保持)の前例がある。ストロークの深い datom 化はしない。

## Decision

**work-actor(例: ghosthacker)の鍵由来 graph に、per-slug の doc エンティティを
単一 string datom で upsert する。**

- スキーマ: `{:db/id "gh.genko/doc/<slug>" :gh.genko/slug <slug>
  :gh.genko/docB64 base64(write-doc JSON) :gh.genko/rev <単調増加>
  :gh.genko/updatedAt <ms>}` @ `kotobase/db/<did:key>/genko`。
  **base64 必須**: 配備中の tx_edn パーサ系譜は値中のリテラル `{`/`}` で
  エンティティを分割する(`:atproto.record/jsonB64` と同じ回避)。
- 読込: kotobase → localStorage(旧キー・既存原稿の移行元)→ 新規 doc の
  フォールバック連鎖。localStorage は**オフラインキャッシュに降格**
  (write-through で維持 — 「ストーリーボードへ反映」の契約は不変)。
- 競合: save 前に remote rev を再読。remote > base なら**黙って上書きせず**
  UI で 再読込/上書き を選択(上書きは observed-remote+1)。
- 認証: studio 既存の work-actor Ed25519 CACAO 鍵(`publish/actor-seed`)。
  鍵が無ければ `:local-only`(ローカル保存のみバッジ・ネットワークゼロ)、
  失敗時は `:offline` で次回 save 時に再試行。
- 実装: `yoro_ui/studio/genko_store.cljc`(注入可能 fetch の薄い client、
  純関数 tx/fold)+ `state/genko.cljc` の re-frame events/fx/subs 拡張 +
  genko-ui アダプタ `:sync` 接続。app-aozora `ab21923`、pin `afee7c2`。

## 実測(2026-07-09 ライブプローブ — 運用上重要)

1. **kotobase.client 既製の mint は 401**。kotobase.net apex(net-kotobase
   clj-edge、2026-07-08 cf-wasm cutover)は CACAO に
   `kotoba://can/kotobase:pin` + **issuer DID と同値の** `kotoba://graph/`
   scope + `x-kotoba-did` ヘッダ + リクエスト毎 fresh nonce を要求する。
   genko_store は自前でこの形状を mint(自己認可は健在: fresh did:key が
   自分の graph に transact 200)。**kotobase.client 本体への反映は follow-up**。
2. **2026-07-02 の transact 500(Invalid array buffer length)は再現せず**
   (全プローブ書込/読出で発生なし)。
3. **`q`(datalog)が実在データに対して `{"ok":true,"rows":[]}` を返す**。
   読出は `:eavt` index scan(`datoms`)で実装。server 側の要調査事項。
4. **再 assert は蓄積**(cardinality-one 未導入)— reader は log 順 last-wins
   で fold(`aozora.appview.scan/group-by-entity` と同型)。novelty 増加は
   将来の周期 fold(cron)で圧縮。

## Consequences

- 原稿が端末を跨いで復元される(E2E 実証: localStorage 消去→リロード→
  kotobase から復元)。テスト 228 本 624 アサーション通過・release build 警告ゼロ。
- 残 follow-up: kotobase.client の edge-CACAO mint 形状対応(他呼出元の 401
  解消)/ `q` 空 rows の server 調査 / cardinality-one スキーマ or 周期 fold /
  WebGPU パリティ / Mangaka AI パネル。
