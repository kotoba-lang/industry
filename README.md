# Junkawasaki.com

Junkawasaki.comの開発環境とプロジェクト群です。

## 🚀 開発環境のセットアップ

### DevContainerを使用した開発（推奨）

このプロジェクトはDevContainerを使用して開発環境を統一しています。

#### 前提条件
- [Docker Desktop](https://www.docker.com/products/docker-desktop/)
- [Visual Studio Code](https://code.visualstudio.com/)
- [Dev Containers extension](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers)

#### セットアップ手順

1. **リポジトリをクローン**
   ```bash
   git clone https://github.com/junkawasaki/junkawasaki.com.git
   cd junkawasaki.com
   ```

2. **DevContainerで開く**
   - VS Codeでプロジェクトフォルダを開く
   - `Ctrl+Shift+P` (または `Cmd+Shift+P`) でコマンドパレットを開く
   - `Dev Containers: Reopen in Container` を選択

3. **自動セットアップ**
   - コンテナのビルドと依存関係のインストールが自動で実行されます
   - 完了まで数分かかる場合があります

#### 利用可能なアプリケーション

##### Next.js アプリケーション
- **Webmaster** (ポート 1016) - メインウェブサイト
  ```bash
  dev-webmaster    # 開発サーバー起動
  test-webmaster   # テスト実行
  build-webmaster  # ビルド
  ```

- **Cytoscaper** (ポート 25720) - Cytoscape.jsベースのビジュアライゼーション
  ```bash
  dev-cytoscaper   # 開発サーバー起動
  test-cytoscaper  # テスト実行
  build-cytoscaper # ビルド
  ```

##### Python 研究プロジェクト
- **Gene Analysis** - 分子精神医学研究
  ```bash
  python-gene      # Pythonシェル起動
  jupyter-gene     # Jupyter Notebook起動
  ```

- **Physics Computational Framework** - 物理学計算フレームワーク
  ```bash
  python-physics   # Pythonシェル起動
  jupyter-physics  # Jupyter Notebook起動
  ```

#### 便利なコマンド

```bash
status             # 全プロジェクトの状態確認
```

#### 開発サーバーアクセス

- **Webmaster**: http://localhost:1016
- **Cytoscaper**: http://localhost:25720
- **Jupyter Notebook**: http://localhost:8888

### ローカル開発環境でのセットアップ

DevContainerを使用しない場合は、以下の手順でローカル環境をセットアップできます。

#### 前提条件
- Node.js 20.x
- Python 3.11
- pnpm

#### セットアップ手順

1. **Node.js依存関係のインストール**
   ```bash
   # Webmaster
   cd apps/webmaster
   pnpm install
   
   # Cytoscaper
   cd ../cytoscaper
   pnpm install
   ```

2. **Python依存関係のインストール**
   ```bash
   # Gene Analysis
   pip install -r apps/research/gene/analysis/requirements.txt
   
   # Physics Computational Framework
   pip install -r apps/research/physics_restructured/computational_framework/requirements.txt
   ```

## 📁 プロジェクト構造

```
junkawasaki.com/
├── apps/
│   ├── webmaster/          # メインウェブサイト (Next.js)
│   ├── cytoscaper/         # Cytoscape.jsビジュアライゼーション (Next.js)
│   ├── research/           # 研究プロジェクト
│   │   ├── gene/           # 分子精神医学研究
│   │   └── physics_restructured/ # 物理学計算フレームワーク
│   ├── ghosthacker/        # 電子書籍プロジェクト
│   └── arxiveorg/          # arXiv関連プロジェクト
├── .devcontainer/          # DevContainer設定
└── README.md
```

## 🛠️ 技術スタック

### フロントエンド
- **Next.js 15** - Reactフレームワーク
- **TypeScript** - 型安全なJavaScript
- **Tailwind CSS** - ユーティリティファーストCSS
- **Radix UI** - アクセシブルなUIコンポーネント
- **Framer Motion** - アニメーション
- **Three.js** - 3Dグラフィックス

### バックエンド
- **Supabase** - バックエンドサービス
- **PostgreSQL** - データベース
- **Drizzle ORM** - データベースORM

### 研究・分析
- **Python 3.11** - 科学計算
- **NumPy/SciPy** - 数値計算
- **Pandas** - データ分析
- **Matplotlib/Seaborn** - 可視化
- **Jupyter** - インタラクティブ開発環境

### 開発ツール
- **pnpm** - パッケージマネージャー
- **ESLint/Prettier** - コード品質
- **Jest/Playwright** - テスト
- **Black/Flake8/MyPy** - Pythonコード品質

## 🧪 テスト

### Next.jsアプリケーション
```bash
# Webmaster
cd apps/webmaster
pnpm test

# Cytoscaper
cd apps/cytoscaper
pnpm test
```

### Pythonプロジェクト
```bash
# Gene Analysis
cd apps/research/gene/analysis
pytest

# Physics Framework
cd apps/research/physics_restructured/computational_framework
pytest
```

## 🚀 デプロイ

### Vercel (Next.jsアプリケーション)
```bash
# Webmaster
cd apps/webmaster
vercel

# Cytoscaper
cd apps/cytoscaper
vercel
```

## 📚 ドキュメント

- [DevContainer詳細設定](.devcontainer/README.md)
- [Webmaster開発ガイド](apps/webmaster/README.md)
- [Gene Analysis研究](apps/research/gene/README.md)
- [Physics Framework](apps/research/physics_restructured/README.md)

## 🤝 コントリビューション

1. このリポジトリをフォーク
2. 機能ブランチを作成 (`git checkout -b feature/amazing-feature`)
3. 変更をコミット (`git commit -m 'Add some amazing feature'`)
4. ブランチにプッシュ (`git push origin feature/amazing-feature`)
5. プルリクエストを作成

## 📄 ライセンス

このプロジェクトはMITライセンスの下で公開されています。詳細は[LICENSE](LICENSE)ファイルを参照してください。

## 📞 お問い合わせ

- ウェブサイト: [junkawasaki.com](https://junkawasaki.com)
- GitHub: [@junkawasaki](https://github.com/junkawasaki) 