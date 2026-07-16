# ADR-2607012230: kotoba-lang に Bitcoin mining / mining pool / マルチチェーン wallet substrate を追加する

Status: Proposed
Date: 2026-07-01

## Context

オーナーから「kotoba-lang に mining, mining pool を設計し、bitcoin mining を kotoba clj で
行えるようにし、wallet も設計せよ。wallet は MetaMask や Coinbase Wallet のように
Ethereum SIWE として Ethereum 互換で、Bitcoin など他の通貨も扱えるように」という依頼。

着手前に3点を確認した:

1. 成果物の深さ → **ADR + 動く最小 scaffold**（ドキュメントのみではない）。
2. 「mining」の意味 → **実ハッシュ計算エンジン（CPU, 教育/検証用）**。ASIC 級の実運用競争力は
   対象外と明示。
3. リポジトリ構成 → オーナー指摘の通り、これは **actor ではなく lib**。`20-actors/`
   の LLM proposal ⊣ governor パターンは適用しない（意思決定を行う知能ノードが存在しない
   決定的な暗号/プロトコルコードのため）。

`kotoba-lang` には既に再利用可能な基盤 library がある:

- **`eth-crypto`**（`orgs/kotoba-lang/eth-crypto`）: secp256k1 の点演算（`pt-add`/`pt-mul`/`G`）、
  RFC 6979 決定論的 ECDSA 署名 + EIP-2 low-s、`ecrecover`、Keccak-256、EIP-55、EIP-712、
  RLP、EIP-155 legacy tx 署名。**Bitcoin も secp256k1** なので EC 演算と ECDSA 署名は
  そのまま再利用できる。
- **`crypto`**（`orgs/kotoba-lang/crypto`, ns `kotoba.lang.crypto`）: SHA-256/512 hash、
  HMAC-SHA256、HKDF、AEAD の data contract（cipher は host 注入）。
- **`cacao`**（`orgs/kotoba-lang/cacao`）: CAIP-122/SIWE 形式の CBOR capability token だが
  **Ed25519 did:key で自己署名する actor 内部認証**専用（`kotoba/write.cljs` の鍵由来
  IPNS 権威モデル用）。**real Ethereum アカウント（secp256k1）による MetaMask 実運用の
  SIWE とは非互換**——今回 wallet に必要なのは後者。

したがって新規 library は cacao を置き換えず、eth-crypto/crypto の上に積む。

## Decision

`kotoba-lang` org（github.com/kotoba-lang/<name>）に **4 つの新規 .cljc library** を追加する。
いずれも plain library（governor/checkpoint 等の actor 機構は使わない）。

### 1. `kotoba-lang/btc-crypto`

Bitcoin 固有の暗号・エンコーディング。`eth-crypto`（secp256k1 EC 演算 + ECDSA）と
`crypto`（SHA-256）に依存し、以下を追加する:

- RIPEMD-160（JDK に無いため pure 実装。eth-crypto の Keccak 同様の自己完結スタイル）
- SHA256d（二重 SHA-256）
- Base58Check エンコード/デコード、WIF
- Bech32 / Bech32m（BIP-173 / BIP-350）
- アドレス導出: P2PKH（legacy）、P2WPKH（native segwit v0）
- BIP-32 HD 鍵導出（`HMAC-SHA512("Bitcoin seed", seed)` によるマスター鍵、CKDprv の
  hardened/normal 両方、eth-crypto の `pt-mul` で拡張公開鍵を導出）
- BIP-44 パス builder（`m/44'/0'/account'/change/index`、testnet は coin type `1'`）
- BIP-39 `mnemonic→seed`（PBKDF2-HMAC-SHA512, 2048 round）。**wordlist は
  ハードコードしない**（後述ガードレール）
- 最小限の tx 構築・署名（legacy P2PKH sighash ALL、BIP-143 P2WPKH witness sighash）

BIP-32 test vector 1 / Base58Check・WIF 既知ベクタ / BIP-173 Bech32 既知ベクタ /
genesis block ヘッダーの既知ハッシュで検証する。

### 2. `kotoba-lang/btc-mining`

CPU の PoW 計算エンジン（**教育/検証用と明示。ASIC 対抗の実運用性能は主張しない**）。
`btc-crypto` の SHA256d に依存。

- block header 構造体（version/prev-hash/merkle-root/time/bits/nonce）
- merkle root 計算（Bitcoin 特有の「奇数個は最後を複製」仕様を含む）
- bits ⇄ target 変換、difficulty 計算、PoW 合否判定
- nonce 探索ループ（32-bit nonce 空間を使い切ったら extranonce/time をロールする設計）
- `getblocktemplate` 形状の入力 → header 組み立て

### 3. `kotoba-lang/mining-pool`

Stratum v1 (JSON-RPC over newline-delimited TCP) の**プロトコル/ロジック層**
（v1 はネットワークデーモンの運用強化までは対象外、後述）。`btc-mining` + `btc-crypto` に依存。

- Stratum メッセージの encode/decode（`mining.subscribe`/`authorize`/`notify`/`submit`/
  `set_difficulty`）
- share 検証（share target ≤ pool difficulty。block target とは別軸）
- vardiff コントローラ
- **append-only の share 台帳**（EDN。監査性のため——actor の governor/checkpoint
  機構ではなく、単純な追記ログとして）
- payout scheme はプラガブルにし、PPLNS を参照実装として同梱

### 4. `kotoba-lang/wallet`

MetaMask / Coinbase Wallet 型の non-custodial マルチチェーン wallet。
`btc-crypto` + `eth-crypto` + `crypto` に依存。

- 単一の BIP-32/39/44 seed → チェーンごとの `ChainDriver` プロトコル
  （`derive-path` / `address-of` / `sign-tx`）。`:eth` driver（eth-crypto 再利用）と
  `:btc` driver（btc-crypto 再利用）を同梱し、registry 経由で他チェーン
  （Dogecoin/Litecoin は btc-clone パラメータ差し替え、別カーブ族の chain も可）を
  後から追加できる設計にする。
- **本物の SIWE (EIP-4361)**: `cacao` の Ed25519 版とは別に、`personal_sign` 相当
  （`"\x19Ethereum Signed Message:\n" + len(msg) + msg` → keccak256 → secp256k1-sign、
  検証は `ecrecover`）を実装し、**siwe.js / viem / ethers 等の実 Ethereum tooling が
  そのまま検証できる**署名を生成する——「SIWE 形式もどき」ではなく実際に
  dApp ログインに使える。
- 秘密鍵は wallet の外に出さない（non-custodial）。ローカル暗号化キーストアの形は
  `crypto` の AEAD data contract を再利用する（実 cipher は host 注入、テスト用の
  mock AEAD のみ同梱——`crypto` 自身のドキュメント済みパターンと同じ）。

## 登録ワークフロー

CLAUDE.md の standing authorization（新規 project scaffold → 登録の一連フロー）に従う。
4 library それぞれについて:

1. scaffold（`.cljc` 正本 + `deps.edn` + `README.md` + test。eth-crypto/cacao と同じ
   テンプレート: Apache-2.0 `LICENSE`、`.gitignore`、`.github/workflows/ci.yml`
   （`clojure -M:test`、JDK 17/21 matrix））
2. `git init` + 初期コミット
3. `gh repo create kotoba-lang/<name> --private` + push
4. `manifest/repos.edn` の `:extra-projects` にパスを追加
5. `nbb scripts/gen-west-manifest.cljs`（+ `--check`）
6. superproject へ ADR + manifest 差分を `chore(manifest)+docs(adr)` でコミット

## ガードレール

- **BIP-39 wordlist は手で書き起こしてソースに埋め込まない。** 2048 語のリストを
  記憶から書き起こすと1語でも誤りがあれば実際の recovery phrase を静かに破壊しうる
  （安全上のクリティカルパス）。呼び出し側が公式ソース（bitcoin/bips の `english.txt`）
  + チェックサムで用意した wordlist resource を注入する contract にする。
  `mnemonic→seed`（PBKDF2）自体は wordlist 不要なので先に実装する。
- 秘密鍵はログ/テレメトリに一切出さない。`deps.edn` はサードパーティ zero-dep 方針を
  維持し、kotoba-lang 内 git 依存のみ許可する。
- mining/pool は明示的に CPU/教育スコープ。ASIC 対抗性能や本番プール運用実績は主張しない。
- 既存の force-push 禁止・main 同期・shallow 既定は4リポジトリすべてに適用する。

## Out of scope（follow-up）

- Stratum v2（バイナリプロトコル）、GBT longpoll/ZMQ ブロック通知、
  mining-pool の本番 TCP サーバー運用強化。
- ハードウェアウォレット連携、WalletConnect/EIP-1193 provider shim、
  ブラウザ拡張 UI。
- BTC/ETH 以外のチェーン driver（Litecoin/Dogecoin/Solana 等）——registry 設計上は
  追加可能だが同梱しない。
- Lightning Network。
