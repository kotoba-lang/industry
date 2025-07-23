#!/bin/bash

# Post-create setup script for Junkawasaki.com devcontainer
set -e

echo "🎯 Running post-create setup..."

# Install project-specific dependencies
echo "📦 Installing project dependencies..."

# Install Next.js project dependencies
if [ -f "apps/webmaster/package.json" ]; then
    echo "🌐 Installing webmaster dependencies..."
    cd apps/webmaster
    pnpm install
    cd ../..
fi

if [ -f "apps/cytoscaper/package.json" ]; then
    echo "🕸️ Installing cytoscaper dependencies..."
    cd apps/cytoscaper
    pnpm install
    cd ../..
fi

# Install Python project dependencies
if [ -f "apps/research/gene/analysis/requirements.txt" ]; then
    echo "📊 Installing gene analysis requirements..."
    pip install -r apps/research/gene/analysis/requirements.txt
fi

if [ -f "apps/research/physics_restructured/computational_framework/requirements.txt" ]; then
    echo "🔬 Installing physics computational framework requirements..."
    pip install -r apps/research/physics_restructured/computational_framework/requirements.txt
fi

# Setup pre-commit hooks if config exists
if [ -f ".pre-commit-config.yaml" ]; then
    echo "🔒 Setting up pre-commit hooks..."
    pre-commit install
fi

# Create development workspace configuration
echo "⚙️ Setting up workspace configuration..."
mkdir -p .vscode

# Create VS Code settings if they don't exist
if [ ! -f ".vscode/settings.json" ]; then
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
fi

# Create launch configurations if they don't exist
if [ ! -f ".vscode/launch.json" ]; then
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
fi

# Create tasks if they don't exist
if [ ! -f ".vscode/tasks.json" ]; then
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
fi

echo "✅ Post-create setup complete!"
echo ""
echo "🎉 Your development environment is ready!"
echo ""
echo "📋 Quick start commands:"
echo "  dev-webmaster    - Start Webmaster development server"
echo "  dev-cytoscaper   - Start Cytoscaper development server"
echo "  status           - Check project status"
echo ""
echo "🌐 Development servers:"
echo "  Webmaster: http://localhost:1016"
echo "  Cytoscaper: http://localhost:25720"
echo ""
echo "📚 See .devcontainer/README.md for detailed documentation" 