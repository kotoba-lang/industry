# Kotoba Platform

Kotoba Platformは、ProseMirrorベースのリッチテキストエディターとCytoscapeベースのAST可視化機能を統合したプラットフォームです。

## アーキテクチャ

このプロジェクトは**BitDevコンポーネント**を使用してコンポーネントを統合しています：

- **Editor Component**: ProseMirrorベースのリッチテキストエディター
- **Graph Component**: CytoscapeベースのAST可視化コンポーネント
- **Host Application**: コンポーネントを統合するメインアプリケーション

## 技術スタック

- **Frontend**: React 19, TypeScript, Vite
- **Editor**: ProseMirror
- **Graph Visualization**: Cytoscape.js
- **Component System**: BitDev
- **Styling**: Tailwind CSS (with Dark Mode support)

## 機能

### テーマ機能

- **ダークモード/ライトモード**: ユーザーがテーマを切り替え可能
- **システムテーマ自動検出**: OSの設定に基づいて自動でテーマを設定
- **テーマ設定の永続化**: ローカルストレージにテーマ設定を保存
- **スムーズなアニメーション**: テーマ切り替え時の滑らかなトランジション

### Editor機能

- リッチテキスト編集
- Markdownサポート
- AST生成
- リアルタイム更新

### Graph機能

- AST可視化
- インタラクティブな操作
- 複数のレイアウトタイプ
- データエクスポート

## 開発環境のセットアップ

### 前提条件

- Node.js 18+
- pnpm 8+

### インストール

```bash
# 依存関係のインストール
pnpm install

# 開発サーバーの起動
pnpm dev
```

### 開発サーバー

開発サーバーは以下のポートで起動します：

- **Host Application**: http://localhost:5173

## プロジェクト構造

```
@kotoba/
├── apps/
│   ├── host/                 # メインアプリケーション
│   │   ├── src/
│   │   │   ├── contexts/     # React Context (ThemeContext)
│   │   │   ├── components/   # UI Components (ThemeToggle)
│   │   │   └── App.tsx       # メインアプリケーション
│   │   └── ...
│   └── api/                  # NestJS API
├── kotoba/
│   └── components/
│       ├── editor/           # ProseMirrorエディターコンポーネント
│       ├── graph/            # Cytoscapeグラフコンポーネント
│       └── host/             # ホストコンポーネント
├── packages/
│   └── shared/               # 共有ライブラリ (Tailwind設定含む)
└── workspace.jsonc           # BitDevワークスペース設定
```

## コンポーネント統合

### BitDevコンポーネント

各コンポーネントは独立して開発・テスト可能で、BitDevを使用して統合されています：

- **Editor**: リッチテキスト編集とAST生成
- **Graph**: ASTデータの可視化とインタラクティブな操作

### データフロー

1. **Editor Component**: ユーザーがテキストを編集
2. **AST Generation**: 編集内容からASTデータを生成
3. **Data Transfer**: ASTデータをGraph Componentに送信
4. **Visualization**: Graph ComponentでASTを可視化

## テスト

### テストの実行

```bash
# 全テストの実行
pnpm test

# 特定のテストファイルの実行
pnpm test tests/theme.spec.ts
pnpm test tests/editor-ui.spec.ts
pnpm test tests/basic.spec.ts

# デバッグモードでのテスト実行
pnpm test:debug

# UIモードでのテスト実行
pnpm test:ui
```

### テストカバレッジ

- **Theme Tests**: ダークモード/ライトモードの切り替え機能
- **Editor UI Tests**: ProseMirrorエディターのUIとスタイリング
- **Basic Tests**: 基本的なアプリケーション動作

## ビルドとデプロイ

### ビルド

```bash
# 本番用ビルド
pnpm build

# プレビュー
pnpm preview
```

### デプロイ

Vercelを使用したデプロイが設定されています：

```bash
# Vercelへのデプロイ
vercel --prod
```

## テーマシステム

### 使用方法

1. **テーマ切り替え**: ヘッダーのテーマトグルボタンをクリック
2. **システムテーマ**: OSの設定に基づいて自動でテーマが設定されます
3. **永続化**: 選択したテーマはローカルストレージに保存されます

### 技術実装

- **React Context**: テーマ状態の管理
- **Tailwind CSS**: `darkMode: 'class'`を使用したクラスベースのダークモード
- **CSS Transitions**: スムーズなテーマ切り替えアニメーション

## 貢献

1. このリポジトリをフォーク
2. 機能ブランチを作成 (`git checkout -b feature/amazing-feature`)
3. 変更をコミット (`git commit -m 'Add some amazing feature'`)
4. ブランチにプッシュ (`git push origin feature/amazing-feature`)
5. プルリクエストを作成

## ライセンス

このプロジェクトはMITライセンスの下で公開されています。