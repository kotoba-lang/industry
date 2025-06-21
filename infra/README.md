# Gitea on Fly.io with Tigris Object Storage

Fly.ioプラットフォーム上でTigirisオブジェクトストレージを利用した自分専用Giteaインスタンスのデプロイメント設定です。

## 概要

- **プラットフォーム**: Fly.io
- **アプリケーション**: Gitea (セルフホスト型Git サービス)
- **データベース**: PostgreSQL (Fly.io Postgres)
- **オブジェクトストレージ**: Tigris
- **リージョン**: 東京 (nrt)

## 機能

- **Git リポジトリ管理**: フル機能のGitサーバー
- **Web UI**: モダンなWebインターフェース
- **SSH アクセス**: Git SSH操作サポート
- **LFS サポート**: Git Large File Storage
- **Issues & PRs**: 課題追跡とプルリクエスト機能
- **オブジェクトストレージ**: アバター、添付ファイル、LFSファイルをTigirisに保存

## ファイル構成

```
infra/
├── fly.toml          # Fly.io設定
├── Dockerfile        # Giteaコンテナ設定
├── app.ini          # Gitea詳細設定
├── deploy.sh        # デプロイスクリプト
└── README.md        # このファイル
```

## 前提条件

1. **Fly.io CLI**: インストール済み
   ```bash
   curl -L https://fly.io/install.sh | sh
   ```

2. **Fly.io アカウント**: ログイン済み
   ```bash
   flyctl auth login
   ```

3. **Tigris アカウント**: オブジェクトストレージ用

## デプロイメント手順

### 1. クイックデプロイ

```bash
chmod +x deploy.sh
./deploy.sh
```

### 2. 手動デプロイ

#### ステップ1: アプリ作成
```bash
flyctl apps create my-gitea --machines
```

#### ステップ2: データベース作成・アタッチ
```bash
flyctl postgres create --name my-gitea-db --region nrt
flyctl postgres attach my-gitea-db --app my-gitea
```

#### ステップ3: Tigirisストレージ設定
```bash
flyctl storage create --name gitea-storage --app my-gitea
```

#### ステップ4: ボリューム作成
```bash
flyctl volumes create gitea_data --region nrt --size 3 --app my-gitea
```

#### ステップ5: シークレット設定
```bash
flyctl secrets set \
  GITEA__database__PASSWD="YOUR_DB_PASSWORD" \
  GITEA__security__SECRET_KEY="$(openssl rand -base64 64)" \
  GITEA__security__INTERNAL_TOKEN="$(openssl rand -base64 64)" \
  TIGRIS_ACCESS_KEY_ID="YOUR_TIGRIS_ACCESS_KEY" \
  TIGRIS_SECRET_ACCESS_KEY="YOUR_TIGRIS_SECRET_KEY" \
  --app my-gitea
```

#### ステップ6: デプロイ実行
```bash
flyctl deploy --app my-gitea
```

## Tigirisオブジェクトストレージ設定

### 1. Tigirisバケット作成

Tigirisコンソールで以下のバケットを作成:
- `gitea-storage` (メインストレージ)
- `gitea-lfs` (Git LFS)
- `gitea-avatars` (アバター画像)
- `gitea-attachments` (添付ファイル)
- `gitea-repo-avatars` (リポジトリアバター)

### 2. アクセス認証情報

Tigirisコンソールでアクセスキーペアを生成し、環境変数で設定:

```bash
flyctl secrets set \
  TIGRIS_ACCESS_KEY_ID="your_access_key" \
  TIGRIS_SECRET_ACCESS_KEY="your_secret_key" \
  --app my-gitea
```

## 初期設定

1. **Webアクセス**: https://my-gitea.fly.dev
2. **管理者アカウント作成**: 初回アクセス時に設定
3. **SSH設定**: 
   ```bash
   ssh-keygen -t rsa -b 4096 -C "your_email@example.com"
   # 公開鍵をGitea UIで登録
   ```

## 運用管理

### ログ確認
```bash
flyctl logs --app my-gitea
```

### ステータス確認
```bash
flyctl status --app my-gitea
```

### スケール調整
```bash
flyctl scale count 1 --app my-gitea
flyctl scale memory 1024 --app my-gitea
```

### バックアップ
```bash
# データベースバックアップ
flyctl postgres backup create --app my-gitea-db

# ボリュームスナップショット
flyctl volumes snapshots create gitea_data --app my-gitea
```

## セキュリティ考慮事項

- [ ] 2FA認証の有効化
- [ ] SSH鍵による認証の設定
- [ ] HTTPSの強制 (Fly.ioで自動設定)
- [ ] データベース接続のTLS暗号化
- [ ] オブジェクトストレージのアクセス制御

## トラブルシューティング

### アプリが起動しない場合
```bash
flyctl logs --app my-gitea
```

### データベース接続エラー
```bash
flyctl postgres connect --app my-gitea-db
```

### Tigirisアクセスエラー
- アクセスキーの確認
- バケット権限の確認
- エンドポイントURLの確認

## コスト見積もり

- **アプリインスタンス**: ~$5-10/月
- **PostgreSQL**: ~$2-5/月
- **ボリューム (3GB)**: ~$0.30/月
- **Tigirisストレージ**: 従量課金

合計: 約 $7-15/月 (使用量による)

## ライセンス

MIT License

## サポート

- [Fly.io ドキュメント](https://fly.io/docs/)
- [Gitea ドキュメント](https://docs.gitea.io/)
- [Tigris ドキュメント](https://docs.tigris.dev/) 