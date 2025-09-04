# Video Emotion Analysis Workflow

このプロジェクトは、Temporal.ioを使用してHumeAIでWebM動画ファイルの感情分析を行うワークフローシステムです。

## アーキテクチャ

- **Temporal Workflow**: 分散ワークフロー管理
- **HumeAI API**: 動画の感情分析
- **Python**: メインの実装言語

## プロジェクト構造

```
src/
├── activities/          # Temporal Activities
│   ├── humeai_analyzer.py   # HumeAI API統合
│   └── result_storage.py    # 分析結果保存
├── workflows/           # Temporal Workflows
│   └── video_emotion_analysis.py
├── shared/              # 共有モデルとユーティリティ
│   └── models.py
├── worker.py            # Temporal Worker
└── client.py            # ワークフロー実行クライアント

data/                    # セッションデータ
├── {session_id}/
│   ├── *.webm          # 分析対象動画
│   └── session_data.json
```

## セットアップ

### 1. 依存関係のインストール

```bash
pip install -r requirements.txt
```

### 2. 環境設定

1. `config.example.yaml` を `config.yaml` にコピー
2. HumeAI APIキーを設定:

```yaml
humeai:
  api_key: "your_actual_hume_api_key"
```

### 3. Temporal Serverの起動

```bash
# Dockerを使用する場合
docker run -p 7233:7233 temporalio/auto-setup:latest

# またはローカルインストール
temporal server start-dev
```

### 4. 必要なディレクトリの作成

```bash
mkdir -p logs
```

## 使用方法

### Workerの起動

```bash
python src/worker.py
```

### ワークフローの実行

#### 単一動画ファイルの分析

```bash
python src/client.py file {session_id} {video_filename}
```

例:
```bash
python src/client.py file 144b325f-5966-4d59-a629-f2ca421388cc session-1-video.webm
```

#### 単一セッション内の全動画分析

```bash
python src/client.py session {session_id}
```

例:
```bash
python src/client.py session 144b325f-5966-4d59-a629-f2ca421388cc
```

#### 全動画ファイルの一括分析（順次実行）

```bash
python src/client.py batch-files
```

#### 指定セッションの一括分析

```bash
python src/client.py batch-sessions {session_id1} {session_id2} ...
```

例:
```bash
python src/client.py batch-sessions 144b325f-5966-4d59-a629-f2ca421388cc 15592cdb-86cf-4baf-86f5-66184169ee39
```

#### 全セッションの一括分析

```bash
python src/client.py batch-all
```

#### クイックスタート（全動画の一括分析）

```bash
python start_analysis.py
```

## 分析結果

分析結果は以下の場所に保存されます:

```
data/{session_id}/analysis_results/
├── {video_filename}_emotions_{timestamp}.json    # 個別動画の結果
└── batch_analysis_summary_{timestamp}.json      # 一括分析サマリー
```

### 結果形式

個別動画の結果:
```json
{
  "session_id": "144b325f-5966-4d59-a629-f2ca421388cc",
  "video_filename": "session-1-video.webm",
  "job_id": "hum_abc123",
  "analyzed_at": "2024-01-15T10:30:00",
  "emotions": [
    {
      "emotion": "joy",
      "score": 0.85,
      "confidence": 0.92
    }
  ],
  "metadata": { ... }
}
```

一括分析サマリー:
```json
{
  "session_id": "144b325f-5966-4d59-a629-f2ca421388cc",
  "analysis_summary": {
    "total_videos": 2,
    "completed_at": "2024-01-15T10:35:00",
    "videos": [
      {
        "video_filename": "session-1-video.webm",
        "job_id": "hum_abc123",
        "emotion_count": 15,
        "analyzed_at": "2024-01-15T10:30:00",
        "top_emotions": [ ... ]
      }
    ]
  }
}
```

## ワークフロー機能

### VideoEmotionAnalysisWorkflow

- **単一動画ファイル**の感情分析
- HumeAI APIを使用して感情データを抽出
- 分析結果をJSONファイルとして保存
- セッションメタデータを更新
- 自動リトライとエラーハンドリング

### BatchVideoAnalysisWorkflow

- **複数動画ファイルの順次分析**
- 各動画ファイルを個別の子ワークフローとして順次実行
- 処理の安定性とリソース管理を重視
- 各ファイルの分析結果を個別に追跡

### 実行パターン

1. **単一ファイル**: 1つの動画ファイルのみ分析
2. **セッション単位**: 1つのセッション内の全ファイルを順次分析
3. **バッチファイル**: 全動画ファイルを順次分析
4. **バッチセッション**: 指定セッション群の全ファイルを順次分析

## エラーハンドリング

- API呼び出しの失敗時は自動リトライ（最大3回）
- 一部の動画分析失敗時も全体のワークフローは継続
- 詳細なログ記録（logs/ディレクトリ）

## モニタリング

WorkerはPrometheusメトリクスを`localhost:9090`で公開しています。

## 拡張性

- 新しい感情分析モデルへの対応
- 追加のメタデータ抽出
- 結果のデータベース保存
- Webhook通知の追加
