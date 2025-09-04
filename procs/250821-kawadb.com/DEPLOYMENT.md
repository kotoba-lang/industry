# 📦 Kawaデプロイメントガイド

## 🚀 公開可能な状況

### ✅ 準備完了
- **kawa-storage**: コンパイル成功、メタデータ完備
- **kawa-cli**: コンパイル成功、メタデータ完備  
- **kawa-broker**: コンパイル成功（kawa-storage依存）
- **kawa-db**: コンパイル成功（kawa-storage依存）

### 🔧 要修復
- **kawa-wasm**: 34件のコンパイルエラー
- **apps/website**: TypeScript/ESLintエラー

## 📋 公開手順

### 1. crates.io 公開

#### ステップ1: アカウント設定
```bash
# crates.ioアカウント作成 (https://crates.io/)
# APIトークンを取得

# Cargoにログイン
cargo login
# APIトークンを入力
```

#### ステップ2: 依存関係順での公開
```bash
# 1. 基盤クレート（依存なし）
cargo publish -p kawa-storage
cargo publish -p kawa-cli

# 2. 依存クレート（kawa-storage依存）
cargo publish -p kawa-broker
cargo publish -p kawa-db

# 注意: kawa-wasmは修復完了後に公開
```

#### ステップ3: 公開確認
```bash
# 公開状況確認
cargo search kawa-storage
cargo search kawa-cli
cargo search kawa-broker  
cargo search kawa-db
```

### 2. npmパッケージ公開（将来）

```bash
# kawa-wasm修復後
cd kawa-wasm
wasm-pack build --target web --out-dir pkg
wasm-pack publish
```

### 3. ウェブサイトデプロイ

#### Vercel Deploy
```bash
cd apps/website
# エラー修正後
pnpm build
vercel --prod
```

#### Netlify Deploy
```bash
cd apps/website
# エラー修正後
pnpm build
netlify deploy --prod --dir=.next
```

## 📊 現在の公開準備状況

| クレート | コンパイル | メタデータ | 依存関係 | 公開可能 |
|---------|------------|------------|-----------|----------|
| kawa-storage | ✅ | ✅ | なし | **🚀 準備完了** |
| kawa-cli | ✅ | ✅ | なし | **🚀 準備完了** |
| kawa-broker | ✅ | ✅ | kawa-storage | **🚀 準備完了** |
| kawa-db | ✅ | ✅ | kawa-storage | **🚀 準備完了** |
| kawa-wasm | ❌ | ✅ | kawa-db | 🔧 要修復 |

## 🎯 推奨公開順序

### Phase 1: 即座に公開可能
1. `cargo publish -p kawa-storage`
2. `cargo publish -p kawa-cli`

### Phase 2: 依存解決後公開
3. `cargo publish -p kawa-broker`  
4. `cargo publish -p kawa-db`

### Phase 3: WASM修復後
5. `wasm-pack publish` (kawa-wasm)
6. ウェブサイトデプロイ

## 🔧 トラブルシューティング

### エラー: no token found
```bash
cargo login
# https://crates.io/me でAPIトークンを取得
```

### エラー: crate already exists
```bash
# バージョンを上げて再公開
# Cargo.tomlのversion = "0.1.1"
```

### 依存関係エラー
```bash
# kawa-storageを先に公開してから依存クレートを公開
```

## ✅ 公開後の確認

1. **crates.io確認**: https://crates.io/crates/kawa-storage
2. **ドキュメント**: https://docs.rs/kawa-storage
3. **ダウンロード数**: crates.ioのダッシュボード
4. **利用方法**: `cargo add kawa-storage`

## 🚀 今すぐ実行可能

```bash
# APIトークン設定後すぐに実行可能
cargo publish -p kawa-storage
cargo publish -p kawa-cli

# 成功後すぐに実行可能  
cargo publish -p kawa-broker
cargo publish -p kawa-db
```

**🎉 4つのクレートすべて公開準備完了！** 