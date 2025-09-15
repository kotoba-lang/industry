// LLM-BOUNDARY: 40_domain - xstate machines（UI非依存）

import { createMachine, assign, ActorRefFrom } from 'xstate';
import { EmotionAnalysisResult } from '@/00_schema';

// コンテキスト型
export interface EmotionAnalysisContext {
  participantId: string | null;
  currentAnalysis: EmotionAnalysisResult | null;
  analysisResults: EmotionAnalysisResult[];
  isAnalyzing: boolean;
  error: string | null;
}

// イベント型
export type EmotionAnalysisEvent =
  | { type: 'START_ANALYSIS'; participantId: string; videoFileName?: string; sessionType?: string }
  | { type: 'ANALYSIS_COMPLETED'; result: EmotionAnalysisResult }
  | { type: 'ANALYSIS_FAILED'; error: string }
  | { type: 'LOAD_RESULTS'; participantId: string }
  | { type: 'RESULTS_LOADED'; results: EmotionAnalysisResult[] }
  | { type: 'START_BATCH_ANALYSIS'; participantId: string }
  | { type: 'BATCH_COMPLETED'; results: EmotionAnalysisResult[] };

// 初期コンテキスト
const initialContext: EmotionAnalysisContext = {
  participantId: null,
  currentAnalysis: null,
  analysisResults: [],
  isAnalyzing: false,
  error: null,
};

// 感情分析ステートマシン
export const emotionAnalysisMachine = createMachine({
  id: 'emotion-analysis',
  initial: 'idle',
  context: initialContext,
  states: {
    idle: {
      on: {
        START_ANALYSIS: {
          target: 'analyzing',
          actions: [
            // eslint-disable-next-line
            assign((context: any, event: any) => ({
              participantId: event.participantId,
              isAnalyzing: true,
              error: null
            }))
          ]
        },
        LOAD_RESULTS: {
          target: 'loading',
          actions: [
            // eslint-disable-next-line
            assign((context: any, event: any) => ({
              participantId: event.participantId,
              error: null
            }))
          ]
        },
        START_BATCH_ANALYSIS: {
          target: 'batchAnalyzing',
          actions: [
            // eslint-disable-next-line
            assign((context: any, event: any) => ({
              participantId: event.participantId,
              isAnalyzing: true,
              error: null
            }))
          ]
        }
      }
    },

    analyzing: {
      on: {
        ANALYSIS_COMPLETED: {
          target: 'idle',
          actions: [
            // eslint-disable-next-line
            assign((context: any, event: any) => ({
              currentAnalysis: event.result,
              analysisResults: [...context.analysisResults, event.result],
              isAnalyzing: false
            })),
            'notifyAnalysisCompleted'
          ]
        },
        ANALYSIS_FAILED: {
          target: 'idle',
          actions: [
            // eslint-disable-next-line
            assign((context: any, event: any) => ({
              error: event.error,
              isAnalyzing: false
            })),
            'notifyAnalysisFailed'
          ]
        }
      }
    },

    loading: {
      on: {
        RESULTS_LOADED: {
          target: 'idle',
          actions: [
            // eslint-disable-next-line
            assign((context: any, event: any) => ({
              analysisResults: event.results
            })),
            'notifyResultsLoaded'
          ]
        },
        ANALYSIS_FAILED: {
          target: 'idle',
          actions: [
            // eslint-disable-next-line
            assign((context: any, event: any) => ({
              error: event.error
            })),
            'notifyLoadFailed'
          ]
        }
      }
    },

    batchAnalyzing: {
      on: {
        BATCH_COMPLETED: {
          target: 'idle',
          actions: [
            // eslint-disable-next-line
            assign((context: any, event: any) => ({
              analysisResults: event.results,
              isAnalyzing: false
            })),
            'notifyBatchCompleted'
          ]
        },
        ANALYSIS_FAILED: {
          target: 'idle',
          actions: [
            // eslint-disable-next-line
            assign((context: any, event: any) => ({
              error: event.error,
              isAnalyzing: false
            })),
            'notifyBatchFailed'
          ]
        }
      }
    }
  }
}, {
  actions: {
    notifyAnalysisCompleted: () => {
      // イベントバスに通知
    },
    notifyAnalysisFailed: () => {
      // イベントバスに通知
    },
    notifyResultsLoaded: () => {
      // イベントバスに通知
    },
    notifyLoadFailed: () => {
      // イベントバスに通知
    },
    notifyBatchCompleted: () => {
      // イベントバスに通知
    },
    notifyBatchFailed: () => {
      // イベントバスに通知
    }
  }
});

export type EmotionAnalysisActor = ActorRefFrom<typeof emotionAnalysisMachine>;
