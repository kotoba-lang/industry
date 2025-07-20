# 数学理論生物体系図 - Next.js版

**Mathematical Theory Organism Visualization - Next.js Edition**

数学理論の相互関係を生物学的メタファーで可視化し、編集可能なインタラクティブな図表として実装したNext.jsアプリケーションです。

## 🌟 特徴

### 🧬 生物学的メタファー
- **🌱 基礎論（根系）**: 数学の土台となる理論群
- **🌳 純粋数学（幹・枝）**: 数学の主要分野とその発展
- **🍃 応用数学（葉・花）**: 実用的な数学分野
- **🍎 学際領域（果実・種）**: 数学と他分野の融合

### ✨ インタラクティブ機能
- **リアルタイム編集**: ノードとエッジの追加・編集・削除
- **複数レイアウト**: 5種類のレイアウトアルゴリズム
- **関係性ハイライト**: ノード間の依存関係の可視化
- **データ管理**: エクスポート・インポート・ローカル保存

### 🎨 モダンUI
- **Tailwind CSS**: レスポンシブで美しいUI
- **グラスモーフィズム**: 透明感のあるモダンデザイン
- **アニメーション**: スムーズなトランジション効果
- **ダークテーマ**: 目に優しいダークUI

## 🚀 技術スタック

- **フレームワーク**: Next.js 15 + React 19
- **言語**: TypeScript
- **スタイリング**: Tailwind CSS 4
- **可視化**: Cytoscape.js
- **パッケージマネージャー**: pnpm

## 📦 依存関係

```json
{
  "cytoscape": "^3.32.1",
  "cytoscape-dagre": "^2.5.0",
  "cytoscape-cose-bilkent": "^4.1.0",
  "dagre": "^0.8.5"
}
```

## 🛠️ インストール

```bash
# リポジトリをクローン
git clone <repository-url>
cd cytoscaper

# 依存関係をインストール
pnpm install

# 開発サーバーを起動
pnpm dev
```

## 📖 使い方

### 基本操作
1. **閲覧モード**:
   - ノードをクリック: 詳細情報表示
   - マウスホバー: ハイライト効果
   - ドラッグ: ビューの移動
   - スクロール: ズーム

2. **編集モード**:
   - 「編集モード」ボタンでオン/オフ切り替え
   - ダブルクリック: ノード/エッジの編集
   - 右クリック: 要素の削除

### コントロール

#### 🎮 ビューコントロール
- **リセット**: ビューを初期状態に戻す
- **全体表示**: すべての要素が見えるように調整
- **レイアウト変更**: 5種類のレイアウトを循環

#### ✏️ 編集コントロール
- **編集モード**: 編集機能のオン/オフ
- **ノード追加**: 新しい理論ノードを作成
- **エッジ追加**: 理論間の関係を追加
- **削除**: 選択した要素を削除

#### 💾 データ管理
- **エクスポート**: 現在の図をJSONでダウンロード
- **インポート**: JSONファイルから図を読み込み

### レイアウトアルゴリズム

1. **Cose**: 力学ベースの自然なレイアウト
2. **Circle**: 円形配置
3. **Breadthfirst**: 階層的な幅優先配置
4. **Grid**: グリッド配置
5. **Concentric**: 同心円配置

## 📁 プロジェクト構造

```
cytoscaper/
├── src/
│   ├── app/
│   │   ├── globals.css           # グローバルスタイル
│   │   ├── layout.tsx            # レイアウトコンポーネント
│   │   └── page.tsx              # メインページ
│   └── components/
│       └── CytoscapeVisualization.tsx  # メイン可視化コンポーネント
├── public/
│   └── data/
│       ├── complete-theory-data.json   # 完全版データ
│       └── detailed-theory-data.json   # 詳細版データ
├── backup/                       # バックアップファイル
├── package.json
├── tsconfig.json
├── tailwind.config.ts
└── README.md
```

## 🎨 スタイルガイド

### カラーパレット
- **基礎論**: `#654321` (褐色系)
- **純粋数学**: `#2E7D32` (緑色系)
- **応用数学**: `#F57F17` (黄色系)
- **学際領域**: `#D32F2F` (赤色系)
- **アクセント**: `#00BCD4` (シアン)

### ノードタイプ
- **foundation_root**: 基礎論（根系）- 六角形
- **trunk**: 主幹 - 角丸長方形
- **branch_***: 各分野の枝 - 角丸長方形
- **leaf**: 葉 - 楕円形
- **fruit**: 果実 - 八角形
- **seed**: 種 - 三角形

## 🔧 開発

### 開発サーバー
```bash
pnpm dev          # 開発サーバー起動
pnpm build        # 本番ビルド
pnpm start        # 本番サーバー起動
pnpm lint         # ESLint実行
```

### カスタマイズ

#### 新しいノードタイプの追加
1. `CytoscapeVisualization.tsx`のスタイル配列に新しいセレクターを追加
2. ノード編集フォームの選択肢に追加

#### 新しいエッジタイプの追加
1. エッジスタイルセクションに新しいスタイルを定義
2. エッジ編集フォームの選択肢に追加

#### レイアウトアルゴリズムの追加
1. `toggleLayout`関数にレイアウト名を追加
2. switch文にレイアウトオプションを定義

## 📊 データ形式

### ノード構造
```typescript
interface NodeData {
  id: string;
  label: string;
  type: string;
  category: string;
  level: number;
  description: string;
  details: string;
}
```

### エッジ構造
```typescript
interface EdgeData {
  source: string;
  target: string;
  type: string;
  label: string;
}
```

## 🎯 今後の拡張予定

- [ ] 3D可視化対応
- [ ] アニメーション効果の強化
- [ ] 検索・フィルタリング機能
- [ ] ユーザーアカウント管理
- [ ] リアルタイム共同編集
- [ ] モバイル最適化
- [ ] PWA対応

## 🤝 コントリビューション

1. Forkしてください
2. Feature branchを作成してください (`git checkout -b feature/AmazingFeature`)
3. 変更をCommitしてください (`git commit -m 'Add some AmazingFeature'`)
4. Branchにプッシュしてください (`git push origin feature/AmazingFeature`)
5. Pull Requestを開いてください

## 📄 ライセンス

このプロジェクトはMITライセンスの下で公開されています。

## 🙏 謝辞

- [Cytoscape.js](https://cytoscape.org/) - グラフ可視化ライブラリ
- [Next.js](https://nextjs.org/) - Reactフレームワーク
- [Tailwind CSS](https://tailwindcss.com/) - CSSフレームワーク

---

**🌟 数学の美しい関係性を生物学的な視点で探索しましょう！**
