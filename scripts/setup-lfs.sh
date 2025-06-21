#!/bin/bash

# Supabase LFS Setup Script
# This script helps configure Git LFS with Supabase Storage

set -e

echo "🚀 Setting up Git LFS with Supabase Storage..."

# Check if git lfs is installed
if ! command -v git-lfs &> /dev/null; then
    echo "❌ Git LFS is not installed. Please install it first:"
    echo "   - macOS: brew install git-lfs"
    echo "   - Ubuntu/Debian: sudo apt install git-lfs"
    echo "   - Other: https://git-lfs.github.io/"
    exit 1
fi

# Check environment variables
if [[ -z "$SUPABASE_SERVICE_ROLE_KEY" ]]; then
    echo "⚠️  Warning: SUPABASE_SERVICE_ROLE_KEY not set"
    echo "   This is required for LFS authentication"
fi

if [[ -z "$SUPABASE_URL" ]]; then
    echo "⚠️  Warning: SUPABASE_URL not set"
    echo "   This should be: https://pxsuqemlayhnmcxuiigk.supabase.co"
fi

# Initialize Git LFS
echo "📦 Initializing Git LFS..."
git lfs install

# Check if .lfsconfig exists
if [[ ! -f ".lfsconfig" ]]; then
    echo "❌ .lfsconfig not found in project root"
    echo "   Please ensure .lfsconfig is properly configured"
    exit 1
fi

echo "✅ .lfsconfig found"

# Display current LFS configuration
echo "📋 Current LFS configuration:"
git lfs env

# Check LFS tracking patterns
echo "📋 Current LFS tracking patterns:"
git lfs track

echo "🎉 Git LFS setup complete!"
echo ""
echo "📝 Next steps:"
echo "1. Create 'lfs-storage' bucket in Supabase Dashboard"
echo "2. Set required environment variables:"
echo "   export SUPABASE_SERVICE_ROLE_KEY='your-service-role-key'"
echo "   export SUPABASE_URL='https://pxsuqemlayhnmcxuiigk.supabase.co'"
echo "3. Track large files: git lfs track '*.large-extension'"
echo "4. Add and commit files normally"
echo ""
echo "📚 For troubleshooting, check: https://git-lfs.github.io/" 