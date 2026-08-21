# kotobase — 読み戻せない secret と archive write token

## kotobase-graph-database の secret は Cloudflare の中にしか無い（2026-08-06 実測）

**Worker script `kotobase-cf-wasm-staging`（= 本番 `graph-database.kotobase.net` /
`backend.kotobase.net` を配信）が持つ 3 つの secret は、どこからも読み戻せない。**

| secret | 役割 | 可読な複製 |
|---|---|---|
| `KOTOBA_SEED` | 本番 operator Ed25519 seed。**IPNS head に署名する** | **無し** |
| `KOTOBASE_B2_KEY_ID` | B2 bucket 스코프鍵 | **無し** |
| `KOTOBASE_B2_APP_KEY` | 同上 | **無し** |

`env.testnet`（`kotobase-cf-wasm-testnet`）も同様に 4 件（上記 3 + `KOTOBASE_PROLLY_STANDALONE_CRYPTO_SEED`）
を持ち、`KOTOBA_SEED_TESTNET` も vault に無い。

**これが何を塞いでいるか。** Cloudflare の Worker secret は **per-script かつ
write-only** で、script 名を変えると別 script が生まれ secret はコピーされない。
つまり **`kotobase-cf-wasm-staging` を capability 名に改名できない**（runtime 名 +
間違った環境名で本番を配信し続ける）。`kotobase-graph-database/wrangler.jsonc` の
`env.testnet` R2 セクションが「That is what blocked kotobase-cf-wasm-staging from
becoming net-kotobase-engine」と書いているのはこの制約のこと。

**`KOTOBA_SEED` は単なる資格情報ではない。** これが変わると operator DID が変わり、
content-addressed graph の名前空間ごと変わる — 障害ではなく**データ面の同一性の変更**。
「動かなくなったら再発行すればいい」で済む種類のものではない。

**解除するには（agent 単独ではできない、安全床①）**:
1. オーナーが 3 値を供給して kagi（compartment `net-kotobase`）に保管する、または
2. B2 → **R2 binding** 移行を先に完了させる（binding は資格情報を要さないので B2 の
   2 件が消える。`env.testnet` は既に `KOTOBASE_R2` binding を持っている）。`KOTOBA_SEED`
   は残るので、これだけでは足りない。

**agent は seed を推測・再生成して代替しないこと。** 新しい seed は別 DID = 別グラフで、
既存の head チェーンから切り離される。


## kotobase.net archive write token (2026-07-29)

- **`KOTOBASE_ARCHIVE_TOKEN`（kagi vault、compartment `net-kotobase`）** —
  `kotobase.net` の first-party content-addressed archive（`PUT /ipfs/:cid`、
  `kotobase.archive-put`）の write gate。Worker `net-kotobase` の同名 secret と同値。
  **この Worker secret は以前から設定されていたが値はどこにも保管されておらず
  読み戻せなかった**ため、2026-07-29 にオーナー承認のもと再発行した
  （workspace 全体を grep して consumer ゼロを確認した上で交換）。
  取得: `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get KOTOBASE_ARCHIVE_TOKEN`。
- ⚠ **2026-08-21 実測: この item は kagi vault に存在しない。**
  `kagi get KOTOBASE_ARCHIVE_TOKEN` は **`no such item`**（exit 1、2 回再現）。
  vault 自体は開けている —— 同じ呼び方の `KOTOBASE_ARCHIVE_TOKEN_2` は同一 invocation で
  値を返すので、これは「vault が開けなかった」ではなく**その名前の item が無い**という測定。
- ⚠ **手元の `KOTOBASE_ARCHIVE_TOKEN_2` は live Worker に受理されない**（同日実測、`PUT` → 401）。
  ADR-2608147400 が記録しているとおり、2026-08-14 に **Worker の slot 2 へ kagi item
  `KOTOBASE_ARCHIVE_TOKEN` の値**を入れている。つまり live が受理する値は
  **kagi に無い方の名前**で保管されていたことになり、`..._2` という kagi item は
  deploy されていない別の値を持っている。**custody 記録と deployment がずれている。**
  Worker secret は書き込み専用で読み戻せない（本ファイル冒頭の注記）ので、
  解消はオーナー操作 —— 現行値を documented な名前で kagi に入れ直すか、
  手元の `..._2` の値を Worker の空きスロットに入れるか、のどちらか。
- **使い方**: raw CIDv1（`bafkrei…` = 本体バイト列の sha2-256）を自分で計算し、
  `PUT https://kotobase.net/ipfs/<cid>` に Bearer で置く。サーバが digest を再計算して
  不一致は 422 で弾く。読みは無認証の `GET /ipfs/<cid>`。**未設定だと 403（feature off）**。
  live 検証済み（2026-07-29、PUT 201 → GET 200、バイト一致）。
- 消費側: `dougaka.archive`（production artifact の保管、ADR-2607299960）。
