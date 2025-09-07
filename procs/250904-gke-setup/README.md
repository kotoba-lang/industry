# GKE ベースのシステム設計とセットアップ

このドキュメントは、0.md で記述された Temporal + Dgraph + LLM 統合システムを Google Kubernetes Engine (GKE) 上で設計・セットアップするためのガイドです。MVP 実装を念頭に、コンテナ化とスケーラブルなデプロイを重視します。

## 1. アーキテクチャ概要

- **クラスタ構造**: GKE Autopilot モードを使用（自動スケーリング）。Namespace: `temporal-ns` (Temporal 用), `dgraph-ns` (Dgraph 用), `llm-ns` (LLM Activities 用)。
- **コンポーネント**:
  - **Temporal**: Server を Deployment でデプロイ。Workflows/Activities を Pod で実行。Persistence に Cloud SQL (PostgreSQL) を使用。
  - **Dgraph**: Alpha/Zero/Ratel を StatefulSet でデプロイ。ストレージに PersistentVolume (GCE PD)。
  - **LLM**: Activities (IE/要約/介入生成) を Deployment で。Vertex AI または自前モデル (例: Hugging Face) を統合。
  - **その他**: Ingress で外部アクセス、Secret Manager で API キー管理。
- **ネットワーキング**: Internal Load Balancer でコンポーネント間通信。
- **モニタリング**: GCP Monitoring + Prometheus で KPI 追跡。
- **スケーリング**: Horizontal Pod Autoscaler (HPA) で Activities を自動スケール。

全体像:
- データ取り込み → Temporal Workflow → Dgraph Upsert → LLM 処理 → 介入出力。

## 2. 前提条件

- GCP アカウントとプロジェクト設定済み。
- gcloud CLI インストール (`brew install --cask google-cloud-sdk`)。
- kubectl インストール。
- Helm インストール (`brew install helm`)。
- Temporal CLI (`brew install temporal`)。
- Docker/Podman でローカルビルド可能。
- ワークスペースの Podman/Tilt でローカルテスト推奨。

## 3. セットアップ手順

### ステップ1: GKE クラスタ作成
gcloud container clusters create-auto temporal-cluster --region=asia-northeast1

### ステップ2: Namespace 作成
kubectl create namespace temporal-ns
kubectl create namespace dgraph-ns
kubectl create namespace llm-ns

### ステップ3: Temporal のデプロイ
Helm で Temporal をインストール（公式チャート使用）。
helm repo add temporal https://temporalio.github.io/helm-charts
helm install temporal temporal/temporal --namespace temporal-ns --set server.replicaCount=3 --set cassandra.enabled=false --set postgresql.enabled=true

(注: PostgreSQL を Cloud SQL に置き換え。)

### ステップ4: Dgraph のデプロイ
Helm で Dgraph をインストール。
helm repo add dgraph https://charts.dgraph.io
helm install dgraph dgraph/dgraph --namespace dgraph-ns --set alpha.replicas=3 --set zero.replicas=3

スキーマ適用: kubectl exec -n dgraph-ns dgraph-alpha-0 -- curl -X POST localhost:8080/admin/schema --data-binary '@schema.dgraph'

(0.md のスキーマを schema.dgraph に保存。)

### ステップ5: LLM Activities のデプロイ
カスタム Docker イメージビルド (Python/TS で Activities 実装)。
例: Dockerfile で OpenAI SDK インストール。
kubectl apply -f llm-deployment.yaml -n llm-ns

### ステップ6: Workflow 登録とテスト
temporal workflow start --workflow-id test-wf --type KPIComputeWorkflow --input '{"tau": "1h"}'

### ステップ7: モニタリング設定
gcloud container clusters update temporal-cluster --monitoring=SYSTEM,WORKLOAD

## 4. Kubernetes マニフェスト例

### Temporal Deployment (抜粋)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: temporal-frontend
  namespace: temporal-ns
spec:
  replicas: 3
  template:
    spec:
      containers:
      - name: frontend
        image: temporalio/server:latest

### Dgraph Alpha StatefulSet (抜粋)
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: dgraph-alpha
  namespace: dgraph-ns
spec:
  replicas: 3
  template:
    spec:
      containers:
      - name: alpha
        image: dgraph/dgraph:latest
        volumeMounts:
        - name: data
          mountPath: /dgraph

### LLM Activity Deployment
apiVersion: apps/v1
kind: Deployment
metadata:
  name: llm-activity
  namespace: llm-ns
spec:
  replicas: 2
  template:
    spec:
      containers:
      - name: llm
        image: your-repo/llm-activity:latest  # カスタムイメージ
        env:
        - name: OPENAI_API_KEY
          valueFrom:
            secretKeyRef:
              name: openai-secret
              key: api-key

## 5. 追加のスクリプト

### schema.dgraph (0.md から抽出)
type Agent { ... }  # 0.md のスキーマをここにコピー

## 6. トラブルシューティング
- Pod が起動しない: kubectl logs で確認。
- スケーリング: HPA を適用 (例: kubectl autoscale deployment llm-activity --cpu-percent=50 --min=1 --max=10)。

このセットアップで MVP を 2-4週で稼働可能。詳細なカスタムコードが必要なら、0.md の具体的な部分を指定してください。
