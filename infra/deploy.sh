#!/bin/bash

# Giteaデプロイメントスクリプト for Fly.io with Tigris

set -e

echo "🚀 Giteaのデプロイを開始します..."

# 1. Fly.ioにログイン確認
if ! flyctl auth whoami > /dev/null 2>&1; then
    echo "❌ Fly.ioにログインしてください: flyctl auth login"
    exit 1
fi

# 2. アプリを作成（既に存在する場合はスキップ）
echo "📱 Fly.ioアプリを作成中..."
flyctl apps create my-gitea --machines || echo "アプリは既に存在します"

# 3. PostgreSQLデータベースを作成
echo "🗃️ PostgreSQLデータベースを作成中..."
flyctl postgres create --name my-gitea-db --region nrt || echo "データベースは既に存在します"

# 4. データベースをアプリにアタッチ
echo "🔗 データベースをアプリにアタッチ中..."
flyctl postgres attach my-gitea-db --app my-gitea

# 5. Tigiris オブジェクトストレージを設定
echo "📦 Tigirisオブジェクトストレージを設定中..."
flyctl storage create --name gitea-storage --app my-gitea
flyctl storage create --name gitea-lfs --app my-gitea
flyctl storage create --name gitea-avatars --app my-gitea
flyctl storage create --name gitea-attachments --app my-gitea
flyctl storage create --name gitea-repo-avatars --app my-gitea

# 6. ボリュームを作成
echo "💾 データボリュームを作成中..."
flyctl volumes create gitea_data --region nrt --size 3 --app my-gitea

# 7. 環境変数を設定
echo "🔧 環境変数を設定中..."
flyctl secrets set \
  GITEA__database__PASSWD="$(flyctl postgres list --json | jq -r '.[] | select(.name == "my-gitea-db") | .password')" \
  GITEA__security__SECRET_KEY="$(openssl rand -base64 64)" \
  GITEA__security__INTERNAL_TOKEN="$(openssl rand -base64 64)" \
  --app my-gitea

# 8. Tigirisストレージ認証情報を確認
echo "🔐 Tigirisストレージ認証情報を確認中..."
flyctl storage info --app my-gitea

# 9. デプロイ実行
echo "🚀 デプロイを実行中..."
flyctl deploy --app my-gitea

# 10. デプロイ状況確認
echo "✅ デプロイ完了！"
echo "🌍 アクセスURL: https://my-gitea.fly.dev"
echo "📊 ステータス確認: flyctl status --app my-gitea"

# 11. 初期設定の案内
echo ""
echo "📋 初期設定手順:"
echo "1. https://my-gitea.fly.dev にアクセス"
echo "2. 管理者アカウントを作成"
echo "3. Tigirisオブジェクトストレージは自動設定済み"
echo "   - 認証情報は環境変数で自動注入"
echo "   - 必要に応じて 'flyctl storage info --app my-gitea' で確認" 