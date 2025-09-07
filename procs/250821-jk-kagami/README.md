# Kagami Editor

ProseMirrorをベースにしたリッチテキストエディタライブラリ。イベントソーシング設計でトランザクションをKafkaにSSOTとして保持し、プロジェクションによる状態再構築を行う設計。

## 特徴

- **ProseMirror** ベースの高性能リッチテキストエディタ
- **構造化文書**: スキーマベースの文書モデル
- **イベントソーシング**: 全ての変更をKafkaにイベントとして記録
- **表計算機能**: HyperFormulaをProseMirror内に統合
- **Excel インポート**: ExcelファイルをProseMirrorの表として表示
- **リアルタイム同期**: 状態変更のリアルタイム反映
- **TypeScript**: 完全なTypeScript対応

## インストール

```bash
npm install @junkawasaki/kagami
# または
pnpm add @junkawasaki/kagami
```

## 基本的な使用方法

### 開発モード（インメモリ）

```typescript
import { createDevKagamiEditor } from '@junkawasaki/kagami';

const editor = createDevKagamiEditor(
  document.getElementById('editor-container'),
  'console.log("Hello, Kagami!");'
);
```

### 本格的な設定

```typescript
import { KagamiEditor } from '@junkawasaki/kagami';

const editor = new KagamiEditor(
  document.getElementById('editor-container'),
  {
    config: {
      kafka: {
        brokers: ['localhost:9092'],
        clientId: 'my-app',
        topic: 'kagami-events'
      },
      editor: {
        initialContent: 'console.log("Hello, World!");',
        language: 'javascript'
      }
    }
  }
);

await editor.initialize();
```

## API

### エディタ操作

```typescript
// コンテンツの設定・取得
editor.setContent('新しいコンテンツ');
const content = editor.getContent();

// カーソル操作
editor.setCursor(10);
editor.setSelection(0, 10);

// テキスト操作
editor.insertText('挿入テキスト');
editor.replaceText(0, 5, '置換テキスト');
```

### 表計算機能

```typescript
// 表を挿入
editor.insertTable(3, 3);

// セルの値を設定
editor.setCellValue(0, 0, 'Hello');
editor.setCellValue(0, 1, '=A1&" World"');

// セルの値を取得
const value = editor.getCellValue(0, 1);

// シート管理
const sheetId = editor.createSheet('NewSheet');
editor.removeSheet(sheetId);
```

### Excel インポート機能

```typescript
// Excelファイルをインポート
const fileInput = document.getElementById('excel-file') as HTMLInputElement;
const file = fileInput.files[0];

const importResult = await editor.importExcelFile(file, {
  hasHeader: true,
  maxRows: 1000,
  maxColumns: 100
});

console.log(`${importResult.tables.length}個の表を作成しました`);
console.log(`${importResult.sheets.length}個のシートをインポートしました`);

// ArrayBufferからのインポート
const arrayBuffer = await file.arrayBuffer();
const result = await editor.importExcelFromArrayBuffer(arrayBuffer, {
  hasHeader: true
});

// 表の管理
const tables = editor.getTables();
const basicTable = editor.createTable(5, 3); // 5行3列の基本表
editor.deleteTable(basicTable.id);

// インポートイベントの監視
editor.excelImportEvents$.subscribe(event => {
  console.log(`Excel インポート: ${event.type}`, event.data);
});
```

### 状態監視

```typescript
// 状態の変更を監視
editor.state$.subscribe(state => {
  console.log('状態が変更されました:', state);
});

// エディタのみの変更を監視
editor.editorState$.subscribe(editorState => {
  console.log('エディタの状態:', editorState);
});
```

## アーキテクチャ

```
┌─────────────────────────────────────────────────────────────┐
│                    EditorCore                               │
│  ┌─────────────────┐    ┌─────────────────┐               │
│  │   ProseMirror   │    │   HyperFormula  │               │
│  │     (Editor)    │    │ (Spreadsheet)   │               │
│  └─────────────────┘    └─────────────────┘               │
└─────────────────────────────────────────────────────────────┘
                                 │
                    ┌─────────────────┐
                    │TransactionManager│
                    │    (Commands)    │
                    └─────────────────┘
                                 │
                    ┌─────────────────┐
                    │   EventStore    │
                    │    (Kafka)      │
                    └─────────────────┘
                                 │
                    ┌─────────────────┐
                    │ProjectionSystem │
                    │ (State Rebuild) │
                    └─────────────────┘
```

## 開発

```bash
# 依存関係のインストール
pnpm install

# ビルド
pnpm run build

# 開発用ビルド（監視モード）
pnpm run watch
```

## ライセンス

MIT License