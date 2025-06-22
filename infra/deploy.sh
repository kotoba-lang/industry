#!/bin/bash

# Giteaデプロイメントスクリプト for Fly.io with Tigris

set -e

APP_NAME="git-emergent-gftd-co-jp"
DB_NAME="${APP_NAME}-db"
REGION="nrt"
VOLUME_NAME="gitea_data"
STORAGE_NAME="${APP_NAME}-storage"

echo "🚀 Giteaのデプロイを開始します: ${APP_NAME}"

# 1. Fly.ioにログイン確認
if ! flyctl auth whoami > /dev/null 2>&1; then
    echo "❌ Fly.ioにログインしてください: flyctl auth login"
    exit 1
fi

# 2. アプリを作成（既に存在する場合はスキップ）
echo "📱 Fly.ioアプリを作成中..."
flyctl apps create "$APP_NAME" --machines || echo "アプリは既に存在します"

# 3. PostgreSQLデータベースを作成
echo "🗃️ PostgreSQLデータベースを作成中..."
flyctl postgres create --name "$DB_NAME" --region "$REGION" --vm-size "shared-cpu-1x" --volume-size 1 || echo "データベースは既に存在します"

# 4. データベースをアプリにアタッチ
echo "🔗 データベースをアプリにアタッチ中..."
flyctl postgres attach "$DB_NAME" --app "$APP_NAME"

# 5. Tigiris オブジェクトストレージを設定
echo "📦 Tigrisオブジェクトストレージを設定中..."
flyctl storage create --name "$STORAGE_NAME" --app "$APP_NAME" || echo "ストレージは既に存在します"

# 6. ボリュームを作成
echo "💾 データボリュームを作成中..."
flyctl volumes create "$VOLUME_NAME" --region "$REGION" --size 3 --app "$APP_NAME" || echo "ボリュームは既に存在します"

# 7. 環境変数を設定
echo "🔧 環境変数を設定中..."
flyctl secrets set \
  "GITEA__database__PASSWD=$(flyctl postgres list --json | jq -r ".[] | select(.name == \"$DB_NAME\") | .password")" \
  "GITEA__security__SECRET_KEY=$(openssl rand -hex 32)" \
  "GITEA__security__INTERNAL_TOKEN=$(openssl rand -hex 32)" \
  "GITEA__security__ADMIN_PASSWORD=$(openssl rand -base64 24)" \
  --app "$APP_NAME"

# 8. デプロイ実行
echo "🚀 デプロイを実行中..."
flyctl deploy --app "$APP_NAME"

# 9. デプロイ完了
echo "✅ デプロイ完了！"
echo "🌍 アクセスURL: https://${APP_NAME}.fly.dev"
echo "🔑 管理者ユーザー: admin"
echo "🔐 管理者パスワードは flyctl secrets get GITEA__security__ADMIN_PASSWORD --app ${APP_NAME} で確認できます"
echo "📊 ステータス確認: flyctl status --app ${APP_NAME}" 