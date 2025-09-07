# 🌐 KawaDB Browser Edition - Next.js サンプルアプリケーション

## 概要

KawaDB Browser Editionは、サーバーを必要とせず、ブラウザのみで動作するイベントソーシングデータベースです。
このNext.jsアプリケーションは、KawaDB Browser EditionのWebAssembly（WASM）実装を使用したデモアプリケーションです。

## 特徴

- 🚀 **サーバレス**: サーバーの設定・管理が不要
- 🔄 **イベントソーシング**: 全ての変更をイベントとして記録
- 💾 **永続化**: ブラウザのLocalStorageに自動保存
- ☁️ **クラウド同期**: オンライン/オフライン同期対応（設定可能）
- 🎯 **型安全**: TypeScriptで完全に型付け
- 🌐 **クロスブラウザ**: モダンブラウザ全てに対応

## 技術スタック

- **フロントエンド**: Next.js 15 + React 19 + TypeScript
- **スタイリング**: Tailwind CSS + shadcn/ui
- **データベース**: KawaDB Browser Edition (Rust + WASM)
- **パッケージマネージャー**: pnpm

## 開発環境セットアップ

### 前提条件

- Node.js 20以上
- pnpm
- Rust toolchain (WASMビルド用)

### インストール

```bash
# 依存関係のインストール
pnpm install

# WASMライブラリのビルド（必要に応じて）
cd ../../kawa-wasm
wasm-pack build --target web --out-dir ../apps/website/public/pkg

# 開発サーバーの起動
cd ../website
pnpm dev
```

### 起動

```bash
pnpm dev
```

ブラウザで `http://localhost:3000` にアクセス

## アプリケーション機能

### 1. データベース操作
- ユーザー情報の入力
- イベントタイプの選択
- イベントの追加・削除

### 2. リアルタイム表示
- イベント履歴の表示
- 現在の状態の表示
- 統計情報の表示

### 3. クラウド同期
- オンライン/オフライン検知
- 自動同期設定
- 競合解決

## 使用方法

1. **イベント追加**
   - ユーザー名とメールアドレスを入力
   - イベントタイプを選択
   - 「イベント追加」ボタンをクリック

2. **データ表示**
   - 最新イベントが自動的に表示
   - 現在の状態が再構築されて表示
   - 統計情報がリアルタイムで更新

3. **データ管理**
   - 「データクリア」で全データを削除
   - LocalStorageに自動保存

## KawaDB Browser Editionについて

KawaDB Browser Editionは、次の特徴を持つイベントソーシングデータベースです：

- **完全ブラウザ実装**: サーバーが不要
- **イベントソーシング**: 全ての変更をイベントとして記録
- **状態再構築**: イベントから現在の状態を再構築
- **永続化**: ブラウザストレージに自動保存
- **同期機能**: クラウドとの双方向同期

## 開発

### プロジェクト構造

```
apps/website/
├── src/
│   ├── app/
│   │   ├── page.tsx          # メインページ
│   │   ├── layout.tsx        # レイアウト
│   │   └── globals.css       # グローバルスタイル
│   ├── components/
│   │   └── ui/               # shadcn/uiコンポーネント
│   └── lib/
│       └── utils.ts          # ユーティリティ関数
├── public/
│   └── pkg/                  # WASMビルド出力
├── package.json
├── tailwind.config.js
└── next.config.js
```

### ビルド

```bash
# 本番用ビルド
pnpm build

# 静的エクスポート
pnpm export
```

### デプロイ

```bash
# Vercel
vercel --prod

# 静的ホスティング
pnpm build && pnpm export
```

## トラブルシューティング

### WASMモジュール読み込みエラー

```bash
# WASMライブラリを再ビルド
cd ../../kawa-wasm
wasm-pack build --target web --out-dir ../apps/website/public/pkg
```

### 型エラー

```bash
# 型チェック
pnpm type-check

# 型生成
pnpm build
```

## ライセンス

このプロジェクトは MIT ライセンスの下で配布されています。

## 貢献

プルリクエストや Issue の報告を歓迎します。

## 🌐 外部サービスからの利用

KawaDB-WASMは、他のWebアプリケーションからも簡単に利用できます。

### クイックスタート

```html
<!-- 直接HTMLから使用 -->
<script type="module">
  import init, { KawaBrowserDB, BrowserDBConfig, StorageType } from '/pkg/kawa_wasm.js';
  
  async function initKawaDB() {
    await init();
    const config = new BrowserDBConfig();
    config.set_storage_type(StorageType.LocalStorage);
    const db = new KawaBrowserDB(config);
    
    // イベントを追加
    await db.add_event('user_action', JSON.stringify({
      action: 'click',
      timestamp: Date.now()
    }));
    
    // イベントを取得
    const events = await db.get_events(10);
    console.log(JSON.parse(events));
  }
  
  initKawaDB();
</script>
```

### React/Vue/Angular での使用

```typescript
// React Hook例
import { useState, useEffect } from 'react';

export function useKawaDB() {
  const [db, setDb] = useState(null);
  
  useEffect(() => {
    const initDB = async () => {
      const wasmModule = await import('/pkg/kawa_wasm.js');
      await wasmModule.default();
      
      const config = new wasmModule.BrowserDBConfig();
      config.set_storage_type(wasmModule.StorageType.LocalStorage);
      setDb(new wasmModule.KawaBrowserDB(config));
    };
    
    initDB();
  }, []);
  
  return db;
}
```

詳細な使用例については、[外部サービス使用例](../../kawa-wasm/external-service-examples.md)をご確認ください。

### 利用例

- **Analytics**: ページビューやユーザー行動の追跡
- **ログ収集**: アプリケーションエラーやイベントログの収集
- **A/Bテスト**: テスト結果の記録と分析
- **リアルタイム通知**: ユーザー行動に基づく通知システム

## 関連リンク

- [KawaDB メインリポジトリ](../../)
- [KawaDB Browser Edition WASM](../../kawa-wasm/)
- [外部サービス使用例](../../kawa-wasm/external-service-examples.md)
- [デプロイメントガイド](../../kawa-wasm/deployment-guide.md)
- [Next.js ドキュメント](https://nextjs.org/docs)
- [shadcn/ui ドキュメント](https://ui.shadcn.com/)
