// LLM-BOUNDARY: 60_projection - selectors/ViewModel（foldの薄ラッパ）

import { KawasakiStoreState, WordResponse, TestResult } from '@/00_schema';
import { JungTestContext } from '@/40_domain';
import { foldMerkleDAG, MerkleDAG } from '@/30_fold';

// JungテストのViewModel
export interface JungTestViewModel {
  // テスト状態
  testStatus: KawasakiStoreState['testStatus'];
  deviceStatus: KawasakiStoreState['deviceStatus'];
  mediaStatus: KawasakiStoreState['mediaStatus'];

  // 現在のセッション情報
  currentSession: 1 | 2;
  currentWordIndex: number;
  currentWord: string | null;

  // 単語リスト
  stimulusWords: string[];
  totalWords: number;
  remainingWords: number;

  // 応答履歴
  wordResponses: WordResponse[];
  totalResponses: number;

  // エラー情報
  error: string | null;

  // 完了情報
  isCompleted: boolean;
  testResult: TestResult | null;

  // UIコントロール
  canStartPreflight: boolean;
  canStartSession: boolean;
  canRecordResponse: boolean;
  canAdvance: boolean;
  canReset: boolean;
}

// 感情分析のViewModel
export interface EmotionAnalysisViewModel {
  participantId: string | null;
  isAnalyzing: boolean;
  currentAnalysis: any;
  analysisResults: any[];
  error: string | null;
  canAnalyze: boolean;
  canLoadResults: boolean;
  statistics: any;
}

// 管理画面のViewModel
export interface AdminAnalyticsViewModel {
  totalParticipants: number;
  totalSessions: number;
  totalAnalyses: number;
  emotionStatistics: any;
  recentActivity: any[];
  systemHealth: 'healthy' | 'warning' | 'error';
}
