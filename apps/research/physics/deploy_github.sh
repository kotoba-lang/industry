#!/bin/bash

# GitHub公開用デプロイスクリプト
# Generative Physics Cosmology Framework

set -e

echo "🚀 Generative Physics Cosmology Framework - GitHub Publication Script"
echo "========================================================================"

# プロジェクト設定
PROJECT_NAME="generative-physics-cosmology"
GITHUB_USERNAME="junkawasaki"
GITHUB_REPO_URL="https://github.com/${GITHUB_USERNAME}/${PROJECT_NAME}.git"

# ディレクトリの作成と移動
echo "📁 Setting up project structure..."
mkdir -p ${PROJECT_NAME}
cd ${PROJECT_NAME}

# Git リポジトリの初期化
echo "📋 Initializing Git repository..."
git init
git config user.name "Jun Kawasaki"
git config user.email "root+physics@junkawasaki.com"

# 主要ファイルのコピー
echo "📄 Copying core files..."
cp ../README_github.md README.md
cp ../LICENSE LICENSE
cp ../requirements.txt requirements.txt
cp ../setup.py setup.py

# パッケージ構造の作成
echo "📦 Creating package structure..."
mkdir -p generative_physics/{computational,new_physics,observations,visualization}
mkdir -p examples
mkdir -p tests
mkdir -p docs

# 主要モジュールのコピー
echo "🔬 Copying physics modules..."
cp ../exascale_cosmology_framework.py generative_physics/computational/
cp ../250709_Exascale_Computing_Cosmology.py generative_physics/computational/
cp ../sigma8_problem_solution.py generative_physics/
cp ../axion_dark_matter.py generative_physics/new_physics/
cp ../new_physics_integration.py generative_physics/new_physics/

# 可視化ファイルのコピー
echo "🎨 Copying visualization files..."
cp ../video_abstract_generative_physics.html generative_physics/visualization/
cp ../interactive_cosmic_evolution.html generative_physics/visualization/

# 例のコピー
echo "💡 Copying examples..."
cp ../examples/quick_start.py examples/

# Jupyter notebookのコピー（簡略化）
echo "📓 Copying notebook..."
cp ../250709_Generative_Phylosophycal_Cosmology.ipynb examples/full_framework_demo.ipynb

# __init__.py ファイルの作成
echo "🐍 Creating __init__.py files..."
cat > generative_physics/__init__.py << 'EOF'
"""
Generative Physics Cosmology Framework
======================================

A unified computational framework for cosmological evolution through information processing.

Author: Jun Kawasaki
License: MIT
"""

__version__ = "1.2.0"
__author__ = "Jun Kawasaki"
__email__ = "root+physics@junkawasaki.com"

from .cosmology import CosmologyFramework
from .sigma8_problem_solution import solve_sigma8_problem
from .computational.exascale_cosmology_framework import ExascaleCosmologyFramework

__all__ = [
    'CosmologyFramework',
    'solve_sigma8_problem',
    'ExascaleCosmologyFramework',
]
EOF

# 各サブモジュールの __init__.py
touch generative_physics/computational/__init__.py
touch generative_physics/new_physics/__init__.py
touch generative_physics/observations/__init__.py
touch generative_physics/visualization/__init__.py

# テストファイルの作成
echo "🧪 Creating test files..."
cat > tests/test_sigma8_solution.py << 'EOF'
"""
Test suite for σ₈ problem solution
"""

import pytest
import numpy as np
from generative_physics import solve_sigma8_problem

def test_sigma8_basic():
    """Basic test for σ₈ problem solution"""
    result = solve_sigma8_problem()
    assert result['accuracy'] < 0.05  # Less than 5% error
    assert 0.8 < result['sigma8_unified'] < 0.9  # Reasonable range
    assert result['error'] < 0.02  # Error bar less than 2%

def test_sigma8_precision():
    """Test precision target"""
    result = solve_sigma8_problem(precision_target=0.03)
    assert result['accuracy'] < 0.03
    assert result['target_achieved'] is True

if __name__ == "__main__":
    pytest.main([__file__])
EOF

# .gitignore の作成
echo "🚫 Creating .gitignore..."
cat > .gitignore << 'EOF'
# Byte-compiled / optimized / DLL files
__pycache__/
*.py[cod]
*$py.class

# C extensions
*.so

# Distribution / packaging
.Python
build/
develop-eggs/
dist/
downloads/
eggs/
.eggs/
lib/
lib64/
parts/
sdist/
var/
wheels/
*.egg-info/
.installed.cfg
*.egg
MANIFEST

# PyInstaller
*.manifest
*.spec

# Installer logs
pip-log.txt
pip-delete-this-directory.txt

# Unit test / coverage reports
htmlcov/
.tox/
.coverage
.coverage.*
.cache
nosetests.xml
coverage.xml
*.cover
.hypothesis/
.pytest_cache/

# Translations
*.mo
*.pot

# Django stuff:
*.log
local_settings.py
db.sqlite3

# Flask stuff:
instance/
.webassets-cache

# Scrapy stuff:
.scrapy

# Sphinx documentation
docs/_build/

# PyBuilder
target/

# Jupyter Notebook
.ipynb_checkpoints

# pyenv
.python-version

# celery beat schedule file
celerybeat-schedule

# SageMath parsed files
*.sage.py

# Environments
.env
.venv
env/
venv/
ENV/
env.bak/
venv.bak/

# Spyder project settings
.spyderproject
.spyproject

# Rope project settings
.ropeproject

# mkdocs documentation
/site

# mypy
.mypy_cache/
.dmypy.json
dmypy.json

# Pyre type checker
.pyre/

# Custom
output/
data/
results/
*.hdf5
*.h5
*.dat
*.fits
*.json
.DS_Store
EOF

# GitHub Actions ワークフロー
echo "⚙️ Creating GitHub Actions workflow..."
mkdir -p .github/workflows
cat > .github/workflows/ci.yml << 'EOF'
name: CI

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]

jobs:
  test:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        python-version: [3.8, 3.9, '3.10', 3.11]

    steps:
    - uses: actions/checkout@v2
    
    - name: Set up Python ${{ matrix.python-version }}
      uses: actions/setup-python@v2
      with:
        python-version: ${{ matrix.python-version }}
    
    - name: Install dependencies
      run: |
        python -m pip install --upgrade pip
        pip install -r requirements.txt
        pip install -e .
    
    - name: Run tests
      run: |
        pytest tests/
    
    - name: Run example
      run: |
        python examples/quick_start.py
EOF

# CONTRIBUTINGガイドの作成
echo "📖 Creating CONTRIBUTING.md..."
cat > CONTRIBUTING.md << 'EOF'
# Contributing to Generative Physics Cosmology Framework

Thank you for your interest in contributing to the Generative Physics Cosmology Framework!

## Development Setup

1. Fork the repository
2. Clone your fork
3. Create a virtual environment
4. Install dependencies: `pip install -e .`
5. Run tests: `pytest tests/`

## Code Style

- Follow PEP 8
- Use type hints
- Include docstrings (NumPy format)
- Maintain test coverage >85%

## Pull Request Process

1. Create a feature branch
2. Make your changes
3. Add tests
4. Update documentation
5. Submit PR with clear description

## Issues

Please use GitHub Issues for bug reports and feature requests.
EOF

# 初期コミットの作成
echo "📝 Creating initial commit..."
git add .
git commit -m "Initial commit: Generative Physics Cosmology Framework

- Complete framework implementation
- σ₈ and H₀ problem solutions
- Quantum gravity integration
- AI/ML acceleration
- Visualization tools
- Comprehensive documentation
- Example scripts and tests"

# リモートリポジトリの設定
echo "🌐 Setting up remote repository..."
git branch -M main
git remote add origin ${GITHUB_REPO_URL}

# タグの作成
echo "🏷️ Creating release tag..."
git tag -a v1.2.0 -m "Release v1.2.0: Full framework implementation"

echo ""
echo "✅ Setup complete! Next steps:"
echo "1. Create GitHub repository: ${GITHUB_REPO_URL}"
echo "2. Push to GitHub: git push -u origin main"
echo "3. Push tags: git push origin --tags"
echo "4. Set up repository settings (description, topics, etc.)"
echo "5. Create release notes on GitHub"
echo ""
echo "🎉 Your Generative Physics Cosmology Framework is ready for publication!"
echo "📚 Don't forget to update the arXiv paper with the GitHub repository link."
echo ""
echo "Repository URL: ${GITHUB_REPO_URL}"
echo "Package name: ${PROJECT_NAME}"
echo "License: MIT"
echo "Author: Jun Kawasaki"
echo ""
echo "Happy coding! 🌌" 