#!/bin/bash

# Supabase LFS Policies Application Script
# This script applies the necessary policies for Git LFS integration

set -e

SUPABASE_URL="${SUPABASE_URL:-https://pxsuqemlayhnmcxuiigk.supabase.co}"
POSTGRES_URL="${POSTGRES_URL}"

echo "🚀 Applying Supabase Storage policies for Git LFS..."

# Check if required environment variables are set
if [[ -z "$POSTGRES_URL" ]]; then
    echo "❌ Error: POSTGRES_URL environment variable is required"
    echo "   Please set: export POSTGRES_URL='your-postgres-url'"
    exit 1
fi

if [[ -z "$SUPABASE_SERVICE_ROLE_KEY" ]]; then
    echo "⚠️  Warning: SUPABASE_SERVICE_ROLE_KEY not set"
    echo "   This may be required for some operations"
fi

echo "📊 Target Supabase project: ${SUPABASE_URL}"
echo "🗄️  Applying policies from: scripts/supabase-lfs-policies.sql"

# Apply the SQL policies
if command -v psql &> /dev/null; then
    echo "📦 Applying policies using psql..."
    psql "${POSTGRES_URL}" -f scripts/supabase-lfs-policies.sql
    
    if [ $? -eq 0 ]; then
        echo "✅ Policies applied successfully!"
    else
        echo "❌ Failed to apply policies"
        exit 1
    fi
else
    echo "❌ psql not found. Please install PostgreSQL client or use Supabase Dashboard"
    echo ""
    echo "📋 Manual steps:"
    echo "1. Open Supabase Dashboard: ${SUPABASE_URL}/dashboard"
    echo "2. Go to SQL Editor"
    echo "3. Copy and execute the contents of scripts/supabase-lfs-policies.sql"
    echo ""
    echo "🔗 Direct link to SQL Editor:"
    echo "   https://supabase.com/dashboard/project/pxsuqemlayhnmcxuiigk/sql/new"
    exit 1
fi

echo ""
echo "🎉 Supabase LFS policies setup complete!"
echo ""
echo "📝 Next steps:"
echo "1. Verify bucket creation in Storage section"
echo "2. Test LFS operations: git push origin main"
echo "3. Check storage usage in Dashboard"
echo ""
echo "🔗 Useful links:"
echo "   Storage Dashboard: https://supabase.com/dashboard/project/pxsuqemlayhnmcxuiigk/storage/buckets"
echo "   Policies: https://supabase.com/dashboard/project/pxsuqemlayhnmcxuiigk/auth/policies" 