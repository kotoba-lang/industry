import { Inngest } from 'inngest';

// Inngestクライアントの初期化（ローカル開発用）
export const inngest = new Inngest({
  id: 'spirit-in-physics',
  name: 'Spirit-in-Physics Analysis Pipeline',
  concurrency: 5, // 同時実行数
  retries: 3, // リトライ回数
  // ローカル開発環境の設定
  baseUrl: process.env.NODE_ENV === 'development' ? 'http://localhost:25250' : undefined,
});

// イベントタイプの定義
export const events = {
  // 動画分析イベント
  VIDEO_ANALYSIS_REQUESTED: 'video.analysis.requested',
  VIDEO_ANALYSIS_STARTED: 'video.analysis.started',
  VIDEO_ANALYSIS_COMPLETED: 'video.analysis.completed',
  VIDEO_ANALYSIS_FAILED: 'video.analysis.failed',

  // バッチ分析イベント
  BATCH_ANALYSIS_REQUESTED: 'batch.analysis.requested',
  BATCH_ANALYSIS_STARTED: 'batch.analysis.started',
  BATCH_ANALYSIS_COMPLETED: 'batch.analysis.completed',
  BATCH_ANALYSIS_FAILED: 'batch.analysis.failed',

  // 結果処理イベント
  RESULTS_PROCESSING_STARTED: 'results.processing.started',
  RESULTS_PROCESSING_COMPLETED: 'results.processing.completed',
  RESULTS_PROCESSING_FAILED: 'results.processing.failed',

  // 通知イベント
  NOTIFICATION_SENT: 'notification.sent',
} as const;

// イベントデータの型定義
export interface VideoAnalysisEvent {
  participantId: string;
  videoFile: string;
  sessionType: string;
  priority?: 'low' | 'normal' | 'high';
  retryCount?: number;
}

export interface BatchAnalysisEvent {
  participantIds: string[];
  priority?: 'low' | 'normal' | 'high';
  batchId: string;
}

export interface AnalysisResultEvent {
  participantId: string;
  videoFile: string;
  results: any;
  processingTime: number;
  metadata: Record<string, any>;
}

// ワークフロー関数の型定義
export type VideoAnalysisWorkflow = (event: VideoAnalysisEvent) => Promise<void>;
export type BatchAnalysisWorkflow = (event: BatchAnalysisEvent) => Promise<void>;
