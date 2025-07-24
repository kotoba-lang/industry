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
- **Package Manager**: pnpm

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
│   ├── components/
│   │   ├── editor/           # ProseMirrorエディターコンポーネント
│   │   ├── graph/            # Cytoscapeグラフコンポーネント
│   │   └── host/             # ホストコンポーネント
│   └── kotoba-app/           # 統合アプリケーション
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

1. Editorコンポーネントでテキストを編集
2. ASTデータが生成され、Graphコンポーネントに送信
3. GraphコンポーネントでASTを可視化

## テーマシステム

### 使用方法

1. **テーマ切り替え**: ヘッダー右上のテーマトグルボタンをクリック
2. **自動検出**: 初回アクセス時にシステムテーマを自動検出
3. **設定保存**: テーマ設定はローカルストレージに自動保存

### 技術実装

- **ThemeContext**: React Contextを使用したテーマ状態管理
- **Tailwind CSS**: `darkMode: 'class'`設定によるクラスベースのダークモード
- **CSS Transitions**: スムーズなテーマ切り替えアニメーション

## 開発

### コンポーネント開発

```bash
# エディターコンポーネントの開発
cd kotoba/components/editor
pnpm dev

# グラフコンポーネントの開発
cd kotoba/components/graph
pnpm dev
```

### テスト

```bash
# 全テストの実行
pnpm test

# テーマ機能のテスト
pnpm test tests/theme.spec.ts

# Playwrightテスト
pnpm test:headed
```

## デプロイ

```bash
# ビルド
pnpm build

# プレビュー
pnpm preview
```

## ライセンス

MIT License