#!/bin/bash

# Giteaデプロイメントスクリプト for Fly.io with Tigris

set -e

APP_NAME="git-emergent-gftd-co-jp"
DB_NAME="${APP_NAME}-db"
REGION="nrt"
VOLUME_NAME="gitea_data"
STORAGE_NAME="${APP_NAME}-storage"
ORG_SLUG="gftdcojp"

echo "🚀 Giteaのデプロイを開始します: ${APP_NAME}"

# 1. Fly.ioにログイン確認
if ! flyctl auth whoami > /dev/null 2>&1; then
    echo "❌ Fly.ioにログインしてください: flyctl auth login"
    exit 1
fi

# 2. アプリを作成（既に存在する場合はスキップ）
echo "📱 Fly.ioアプリを作成中..."
flyctl apps create "$APP_NAME" --machines --org "$ORG_SLUG" || echo "アプリは既に存在します"

# 3. PostgreSQLデータベースを作成
echo "🗃️ PostgreSQLデータベースを作成中..."
flyctl postgres create --name "$DB_NAME" --region "$REGION" --vm-size "shared-cpu-1x" --volume-size 1 --org "$ORG_SLUG" || echo "データベースは既に存在します"

# 4. データベースをアプリにアタッチ
echo "🔗 データベースをアプリにアタッチ中..."
flyctl postgres attach "$DB_NAME" -a "$APP_NAME"

# 5. Tigris オブジェクトストレージを設定
echo "📦 Tigrisオブジェクトストレージを設定中..."
flyctl tigris create "$STORAGE_NAME" --org "$ORG_SLUG" || echo "ストレージは既に存在します"

# 6. ボリュームを作成
echo "💾 データボリュームを作成中..."
flyctl volumes create "$VOLUME_NAME" --region "$REGION" --size 3 -a "$APP_NAME" || echo "ボリュームは既に存在します"

# 7. 環境変数を設定
echo "🔧 環境変数を設定中..."
STORAGE_INFO_JSON=$(flyctl tigris info "$STORAGE_NAME" --json)

flyctl secrets set -a "$APP_NAME" \
  "GITEA__database__PASSWD=$(flyctl postgres list --json | jq -r ".[] | select(.name == \"$DB_NAME\") | .password")" \
  "GITEA__security__SECRET_KEY=$(openssl rand -hex 32)" \
  "GITEA__security__INTERNAL_TOKEN=$(openssl rand -hex 32)" \
  "GITEA__security__ADMIN_PASSWORD=$(openssl rand -base64 24)" \
  "GITEA__storage__MINIO_ENDPOINT=$(echo $STORAGE_INFO_JSON | jq -r .url)" \
  "GITEA__storage__MINIO_ACCESS_KEY_ID=$(echo $STORAGE_INFO_JSON | jq -r .access_key_id)" \
  "GITEA__storage__MINIO_SECRET_ACCESS_KEY=$(echo $STORAGE_INFO_JSON | jq -r .secret_access_key)" \
  "GITEA__storage__MINIO_BUCKET=$(echo $STORAGE_INFO_JSON | jq -r .bucket)" \
  "GITEA__storage__MINIO_USE_SSL=true"

# 8. デプロイ実行
echo "🚀 デプロイを実行中..."
flyctl deploy -a "$APP_NAME"

# 9. デプロイ完了
echo "✅ デプロイ完了！"
echo "🌍 アクセスURL: https://${APP_NAME}.fly.dev"
echo "🔑 管理者ユーザー: admin"
echo "🔐 管理者パスワードは次のコマンドで確認できます:"
echo "flyctl ssh console -a ${APP_NAME} -C \"printenv | grep GITEA__security__ADMIN_PASSWORD\""
echo "📊 ステータス確認: flyctl status -a ${APP_NAME}" 