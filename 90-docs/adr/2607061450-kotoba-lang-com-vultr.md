---
id: adr-2607061450-kotoba-lang-com-vultr
title: "ADR-2607061450: kotoba-lang に com-vultr ポータブル .cljc Vultr API v2 client を追加する"
status: closed
closed: "2026-07-06"
doc_type: adr
topic: agent-loop
authoritative: true
last_verified: 2026-07-06
authoritative_for:
  - Vultr 向けに com-cloudflare/com-wise と同型の portable .cljc API client を新設する判断
  - Vultr のインスタンス削除/プラン変更/オートペイ切替を本ライブラリのスコープ外にする判断
    （オートペイは risk-gate 方針に加え、Vultr API 自体に該当エンドポイントが存在しない）
related:
  - orgs/kotoba-lang/com-vultr          # 新規: Vultr API v2 の portable client
  - orgs/kotoba-lang/com-cloudflare     # 前例: portable .cljc vendor API client の型
  - orgs/kotoba-lang/com-wise           # 前例: 同日(2026-07-06)に作った同型 client、read-focused の risk-gate 方針も踏襲
  - 90-docs/adr/2607061423-kotoba-lang-com-gmail-com-wise-procedure-clj.md # 直前の同型判断
supersedes: []
superseded_by: []
---

# ADR-2607061450: kotoba-lang/com-vultr

- Status: closed (2026-07-06)。本ADRのスコープである com-vultr ライブラリ本体は
  scaffold・テスト green・push・manifest 登録・pin 検証まで全て完了。

## 課題

jun784@gmail.com のメール triage（本日）で、Vultr から月$700〜900規模の請求
（オートペイ失敗時の請求書、セッション検証メール等）が繰り返し届いていることが
判明した。6/11付の自分宛メモ（下書き）にも「Vultr月額$764-875のコスト削減を
したい。APIキーまたはダッシュボードで棚卸し」という未着手の課題が残っていた。

同じ会話の流れでオーナーから「Vultrは自動契約停止」という指示があったため、
実行前に範囲（アカウント解約 or 全サーバー削除 or オートペイのみ停止）と手段
（API キー vs. ブラウザ操作）を確認したところ、「オートペイのみ停止」を
「Claude in Chrome でダッシュボードにログインして操作」で進める方針に決まった
（ログイン自体は資格情報を扱えないためオーナー本人が実施）。

この過程で、「このアカウントは何が動いていて、実際何を請求されたか」を答える
手段が、ダッシュボードを開いて目視するか請求メールの件名を信じるかしかない
ことが露呈した。`com-cloudflare` が「どのホスト名が実際に何で処理されているか」
に、`com-wise`（同日 ADR-2607061423）が「この送金は実際どんな状態か」に、
それぞれ ad hoc curl/ダッシュボード閲覧の代わりに与えた実答えを、Vultr にも
与える。

## 決定

`kotoba-lang/com-vultr` — Vultr API v2 の portable .cljc client を新設する。
`com-cloudflare`/`com-wise` と同型:

```
vultr.client    -- 認証(Bearer APIキー)+ HTTP(注入可能な :http-fn)+ JSON envelope
vultr.account   -- get-account: 残高/未払い請求/直近支払い
vultr.billing   -- list-history, list-invoices, get-invoice, invoice-items
vultr.instances -- list-instances(label/tag/region絞り込み), get-instance
```

Vultr には Wise のような公開 sandbox API が無いため `api-base` は1つのみ。
認証は `VULTR_API_KEY`（環境変数）または明示的な `:token`。

## 非目標（scope外）

- **インスタンスの起動/停止/再インストール/削除、プラン変更**: 実運用中の
  リソースを壊しうる不可逆操作であり、`com-wise` の送金実行と同じ risk-gate
  原則（`kotoba-issue-clj` の risk tier、`local-manimani` ADR-0019）に従い、
  ライブラリ呼び出し1つで実行可能にしない。人間承認のゲートを経由させる。
- **オートペイ/自動更新のトグル**: risk-gate 方針に加えて、**Vultr API v2
  自体にこの設定を変更するエンドポイントが存在しない**（ダッシュボード限定の
  設定）。今回オーナー指示の「自動契約停止」自体は、このライブラリでは実行
  できず、引き続きブラウザ操作（Claude in Chrome + オーナー本人のログイン）で
  対応する。将来 Vultr がAPIを公開しない限りこの非目標は解消しない。
- APIキー取得フロー自体: 呼び出し側が `VULTR_API_KEY` を用意する
  （`cloudflare.client` の `CLOUDFLARE_API_TOKEN` と同じ規約）。

## 却下案

- **オートペイ停止までこのライブラリで自動化する**: 上記の通り API 自体に
  手段が無く、実装不可能。仮に将来 Vultr が公開しても、`:financial` 操作は
  risk-gate 越しにすべきで、ライブラリの薄い client 層に埋め込まない。
- **インスタンス管理（起動/停止/削除）まで含めたフルクライアントにする**:
  `com-wise` が送金実行を除外したのと同じ理由（該当 ADR 参照）で却下。

## 検証

```
cd orgs/kotoba-lang/com-vultr && clojure -M:test   # 14 tests, 28 assertions, green
nbb scripts/gen-west-manifest.cljs --entry com-vultr
nbb scripts/gen-west-manifest.cljs --check
```

## 実装フェーズ（完了）

- **Phase A（完了）**: scaffold・テスト green・`kotoba-lang/com-vultr`（public）
  作成・push、`manifest/repos.edn` の `:extra-projects` に登録、
  `gen-west-manifest.cljs --entry com-vultr` で最小 diff 生成、pin 検証通過。

本ADRのスコープは「com-vultr ライブラリを作る」という決定であり、上記 Phase A
の完了をもって全て完了・closed とする。

**本ADRのスコープ外として残る別件（このライブラリでは解決できない）**: オーナーの
「Vultr は自動契約停止」指示のうち、オートペイ無効化の実操作自体は、上記「非目標」
の通り Vultr API に該当エンドポイントが無いため本ライブラリでは実行不可能で、
Claude in Chrome 経由でオーナー本人がダッシュボードにログインして操作する必要が
ある。これは本ADR/ライブラリの実装物ではなく別途の一回限りの手動操作なので、
本ADRの closed 判定をブロックしない（トラッキングはこのADRではなく元の会話の
todo で継続）。
