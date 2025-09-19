// LLM-BOUNDARY: 60_projection - selectors/ViewModel（foldの薄ラッパ）

import { JungTestViewModel, EmotionAnalysisViewModel, AdminAnalyticsViewModel } from './view-models';
import { JungTestContext } from '@/40_domain';
import { KawasakiStoreState, WordResponse } from '@/00_schema';
import { foldMerkleDAG, MerkleDAG } from '@/30_fold';

// Jungテストのセレクター
export function selectJungTestViewModel(
  context: JungTestContext,
  dag: MerkleDAG
): JungTestViewModel {
  const foldedState = foldMerkleDAG(dag);

  return {
    // テスト状態
    testStatus: context.testStatus,
    deviceStatus: context.deviceStatus,
    mediaStatus: context.mediaStatus,

    // 現在のセッション情報
    currentSession: context.currentSession,
    currentWordIndex: context.currentWordIndex,
    currentWord: context.stimulusWords[context.currentWordIndex]?.word || null,

    // 単語リスト
    stimulusWords: context.stimulusWords.map(w => w.word),
    totalWords: context.stimulusWords.length,
    remainingWords: Math.max(0, context.stimulusWords.length - context.currentWordIndex - 1),

    // 応答履歴
    wordResponses: context.wordResponses,
    totalResponses: context.wordResponses.length,

    // エラー情報
    error: context.error,

    // 完了情報
    isCompleted: context.testStatus === 'completed',
    testResult: context.testStatus === 'completed' ? computeTestResult(context) : null,

    // UIコントロール
    canStartPreflight: context.testStatus === 'idle',
    canStartSession: context.testStatus === 'preflight' && context.deviceStatus === 'success',
    canRecordResponse: ['session-1-running', 'session-2-running'].includes(context.testStatus),
    canAdvance: context.currentWordIndex >= 0 && context.currentWordIndex < context.stimulusWords.length,
    canReset: context.testStatus === 'completed'
  };
}

// 感情分析のセレクター
export function selectEmotionAnalysisViewModel(
  context: any, // EmotionAnalysisContext
  analysisResults: any[]
): EmotionAnalysisViewModel {
  return {
    participantId: context.participantId,
    isAnalyzing: context.isAnalyzing,
    currentAnalysis: context.currentAnalysis,
    analysisResults,
    error: context.error,
    canAnalyze: !!context.participantId && !context.isAnalyzing,
    canLoadResults: !!context.participantId,
    statistics: {} // 統計情報は別途計算
  };
}

// 管理画面のセレクター
export function selectAdminAnalyticsViewModel(
  participants: any[],
  sessions: any[],
  analyses: any[]
): AdminAnalyticsViewModel {
  return {
    totalParticipants: participants.length,
    totalSessions: sessions.length,
    totalAnalyses: analyses.length,
    emotionStatistics: {}, // 統計情報
    recentActivity: [], // 最近のアクティビティ
    systemHealth: 'healthy' // システム状態
  };
}

// ヘルパー関数
function computeTestResult(context: JungTestContext): any {
  const totalWords = context.stimulusWords.length;
  const totalResponses = context.wordResponses.length;
  const totalReactionTime = context.wordResponses.reduce((sum, r) => sum + r.reactionTimeMs, 0);
  const averageReactionTimeMs = totalResponses > 0 ? totalReactionTime / totalResponses : 0;

  return {
    totalWords,
    averageReactionTimeMs,
    responses: context.wordResponses,
    completedAt: new Date()
  };
}

// MerkleDAGから直接ViewModelを生成するセレクター
export function selectFromMerkleDAG(dag: MerkleDAG): JungTestViewModel {
  const foldedState = foldMerkleDAG(dag);

  return {
    testStatus: foldedState.testStatus,
    deviceStatus: foldedState.deviceStatus,
    mediaStatus: foldedState.mediaStatus,
    currentSession: foldedState.currentSession,
    currentWordIndex: foldedState.currentWordIndex,
    currentWord: foldedState.stimulusWords[foldedState.currentWordIndex]?.word || null,
    stimulusWords: foldedState.stimulusWords.map(w => w.word),
    totalWords: foldedState.stimulusWords.length,
    remainingWords: Math.max(0, foldedState.stimulusWords.length - foldedState.currentWordIndex - 1),
    wordResponses: foldedState.wordResponses,
    totalResponses: foldedState.wordResponses.length,
    error: foldedState.error,
    isCompleted: foldedState.testStatus === 'completed',
    testResult: foldedState.testStatus === 'completed' ? {
      totalWords: foldedState.stimulusWords.length,
      averageReactionTimeMs: foldedState.wordResponses.length > 0
        ? foldedState.wordResponses.reduce((sum, r) => sum + r.reactionTimeMs, 0) / foldedState.wordResponses.length
        : 0,
      responses: foldedState.wordResponses,
      completedAt: new Date()
    } : null,
    canStartPreflight: foldedState.testStatus === 'idle',
    canStartSession: foldedState.testStatus === 'preflight' && foldedState.deviceStatus === 'success',
    canRecordResponse: ['session-1-running', 'session-2-running'].includes(foldedState.testStatus),
    canAdvance: foldedState.currentWordIndex >= 0 && foldedState.currentWordIndex < foldedState.stimulusWords.length,
    canReset: foldedState.testStatus === 'completed'
  };
}
