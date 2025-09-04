# Temporal Server & Web UI Setup

このディレクトリには、Temporal ワークフローエンジンのローカル開発環境が含まれています。

## 構成要素

- **Temporal Server**: ワークフロー実行エンジン (ポート 7233, 7234)
- **Temporal Web UI**: 管理・監視用の Web インターフェース (ポート 8080)
- **PostgreSQL**: 永続化データベース (ポート 5432)
- **Temporal Admin Tools**: CLI 管理ツール

## セットアップ手順

### 1. Docker がインストールされていることを確認

```bash
docker --version
docker-compose --version
```

### 2. Temporal サービスを起動

```bash
# 現在のディレクトリに移動
cd /Users/junkawasaki/jun784/root/procs/250904-temporal

# サービスを起動（バックグラウンドで実行）
docker-compose up -d

# ログを確認する場合
docker-compose logs -f
```

### 3. サービスの確認

起動後、以下の URL でアクセスできます：

- **Temporal Web UI**: http://localhost:8080
- **Temporal Server gRPC**: localhost:7233
- **Temporal Server HTTP**: localhost:7234

### 4. Admin Tools の使用

Temporal Admin Tools を使用して、名前空間の管理などを行うことができます：

```bash
# Admin Tools コンテナに入る
docker-compose exec temporal-admin-tools bash

# 名前空間の一覧表示
temporal operator namespace list

# 新しい名前空間を作成
temporal operator namespace create test-namespace
```

## 一般的なコマンド

```bash
# サービスを停止
docker-compose down

# サービスを停止してデータを削除
docker-compose down -v

# サービスを再構築
docker-compose up --build -d

# ログを表示
docker-compose logs temporal
docker-compose logs temporal-web
docker-compose logs postgres
```

## トラブルシューティング

### ポートが使用中の場合

他のサービスがポートを使用している場合は、docker-compose.yml のポートマッピングを変更してください。

### データベース接続エラー

PostgreSQL が起動していない場合、Temporal Server が起動に失敗することがあります。以下のコマンドで確認してください：

```bash
docker-compose ps
docker-compose logs postgres
```

### メモリ不足

Docker Desktop のメモリ割り当てを増やすことを検討してください（最低 4GB 推奨）。

## バージョン情報

- Temporal Server: 1.25.0
- Temporal Web UI: 2.28.0
- PostgreSQL: 13

## 追加情報

- [Temporal 公式ドキュメント](https://docs.temporal.io/)
- [Temporal Web UI ガイド](https://docs.temporal.io/web-ui)
- [Temporal CLI リファレンス](https://docs.temporal.io/cli)
