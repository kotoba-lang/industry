# ADR-2607051621: cloud-murakumo AGPLv3 OSS化 + ISIC/ISCO ネットワークレジストリ + kotoba-lang 共有決済台帳ライブラリ

**Status**: accepted(一部修正、下記Amendment参照)
**Date**: 2026-07-05
**Deciders**: Jun Kawasaki
**Scope**: `orgs/gftdcojp/cloud-murakumo`, `orgs/gftdcojp/cloud-itonami`, `orgs/gftdcojp/local-murakumo`, 新設 `orgs/kotoba-lang/treasury`

## Context

米国VCからの資金調達検討の中で、`cloud-murakumo`(分散GPUレンタルクラウド「Sora」)について
「OSSでISIC/ISCOに基づき業務を構造化し、AGPLv3で全公開、そのシステムに参加する企業から
報酬を得る、誰でもSaaSを運用できるようにしたい」という方向性が決まった。

並行して、crypto建ての資金調達については米国証券法(Howey test)上「出資に該当しない」形が
必要という整理を行い、(1) 実プロダクトへの支払い(ステーブルコイン建て実収益)と
(3) Gitcoin Grants型 quadratic funding donation の2手段を採用することにした。

AGPLライセンス自体は「改変してSaaSとして提供するなら改変部分のソースを公開する義務」を
課すだけで、参加企業からの金銭的な報酬支払いを法的に強制する仕組みではない。実際に報酬を
得るには、AGPLに加えて別レイヤーが必要という認識で、(1) ネットワーク登録/認証バッジ、
(2) オンチェーンprotocol fee、(3) デュアルライセンス の3方式を組み合わせる方針とした。

登録先は `cloud-itonami` の既存 `itonami.cloud/{org}/{repo}`(CACAO/did:key認証、
Business Model Canvas公開の「operator cockpit」)を流用する想定だったが、調査の結果:

- 第三者の自己登録フローは現状ゼロ(gftdcojp自社ベンチャーの手動seedのみ)。
- `GET /api/open-business` レジストリは静的・ハードコードのgftdcojpブループリント一覧で、
  動的な外部テナント一覧ではない。

protocol feeの決済基盤について、`local-murakumo`(旧 `cloud-murakumo`、ADR-2607041302で
2026-07-04に改名。`app.itonami.cloud`/`api.murakumo.cloud` の実体)に、実運用中の
USDC→Gnosis Safe決済(`src/local_murakumo/itonami.cljc`、treasury `0xA00366234D29d4F882088048c0B2fa0dB7302D4E`、
Ethereum/Base/Arbitrum対応)が既に存在することが判明した。`crypto-quote` /
`crypto-topup-entry` / `crypto-pending-entry` / `verify-payment` / `pending-payments` /
`payment-status` / `etherscan-row->onchain` は、itonami固有の値付け
(`credits-per-usd` / `topup-fee-frac` / `plans` / ISCO verticals / model-catalog)から
既に綺麗に分離されたpure関数群であり、汎用ライブラリとして切り出し可能な形をしている。

共通化の置き場所はkotoba-lang orgとする(オーナー指示:「共通化できる部分はkotoba-lang org
で共通化」)。kotoba-langは既存の命名慣習上、単語ベースの短い名前(`cacao`, `btc-crypto`,
`banking`等)を使っており、決済/台帳を専業とする既存repoは無い。

## Decision

1. **cloud-murakumo**: AGPLv3へrelicenseする。業務ロジックはcloud-itonamiの
   ISIC/ISCOブループリント資産(21/21 section coverage, 26業種レジストリ)を輸入し、
   業種別モジュールとして再構成する。
2. **資金調達手段**: 米国証券法上の出資該当性を避けるため、(a) ステーブルコイン建て
   実収益、(b) Gitcoin Grants型 quadratic funding donation を採用する。SAFT等、
   将来のトークン給付を約束する投資契約型は不採用とする。
3. **参加企業からの報酬モデル**: 4層構造とする。
   - **Layer 0(無料)**: AGPLv3準拠の自己ホスト。義務は改変ソースの公開のみ。
     ここが「誰でもSaaSを運用できる」を担保する。
   - **Layer 1(ネットワーク登録)**: `cloud-itonami` の `itonami.cloud` に自己登録する
     (運用者自身が自分のdid:key CACAOを自己mintし、自分の`{org}/{repo}`テナントに
     bindする。中央の承認/共有トークンは不要)。ISIC/ISCOタグ付きで
     `/api/open-business` に動的掲載される。登録料はブランド使用権/掲載/相互運用性
     認証への対価であり、投資リターンではない。
   - **Layer 2(オンチェーンprotocol fee)**: 登録運用者は稼働収益の一定割合を
     treasuryへstablecoinでストリーミングする。決済基盤は下記4のkotoba-lang
     共有ライブラリ経由とする。
   - **Layer 3(デュアルライセンス)**: AGPLの改変ソース公開義務を避けたい企業向けに
     商用ライセンスを別売りする。Layer 0の「誰でも無料運用できる」とは独立。
4. **`kotoba-lang/treasury`(新設)**: `local-murakumo` の `itonami.cljc` から、
   chains設定・`crypto-quote`・`crypto-topup-entry`・`crypto-pending-entry`・
   `verify-payment`・`pending-payments`・`payment-status`・`etherscan-row->onchain`・
   `min-confirmations`・汎用append-only台帳エントリschemaを抽出し、ドメイン非依存の
   OSSライブラリとして切り出す。`credits-per-usd` / fee率 / plans等の値付けは
   呼び出し側(`local-murakumo`, `cloud-murakumo` それぞれ)が注入する。
5. **cloud-itonami**: 第三者自己登録用のPages Function(CACAO bind)と、
   `GET /api/open-business` の動的化(登録済みテナントをISIC/ISCOタグ付きで一覧)を
   追加する。

## Execution(段階的。各ステップは個別に検証してから次へ進む)

1. [本ADR] 設計を確定する。
2. `kotoba-lang/treasury` をscaffoldする(標準手順: ADR起票 → scaffold
   `.cljc` + `deps.edn` + README + test → `git init` + 初期コミット →
   `gh repo create` + push → manifest登録)。
3. `local-murakumo` を `treasury` 依存へリファクタする。実運用中のtreasury
   アドレス(`0xA003...`)の挙動が変わらないことを既存テスト+ドライランで確認して
   からマージする(生きている決済コードのため、マージ前にユーザー確認を挟む)。
4. `cloud-itonami` に自己登録フロー + 動的レジストリを実装する。
5. `cloud-murakumo` をAGPLv3化・ISIC/ISCO再構成・`treasury`依存での
   protocol fee実装・`cloud-itonami`登録連携まで仕上げる。

## Consequences

- (+) crypto決済/台帳の実装が1箇所(`treasury`)に集約され、`local-murakumo`
  と`cloud-murakumo`の二重実装を避けられる。
- (+) AGPL自体では強制できない金銭対価を、登録+protocol fee+デュアルライセンスの
  組み合わせで、証券性(Howey test)を避けながら実現できる。
- (+) `itonami.cloud` が「gftdcojp内製ベンチャーの掲示板」から「外部運用者も参加
  できるネットワークレジストリ」へ拡張される。
- (-) `local-murakumo` の実運用中の決済コードに手を入れるため、切り出し後の移行は
  慎重な検証が必要(生きているtreasuryアドレスへの実際の資金移動に影響しうる)。
- (-) Layer 2のprotocol feeは技術的強制力がない(性善説 + レジストリ除名のみ)。
  メータリングによる自動徴収は将来課題として残る。
- (-) Layer 1の登録料/バッジの説明が一貫していないと、証券的な「参加すれば得する」
  商品に見えるリスクが残る(marketingの一貫性が必要)。

## Related

- ADR-2607041302: murakumoファミリー命名整理(`local-murakumo`/`cloud-murakumo`)。
- `cloud-itonami` ADR-0009(open-business)、ADR-0010(itonami-cli-canvas-lean-funding)。
- 本ADRに先立つ会話: 米国VC資金調達戦略の検討、および非証券crypto資金調達の
  法的整理(Howey test、DePIN型対価モデル等)。

## Amendment(2026-07-05 17:54、実行状況とスコープ変更)

Execution 1〜4は完了。Execution 5(`cloud-murakumo`本体のAGPLv3化)はオーナー
判断により**行わない**ことになった:「`cloud-murakumo`本体はprivateでOK」。

**実施済み**:

1. `kotoba-lang/treasury`(https://github.com/kotoba-lang/treasury) を新設。
   `local-murakumo`の`itonami.cljc`からchains設定/`crypto-quote`/`verify-payment`等の
   USDC決済プリミティブを抽出、テスト7件30assertion、manifest登録済み。
2. `cloud-itonami`にADR-0013として第三者自己登録(`POST /api/{org}/{repo}/register`、
   CACAO resource-scoped、first-come claim)+ `GET /api/open-business`の動的化を実装。
   PR https://github.com/gftdcojp/cloud-itonami/pull/29 (レビュー待ち、mainには未マージ
   — `itonami.cloud`がmainから自動デプロイされる本番環境のため)。
3. `local-murakumo`をkotoba-lang/treasury依存へ**限定的に**リファクタ(chains/
   chain-cfg/crypto-asset/usdc-per-usd/min-confirmations/etherscan-row->onchainの
   静的設定のみ委譲。`quote-topup`等、本番KVに既に保存されている台帳形式を
   直接読み書きする関数群は意図的に未変更)。特性テスト新規作成、35テスト238assertion
   全パス。PR https://github.com/gftdcojp/local-murakumo/pull/31 (レビュー待ち)。

**行わないことになった部分(Execution 5、Decision 1/3の一部)**:

- `cloud-murakumo`本体のAGPLv3 relicenseは**しない**。private repoのまま。
- Decision 3の4層報酬モデルのうち **Layer 0(AGPL無料自己ホスト)と Layer 3
  (デュアルライセンス)は前提(OSS化)が無くなったため成立しない**。
- Layer 1(itonami.cloudネットワーク登録)とLayer 2(kotoba-lang/treasury経由の
  protocol fee)は、`cloud-murakumo`自身がOSSでなくとも利用可能なインフラとして
  既に実装済み — 将来`cloud-murakumo`または別プロダクトが「有償で登録・
  protocol feeを払って参加する」形を採る場合の土台として残す。ISIC/ISCO業種
  再構成(Decision 1後段)も現時点では見送り。

**Status**は上記の通り「accepted(一部修正)」とし、Execution 5と紐づくDecision
1/3の該当箇所は本Amendmentにより取り下げられたものとして読む(ADR自体は再作成
せず、実行結果としてこのAmendmentで閉じる)。
