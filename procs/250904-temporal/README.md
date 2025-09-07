# Temporal Server & Web UI Setup

このディレクトリには、Temporal ワークフローエンジンのローカル開発環境が含まれています。

## 現在のセットアップ状況

### 実行中のサービス
- **Temporal Server**: ポート 7233 (gRPC), 7243 (HTTP) で実行中
- **SQLite データベース**: ファイルベースで永続化

### セットアップ方法
ローカルバイナリを使用してセットアップ済みです。

## 起動方法

### 1. Temporal Server の起動

```bash
# 環境変数を使用して設定ディレクトリを指定
TEMPORAL_CONFIG_DIR=./config ./temporal-server start
```

### 2. Temporal Web UI の起動

```bash
# npx を使用して Web UI を起動
npx temporalio/web --port 8080 --temporal-address localhost:7233
```

### 3. Temporal CLI の使用

```bash
# ワークフロー一覧表示
./temporal workflow list

# 名前空間作成
./temporal operator namespace create default
```

## アクセス URL

- **Temporal Web UI**: http://localhost:8080 (準備中)
- **Temporal Server gRPC**: localhost:7233
- **Temporal Server HTTP**: localhost:7243

## プロセス管理

### 実行中のプロセス確認
```bash
ps aux | grep temporal
```

### プロセス停止
```bash
# Temporal Server 停止
pkill -f temporal-server

# Web UI 停止
pkill -f "temporalio/web"
```

## 構成要素

- **Temporal Server**: ワークフロー実行エンジン
- **Temporal CLI**: コマンドライン管理ツール
- **Temporal Web UI**: 管理・監視用の Web インターフェース
- **SQLite**: 永続化データベース

## トラブルシューティング

### Web UI が起動しない場合
```bash
# 別の方法で Web UI を起動
npx @temporalio/web@latest --port 8080 --temporal-address localhost:7233
```

### ポートが使用中の場合
docker-compose.yml または npx コマンドのポート番号を変更してください。

### データベースの問題
SQLite ファイルが破損した場合、config ディレクトリ内のデータベースファイルを削除して再起動してください。

## バージョン情報

- Temporal Server: 1.28.1
- Temporal CLI: 1.4.1
- SQLite: 組み込み

## 追加情報

- [Temporal 公式ドキュメント](https://docs.temporal.io/)
- [Temporal CLI リファレンス](https://docs.temporal.io/cli)
