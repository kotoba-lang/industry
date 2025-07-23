#!/bin/bash

# Junkawasaki.com Development Environment Setup Script
set -e

echo "🚀 Setting up Junkawasaki.com development environment..."

# Update package lists
echo "📦 Updating package lists..."
sudo apt-get update

# Install additional system dependencies
echo "🔧 Installing system dependencies..."
sudo apt-get install -y \
    build-essential \
    curl \
    wget \
    git \
    vim \
    nano \
    htop \
    tree \
    jq \
    unzip \
    zip \
    ca-certificates \
    gnupg \
    lsb-release

# Install pnpm globally
echo "📦 Installing pnpm..."
npm install -g pnpm@latest

# Install Python dependencies
echo "🐍 Installing Python dependencies..."

# Install pip packages for research projects
pip install --upgrade pip
pip install \
    numpy \
    pandas \
    matplotlib \
    seaborn \
    scipy \
    scikit-learn \
    jupyter \
    ipython \
    black \
    flake8 \
    mypy \
    pytest \
    pytest-cov \
    pre-commit

# Install project-specific Python requirements
if [ -f "apps/research/gene/analysis/requirements.txt" ]; then
    echo "📊 Installing gene analysis requirements..."
    pip install -r apps/research/gene/analysis/requirements.txt
fi

if [ -f "apps/research/physics_restructured/computational_framework/requirements.txt" ]; then
    echo "🔬 Installing physics computational framework requirements..."
    pip install -r apps/research/physics_restructured/computational_framework/requirements.txt
fi

# Install Node.js dependencies for Next.js projects
echo "⚛️ Installing Node.js dependencies..."

# Install webmaster dependencies
if [ -f "apps/webmaster/package.json" ]; then
    echo "🌐 Installing webmaster dependencies..."
    cd apps/webmaster
    pnpm install
    cd ../..
fi

# Install cytoscaper dependencies
if [ -f "apps/cytoscaper/package.json" ]; then
    echo "🕸️ Installing cytoscaper dependencies..."
    cd apps/cytoscaper
    pnpm install
    cd ../..
fi

# Setup Git configuration
echo "🔧 Setting up Git configuration..."
git config --global init.defaultBranch main
git config --global pull.rebase false

# Create useful aliases
echo "📝 Creating useful aliases..."
cat >> ~/.bashrc << 'EOF'

# Development aliases
alias dev-webmaster="cd apps/webmaster && pnpm dev"
alias dev-cytoscaper="cd apps/cytoscaper && pnpm dev"
alias test-webmaster="cd apps/webmaster && pnpm test"
alias test-cytoscaper="cd apps/cytoscaper && pnpm test"
alias build-webmaster="cd apps/webmaster && pnpm build"
alias build-cytoscaper="cd apps/cytoscaper && pnpm build"

# Python development aliases
alias python-gene="cd apps/research/gene/analysis && python"
alias python-physics="cd apps/research/physics_restructured/computational_framework && python"
alias jupyter-gene="cd apps/research/gene/analysis && jupyter notebook"
alias jupyter-physics="cd apps/research/physics_restructured/computational_framework && jupyter notebook"

# Utility aliases
alias ll="ls -la"
alias ..="cd .."
alias ...="cd ../.."
alias ....="cd ../../.."

# Project status
alias status="echo '=== Project Status ===' && echo 'Webmaster: ' && cd apps/webmaster && pnpm --version && cd ../.. && echo 'Cytoscaper: ' && cd apps/cytoscaper && pnpm --version && cd ../.. && echo 'Python: ' && python --version && echo 'Node: ' && node --version && echo 'pnpm: ' && pnpm --version"
EOF

# Setup pre-commit hooks
echo "🔒 Setting up pre-commit hooks..."
if [ -f ".pre-commit-config.yaml" ]; then
    pre-commit install
fi

# Create development workspace configuration
echo "⚙️ Creating workspace configuration..."
mkdir -p .vscode

cat > .vscode/settings.json << 'EOF'
{
  "typescript.preferences.includePackageJsonAutoImports": "on",
  "typescript.suggest.autoImports": true,
  "editor.formatOnSave": true,
  "editor.defaultFormatter": "esbenp.prettier-vscode",
  "editor.codeActionsOnSave": {
    "source.fixAll.eslint": "explicit",
    "source.organizeImports": "explicit"
  },
  "python.defaultInterpreterPath": "/usr/local/bin/python",
  "python.formatting.provider": "black",
  "python.linting.enabled": true,
  "python.linting.flake8Enabled": true,
  "python.linting.mypyEnabled": true,
  "python.testing.pytestEnabled": true,
  "jest.autoRun": {
    "watch": false,
    "onSave": "test-file"
  },
  "tailwindCSS.includeLanguages": {
    "typescript": "javascript",
    "typescriptreact": "javascript"
  },
  "files.associations": {
    "*.css": "tailwindcss"
  },
  "search.exclude": {
    "**/node_modules": true,
    "**/dist": true,
    "**/.next": true,
    "**/.cache": true,
    "**/__pycache__": true,
    "**/*.pyc": true
  }
}
EOF

cat > .vscode/launch.json << 'EOF'
{
  "version": "0.2.0",
  "configurations": [
    {
      "name": "Debug Webmaster",
      "type": "node",
      "request": "launch",
      "program": "${workspaceFolder}/apps/webmaster/node_modules/.bin/next",
      "args": ["dev", "-p", "1016"],
      "cwd": "${workspaceFolder}/apps/webmaster",
      "console": "integratedTerminal",
      "env": {
        "NODE_ENV": "development"
      }
    },
    {
      "name": "Debug Cytoscaper",
      "type": "node",
      "request": "launch",
      "program": "${workspaceFolder}/apps/cytoscaper/node_modules/.bin/next",
      "args": ["dev", "-p", "25720"],
      "cwd": "${workspaceFolder}/apps/cytoscaper",
      "console": "integratedTerminal",
      "env": {
        "NODE_ENV": "development"
      }
    },
    {
      "name": "Python: Current File",
      "type": "python",
      "request": "launch",
      "program": "${file}",
      "console": "integratedTerminal",
      "justMyCode": true
    }
  ]
}
EOF

cat > .vscode/tasks.json << 'EOF'
{
  "version": "2.0.0",
  "tasks": [
    {
      "label": "Start Webmaster Dev Server",
      "type": "shell",
      "command": "cd apps/webmaster && pnpm dev",
      "group": "build",
      "presentation": {
        "echo": true,
        "reveal": "always",
        "focus": false,
        "panel": "new"
      },
      "problemMatcher": []
    },
    {
      "label": "Start Cytoscaper Dev Server",
      "type": "shell",
      "command": "cd apps/cytoscaper && pnpm dev",
      "group": "build",
      "presentation": {
        "echo": true,
        "reveal": "always",
        "focus": false,
        "panel": "new"
      },
      "problemMatcher": []
    },
    {
      "label": "Test Webmaster",
      "type": "shell",
      "command": "cd apps/webmaster && pnpm test",
      "group": "test",
      "presentation": {
        "echo": true,
        "reveal": "always",
        "focus": false,
        "panel": "new"
      },
      "problemMatcher": []
    },
    {
      "label": "Test Cytoscaper",
      "type": "shell",
      "command": "cd apps/cytoscaper && pnpm test",
      "group": "test",
      "presentation": {
        "echo": true,
        "reveal": "always",
        "focus": false,
        "panel": "new"
      },
      "problemMatcher": []
    },
    {
      "label": "Build Webmaster",
      "type": "shell",
      "command": "cd apps/webmaster && pnpm build",
      "group": "build",
      "presentation": {
        "echo": true,
        "reveal": "always",
        "focus": false,
        "panel": "new"
      },
      "problemMatcher": []
    },
    {
      "label": "Build Cytoscaper",
      "type": "shell",
      "command": "cd apps/cytoscaper && pnpm build",
      "group": "build",
      "presentation": {
        "echo": true,
        "reveal": "always",
        "focus": false,
        "panel": "new"
      },
      "problemMatcher": []
    }
  ]
}
EOF

# Create a comprehensive README for the devcontainer
cat > .devcontainer/README.md << 'EOF'
# Junkawasaki.com Development Container

このdevcontainerは、Junkawasaki.comプロジェクトの開発環境を提供します。

## 含まれる技術スタック

- **Node.js 20** - Next.jsアプリケーション用
- **Python 3.11** - 研究プロジェクト用
- **pnpm** - パッケージマネージャー
- **TypeScript** - 型安全なJavaScript開発
- **Jest** - テストフレームワーク
- **Playwright** - E2Eテスト
- **Black/Flake8/MyPy** - Pythonコード品質ツール

## 利用可能なアプリケーション

### Next.js アプリケーション
- **Webmaster** (ポート 1016) - メインウェブサイト
- **Cytoscaper** (ポート 25720) - Cytoscape.jsベースのビジュアライゼーション

### Python 研究プロジェクト
- **Gene Analysis** - 分子精神医学研究
- **Physics Computational Framework** - 物理学計算フレームワーク

## 便利なコマンド

### 開発サーバー起動
```bash
dev-webmaster      # Webmaster開発サーバー起動
dev-cytoscaper     # Cytoscaper開発サーバー起動
```

### テスト実行
```bash
test-webmaster     # Webmasterテスト実行
test-cytoscaper    # Cytoscaperテスト実行
```

### Python開発
```bash
python-gene        # Gene分析プロジェクトでPython起動
python-physics     # PhysicsプロジェクトでPython起動
jupyter-gene       # Gene分析用Jupyter起動
jupyter-physics    # Physics用Jupyter起動
```

### プロジェクト状態確認
```bash
status             # 全プロジェクトの状態確認
```

## ポート設定

- **1016** - Webmaster (Next.js)
- **25720** - Cytoscaper (Next.js)
- **3000** - デフォルトNext.js
- **8000** - Python開発サーバー
- **8888** - Jupyter Notebook

## 開発ワークフロー

1. devcontainerを起動
2. 必要な依存関係が自動インストールされる
3. 各アプリケーションのディレクトリで開発開始
4. 自動フォーマット・リント・テストが有効

## トラブルシューティング

### 依存関係の再インストール
```bash
# Node.js依存関係
cd apps/webmaster && pnpm install
cd apps/cytoscaper && pnpm install

# Python依存関係
pip install -r apps/research/gene/analysis/requirements.txt
pip install -r apps/research/physics_restructured/computational_framework/requirements.txt
```

### キャッシュクリア
```bash
# Node.jsキャッシュ
pnpm store prune

# Pythonキャッシュ
find . -type d -name "__pycache__" -exec rm -rf {} +
find . -name "*.pyc" -delete
```
EOF

echo "✅ Development environment setup complete!"
echo ""
echo "🎉 Welcome to Junkawasaki.com development environment!"
echo ""
echo "📋 Available commands:"
echo "  dev-webmaster    - Start Webmaster development server"
echo "  dev-cytoscaper   - Start Cytoscaper development server"
echo "  test-webmaster   - Run Webmaster tests"
echo "  test-cytoscaper  - Run Cytoscaper tests"
echo "  python-gene      - Python shell for gene analysis"
echo "  python-physics   - Python shell for physics research"
echo "  jupyter-gene     - Jupyter notebook for gene analysis"
echo "  jupyter-physics  - Jupyter notebook for physics research"
echo "  status           - Check project status"
echo ""
echo "🌐 Development servers will be available at:"
echo "  Webmaster: http://localhost:1016"
echo "  Cytoscaper: http://localhost:25720"
echo ""
echo "📚 See .devcontainer/README.md for more information" 