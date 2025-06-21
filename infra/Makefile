# Gitea on Fly.io Makefile

.PHONY: help deploy status logs ssh db-connect backup clean setup-tigris

help: ## このヘルプを表示
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

setup: ## 初期セットアップを実行
	@echo "🔧 Giteaセットアップを開始..."
	@chmod +x deploy.sh
	@echo "✅ セットアップ完了"

deploy: ## Giteaをデプロイ
	@echo "🚀 Giteaをデプロイ中..."
	@./deploy.sh

status: ## アプリケーションの状態を確認
	@echo "📊 ステータス確認中..."
	@flyctl status --app my-gitea

logs: ## ログを表示
	@echo "📋 ログを表示中..."
	@flyctl logs --app my-gitea --follow

ssh: ## アプリケーションコンテナにSSH接続
	@echo "🔌 SSHで接続中..."
	@flyctl ssh console --app my-gitea

db-connect: ## PostgreSQLデータベースに接続
	@echo "🗃️ データベースに接続中..."
	@flyctl postgres connect --app my-gitea-db

backup: ## バックアップを作成
	@echo "💾 バックアップ作成中..."
	@flyctl postgres backup create --app my-gitea-db
	@flyctl volumes snapshots create gitea_data --app my-gitea
	@echo "✅ バックアップ完了"

clean: ## リソースを削除
	@echo "🧹 リソースを削除中..."
	@echo "⚠️  この操作は全データを削除します。続行しますか? [y/N]"
	@read ans && [ $${ans:-N} = y ]
	@flyctl apps destroy my-gitea --yes
	@flyctl postgres destroy my-gitea-db --yes
	@echo "✅ 削除完了"

setup-tigris: ## Tigirisバケットを作成
	@echo "📦 Tigirisバケットを作成中..."
	@echo "以下のバケットを手動で作成してください:"
	@echo "  - gitea-storage"
	@echo "  - gitea-lfs"
	@echo "  - gitea-avatars"
	@echo "  - gitea-attachments"
	@echo "  - gitea-repo-avatars"
	@echo "Tigirisコンソール: https://console.tigris.dev/"

secrets: ## 環境変数とシークレットを設定
	@echo "🔐 シークレットを設定中..."
	@flyctl secrets set \
		GITEA__security__SECRET_KEY="$$(openssl rand -base64 64)" \
		GITEA__security__INTERNAL_TOKEN="$$(openssl rand -base64 64)" \
		--app my-gitea
	@echo "⚠️  Tigirisのアクセス認証情報を手動で設定してください:"
	@echo "flyctl secrets set TIGRIS_ACCESS_KEY_ID='your_key' TIGRIS_SECRET_ACCESS_KEY='your_secret' --app my-gitea"

scale-up: ## リソースをスケールアップ
	@echo "📈 リソースをスケールアップ中..."
	@flyctl scale memory 2048 --app my-gitea
	@flyctl scale count 1 --app my-gitea

scale-down: ## リソースをスケールダウン
	@echo "📉 リソースをスケールダウン中..."
	@flyctl scale memory 1024 --app my-gitea
	@flyctl scale count 1 --app my-gitea

restart: ## アプリを再起動
	@echo "🔄 アプリを再起動中..."
	@flyctl machine restart --app my-gitea

update: ## Giteaを最新版に更新
	@echo "🔄 Giteaを更新中..."
	@flyctl deploy --app my-gitea --force

health: ## ヘルスチェック
	@echo "🏥 ヘルスチェック実行中..."
	@curl -s -o /dev/null -w "%{http_code}" https://my-gitea.fly.dev/api/healthz || echo "接続失敗"

config: ## 設定情報を表示
	@echo "⚙️  現在の設定:"
	@echo "アプリ名: my-gitea"
	@echo "データベース: my-gitea-db"
	@echo "リージョン: nrt (東京)"
	@echo "URL: https://my-gitea.fly.dev"
	@echo "SSH: my-gitea.fly.dev:22"

open: ## ブラウザでGiteaを開く
	@echo "🌐 ブラウザで開いています..."
	@open https://my-gitea.fly.dev

install-deps: ## 必要な依存関係をインストール
	@echo "📦 依存関係をインストール中..."
	@command -v flyctl >/dev/null 2>&1 || { echo "Fly.io CLIが必要です: https://fly.io/docs/hands-on/install-flyctl/"; exit 1; }
	@command -v openssl >/dev/null 2>&1 || { echo "OpenSSLが必要です"; exit 1; }
	@command -v jq >/dev/null 2>&1 || { echo "jqが必要です"; exit 1; }
	@echo "✅ 全ての依存関係がインストールされています" 