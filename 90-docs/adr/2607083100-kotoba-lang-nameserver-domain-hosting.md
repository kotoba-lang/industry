---
id: adr-2607083100-kotoba-lang-nameserver-domain-hosting
title: "ADR-2607083100: kotoba-lang/nameserver — RFC 1035 権威DNSネームサーバー(.cljc wire/store/resolver + JVM socket層) + IPNS alt-root によるカスタムTLD (.hogehoge 等) ホスティングブリッジ"
status: accepted
doc_type: adr
topic: dns-nameserver-domain-hosting
authoritative: true
last_verified: 2026-07-08
authoritative_for:
  - kotoba-lang における「ネームサーバーによるドメインホスティング」の設計方針の決定
  - zone-clj(ゾーンファイルEDN化)・godaddy-dns-clj(レジストラAPI)と新設 nameserver
    (ワイヤプロトコル権威応答)の責務分離
  - 実TLD(.com等)ホスティングを「NS委任(レジストラ経由)+自前権威応答」の組み合わせと
    して現実的にスコープする決定
  - 独自TLD(.hogehoge等)を「alt-root + IPNS/dnslink ブリッジ」として、グローバル解決を
    主張しない誠実なスコープで設計する決定
  - ソケット層(生UDP/TCP bind)のみ JVM を採用する根拠(kotoba wasm > clojurewasm >
    cljs > nbb > JVM ランタイム優先順位の「最後の手段」適用例)
related:
  - orgs/kotoba-lang/nameserver                            # 本ADRのライブラリ
  - orgs/kotoba-lang/zone                                  # ゾーンファイルEDNモデル(消費元)
  - orgs/kotoba-lang/godaddy-dns                            # レジストラAPIクライアント(委任連携)
  - orgs/kotoba-lang/ipns                                   # 鍵由来IPNS名導出(alt-rootブリッジの消費元)
  - 90-docs/adr/2606272207-zone-clj-dns-zonefile-edn.md     # zone-clj設計
  - 90-docs/adr/2606251621-godaddy-dns-clj-dns-management-agent.md # godaddy-dns-clj設計
supersedes: []
superseded_by: []
---

# ADR-2607083100: kotoba-lang/nameserver — 権威DNSネームサーバー + IPNSカスタムTLDブリッジ

**Status**: accepted（実装済み・テスト緑・`dig` 実地検証済み）
**Date**: 2026-07-08
**Deciders**: Jun Kawasaki

## Context

オーナーから「kotoba-lang でネームサーバーによるドメインホスティングのライブラリ・
規格は設計済みか(.com 等の実TLD、`.hogehoge` のような独自TLDも含め)」と問われ、
調査した結果:

- `zone-clj`(`orgs/kotoba-lang/zone`)はゾーンファイル(RFC 1035)を EDN として
  扱うモデル/検証/diff ライブラリだが、**I/O を一切持たない**（データ表現のみ）。
- `godaddy-dns-clj`(`orgs/kotoba-lang/godaddy-dns`)は GoDaddy レジストラ API の
  クライアントだが、**DNS クエリに応答するわけではない**（レコードの読み書きのみ）。
- 実際に DNS クエリへワイヤプロトコルで応答する**権威ネームサーバー本体**は
  存在せず、独自TLD(`.hogehoge` 等)のホスティング設計も存在しなかった。

standing authorization（本ファイル `CLAUDE.md` の「標準作業の常時許可」節）に基づき、
この gap を埋める新規 project を起こし、ADR起票 → scaffold → 実装・テスト → GitHub
登録・push → manifest 登録 → 本 ADR、まで一気通貫で実施した。

## Decision

### 1. 新設 `kotoba-lang/nameserver`（public, MIT）— zone-clj/godaddy-dns-clj の間隙を埋める権威DNSサーバー

責務を6つの namespace に分離（全て `.cljc`、ソケット層のみ `.clj`、third-party
実行時依存ゼロ）:

- **`nameserver.wire`** — RFC 1035 §4 メッセージワイヤ形式 ⇄ EDN。RR (resource
  record) は `zone.model` の `:zone/*` 形状を**そのまま**再利用（`:zone/name` は
  絶対FQDN）— ゾーンファイル・EDN・ワイヤバイトが同一スキーマを共有し、変換
  レイヤーを増やさない。エンコーダは名前圧縮(RFC 1035 §4.1.4)を実装。デコーダは
  圧縮ポインタを解決するが、悪意あるポインタ循環に対する jump-count guard
  (128回で例外)を持つ — 未検証入力(生UDPパケット)を安全に捌く設計。
- **`nameserver.store`** — `{origin -> zone.model zone}` からの最長サフィックス
  一致による権威ルックアップ(exact/wildcard/CNAME chase/NODATA/NXDOMAIN)。
  AXFR/IXFR・DNSSEC・委任境界NSリファラルは非スコープ。
- **`nameserver.resolver`** — `IResolver` protocol(`godaddydns.dns/IDns` と
  同型の注入可能 capability)+ `chain-resolver`(先頭から試し `:refused` 以外で採用)。
- **`nameserver.custom-tld`** — alt-root カスタムTLD ⇄ IPNS/dnslink ブリッジ(下記4)。
- **`nameserver.delegate`** — サブドメイン委任用の NS+glue-A レコード編集セットを
  計算する純関数(`godaddydns.dns/IDns` へ渡す。下記3)。
- **`nameserver.server`**（`.clj` のみ）— JVM UDP/TCP ソケットリスナー(下記5)。

### 2. 実TLD(.com等)ホスティング = 「レジストラNS委任 + 自前権威応答」の組み合わせとして現実的にスコープ

ICANN root の権威になることは主張しない(誰にも不可能)。代わりに、**サブドメインの
NS委任**(レジストラで `ns.example.com` 等を本ネームサーバーの公開IPへ向ける)+
`nameserver.server` による実際のワイヤ応答、の組み合わせで「自分の持つ名前空間を
自前ネームサーバーでホスティングする」を実現する。委任に必要な NS+glue-A レコードは
`nameserver.delegate` の純関数が計算し、`godaddy-dns-clj` の `IDns`(dry-run既定)へ渡す。
ドメイン全体の apex ネームサーバー変更(レジストラアカウント操作)は `godaddy-dns-clj`
自体が非実装のため、ここでも非スコープと明記(過剰主張しない)。

### 3. 独自TLD(.hogehoge等)ホスティング = alt-root + IPNS/dnslink ブリッジとして誠実にスコープ

架空TLDへのグローバル解決権は誰にも付与できない(Handshake/ENS-via-gateway/
OpenNIC と同じ alt-root の現実)。`nameserver.custom-tld` が提供するのは:
このネームサーバーへ向けたクライアント(stub resolver / ブラウザ拡張 / 自前
クライアント)に対し、`ipns.core/name->pubkey` で構文検証できる鍵由来ラベルを
[dnslink](https://dnslink.io) 規約の TXT(`dnslink=/ipns/<name>`)として返す。
所有権移譲・共有token は不要(秘密鍵保持自体がその名前への authority、という
`ipns.core` と同じ設計思想)。IPNS のネットワーク解決自体(libp2p/publish/resolve)は
`ipns.core` 自身の非スコープと同じく非スコープ。

### 4. ソケット層のみ JVM(`.cljc`/`.kotoba` ランタイム優先順位の「最後の手段」適用)

`CLAUDE.md`(2026-07-07 改訂)の kotoba wasm > clojurewasm > ClojureScript >
nbb > JVM 優先順位に従うと、生UDP/TCPソケット bind は他ランタイムに移植先が無い
— kotoba wasm の `actor:host` ABI は閉じた host-import 表に raw socket
capability を持たず(ADR-2607062330)、ブラウザも UDP:53 を bind できない。
よって `nameserver.server` だけを JVM 限定とし、それ以外(wire/store/resolver/
custom-tld/delegate)は全て zero-dep `.cljc` を維持した。

## Rationale

- **データ第一・変換レイヤーを増やさない**: `zone.model` の `:zone/*` 形状を
  ワイヤ RR にも採用し、ゾーンファイル・EDN・ワイヤバイトが同一スキーマを共有。
- **注入可能な capability seam**: `IResolver` は `godaddydns.dns/IDns` /
  `computer-use-clj` の `mock-computer` と同じ設計原則。
- **誠実なスコープ境界**: 「グローバルな `.hogehoge` 解決」のような過大な主張を
  しない。zone-clj の「well-formed subset」・godaddy-dns-clj の「非スコープ」節と
  同じ流儀を踏襲。
- **実ソケットでの検証**: ユニットテストに加え、実際に `DatagramSocket` を bind して
  ワイヤバイトを送受信するテスト、および `dig` による手動検証を実施。この過程で
  `:zone/name` が zone-relative の `"@"` のまま返る(絶対FQDN化されていない)バグを
  ユニットテストでは検出できず、`dig` の出力を目視して発見・修正(`nameserver.store`)。
  ユニットテストにも回帰防止アサーションを追加済み — 実地検証(`dig`)が
  ユニットテストの死角を埋めた具体例。

## Consequences

- ASCIIラベルのみ、TXT/CAA は255オクテット以下、EDNS0/DNSSEC/AXFR非対応 —
  README(`orgs/kotoba-lang/nameserver/README.md`)に明記。
- レート制限/接続スロットリングは圧縮ポインタ loop guard 以外未実装。公開
  デプロイは別途 DoS 対策が必要。
- カスタムTLDブリッジは alt-root であり、クライアント側の設定なしにグローバルに
  解決されるわけではない。

## Verification

`clojure -M:dev:test`(local checkout)/ `clojure -M:test`(公開git deps、CI相当)
とも **27 tests / 54 assertions / 0 failures**（wire round-trip全型・名前圧縮・
悪意ポインタ循環・truncation、store の exact/wildcard/CNAME/NODATA/NXDOMAIN/ANY、
resolver chain、custom-tld の dnslink/CNAME/NXDOMAIN/REFUSED、delegate の純関数、
server の実ソケットUDP統合テスト4本)。`clojure -M:lint`(clj-kondo)0 errors/0
warnings。加えて `examples/run_server.clj` を起動し実 `dig` で A/CNAME/NXDOMAIN/
ANY/TCPフォールバック/カスタムTLD TXT・CNAME を目視確認。

## 実装状況（closing, 2026-07-08）

- **repo**: `kotoba-lang/nameserver`（MIT, public, init `199a50a`）。
- **west**: `manifest/repos.edn` の `:extra-projects` に登録、
  `nbb scripts/gen-west-manifest.cljs --entry nameserver` で最小diff生成、
  サーバ側 pin 検証 OK（"新規 entry, pin 199a50a1bdf6 は main から到達可能"）。
- **次段**: `godaddy-dns-clj` との実結線(実際にサブドメイン委任を1件通す
  end-to-end 検証)、custom-tld ブリッジの実クライアント側resolver設定手順の
  ドキュメント化、EDNS0/レート制限は将来の別ADR。

## 追記 1（2026-07-08）: リポジトリ rename

初期実装後、`kotoba-lang/nameserver` を `kotoba-lang/org-ietf-dns` へ改名した
（RFC 1035 は IETF RFC、既存 `org-ietf-turn`/`org-ietf-ical` 等と同じ
reverse-domain 命名precedent）。詳細は独立 ADR
`90-docs/adr/2607084500-nameserver-org-ietf-dns-rename.md` を参照。

## 追記 2（2026-07-08）: 実運用エントリポイント + 「次段」項目の一部解消

「実際にネームサーバーとして動かすには」という follow-up 質問を受け、
`kotoba-lang/org-ietf-dns` に以下を追加した（詳細は
`orgs/kotoba-lang/org-ietf-dns/docs/adr/0001-architecture.md` の
「7. 実運用エントリポイント」節）:

- **`nameserver.main`** — config.edn 駆動の CLI エントリポイント
  （`:host`/`:port`/`:zones-dir`/`:custom-tld`）。本 org 既存の
  env-var-config + shutdown-hook + `@(promise)` blocking パターン
  （`murakumo/relay_server.clj` 等）と同型。
- **`deploy/org-ietf-dns.service`**（systemd unit、`AmbientCapabilities=
  CAP_NET_BIND_SERVICE` で port 53 を root 無しに bind）。
- **`Dockerfile`**（ソースを Clojure CLI で直接動かす形 — 本 org 全体を
  調査した結果 `tools.build`/uberjar の前例が皆無だったため、新規導入せず
  既存の `ai-gftd-syosetsuka` 等と同じ形に合わせた。**Docker build 自体は
  作業環境に daemon が無く未検証** — README に明記済み）。
- README「Running for real」節に port 53 bind の3方法、NS委任への導線、
  および **custom-tld ブリッジの実クライアント側 resolver 設定手順**
  （`systemd-resolved`/`dnsmasq` の split-horizon 転送例）を追加。

これにより上記「次段」のうち「custom-tld ブリッジの実クライアント側resolver
設定手順のドキュメント化」は解消。残る次段は `godaddy-dns-clj` との実結線
end-to-end 検証と EDNS0/レート制限（引き続き将来の別ADR）。

`clojure -M -m nameserver.main examples/config.edn` を実際に起動し実 `dig`
で A/CNAME/カスタムTLD dnslink を再確認、`SIGTERM` での正常終了も確認済み。
