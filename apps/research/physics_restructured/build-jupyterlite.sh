#!/bin/bash

# JupyterLite ビルドスクリプト
# 生成的情報物理学研究フレームワーク用

echo "🚀 JupyterLite サイトをビルド中..."

# 環境のセットアップ
echo "📦 依存関係をインストール中..."
pip install -r requirements-lite.txt

# 出力ディレクトリの準備
echo "🗂️ 出力ディレクトリを準備中..."
rm -rf _output
mkdir -p _output

# JupyterLiteサイトのビルド
echo "🔨 JupyterLiteサイトをビルド中..."
jupyter lite build --contents notebooks --output-dir _output

# faviconとロゴを追加
echo "🎨 ファビコンとロゴを追加中..."
if [ -f "favicon.ico" ]; then
    cp favicon.ico _output/
fi

# カスタムCSSを追加
echo "🎨 カスタムスタイルを追加中..."
cat >> _output/lab/static/style.css << EOF
/* 日本語フォント対応 */
.jp-LabShell {
    font-family: 'Hiragino Sans', 'Yu Gothic', 'Meiryo', sans-serif;
}

/* カスタムテーマ色 */
:root {
    --jp-brand-color1: #2E86AB;
    --jp-brand-color2: #A23B72;
    --jp-accent-color1: #F18F01;
}
EOF

echo "✅ JupyterLiteサイトのビルドが完了しました!"
echo "📂 出力ディレクトリ: _output/"
echo "🌐 GitHub Pagesで配信可能です"

# ビルド統計を表示
echo ""
echo "📊 ビルド統計:"
echo "- Notebooks: $(find notebooks -name "*.ipynb" | wc -l)"
echo "- サイズ: $(du -sh _output | cut -f1)" 