// LLM-BOUNDARY: 40_domain - xstate machines（UI非依存）

import { createMachine, assign, ActorRefFrom } from 'xstate';
import {
  KawasakiStoreState,
  Word,
  WordResponse,
  JUNG_STIMULUS_WORDS
} from '@/00_schema';
import { EventType } from '@/10_events';
import { EventBusPort } from '@/20_ports';

// コンテキスト型
export interface JungTestContext {
  participantId: string | null;
  testStatus: KawasakiStoreState['testStatus'];
  deviceStatus: KawasakiStoreState['deviceStatus'];
  mediaStatus: KawasakiStoreState['mediaStatus'];
  currentSession: 1 | 2;
  currentWordIndex: number;
  stimulusWords: Word[];
  wordResponses: WordResponse[];
  error: string | null;
  events: Array<{ timestamp: number; type: string; payload?: object }>;
}

// イベント型
export type JungTestEvent =
  | { type: 'INITIALIZE_PARTICIPANT' }
  | { type: 'START_PREFLIGHT' }
  | { type: 'START_SESSION'; numberOfWords: number }
  | { type: 'RECORD_WORD_RESPONSE'; responseWord: string; reactionTimeMs: number; audioBlob: Blob }
  | { type: 'ADVANCE_TO_NEXT_WORD' }
  | { type: 'COMPLETE_SESSION' }
  | { type: 'SET_DEVICE_STATUS'; status: KawasakiStoreState['deviceStatus'] }
  | { type: 'SET_MEDIA_STATUS'; status: KawasakiStoreState['mediaStatus'] }
  | { type: 'SET_STREAM'; stream: MediaStream | null }
  | { type: 'SET_ERROR'; error: string | null }
  | { type: 'RESET_TEST' };

// 初期コンテキスト
const initialContext: JungTestContext = {
  participantId: null,
  testStatus: 'idle',
  deviceStatus: 'idle',
  mediaStatus: 'idle',
  currentSession: 1,
  currentWordIndex: -1,
  stimulusWords: [],
  wordResponses: [],
  error: null,
  events: [],
};

// Jungテストステートマシン
export const jungTestMachine = createMachine({
  id: 'jung-test',
  initial: 'idle',
  context: initialContext,
  states: {
    idle: {
      on: {
        INITIALIZE_PARTICIPANT: {
          actions: [
            assign({
              participantId: () => crypto.randomUUID(),
              // eslint-disable-next-line
              events: (context: any) => [
                ...context.events,
                {
                  timestamp: Date.now(),
                  type: 'participant_initialized',
                  payload: { participantId: crypto.randomUUID() }
                }
              ]
            }),
            'notifyParticipantInitialized'
          ]
        },
        START_PREFLIGHT: {
          target: 'preflight',
          actions: [
            assign({
              testStatus: 'preflight',
              deviceStatus: 'pending',
              // eslint-disable-next-line
              events: (context: any) => [
                ...context.events,
                {
                  timestamp: Date.now(),
                  type: 'preflight_started'
                }
              ]
            }),
            'notifyPreflightStarted'
          ]
        }
      }
    },

    preflight: {
      on: {
        SET_DEVICE_STATUS: {
          actions: [
            // eslint-disable-next-line
            assign((context: any, event: any) => ({
              deviceStatus: event.status
            })),
            'notifyDeviceStatusChanged'
          ]
        },
        SET_STREAM: {
          actions: [
            // eslint-disable-next-line
            (assign as any)({
              stream: (context: any, event: any) => event.stream
            }),
            'notifyStreamSet'
          ]
        },
        SET_ERROR: {
          actions: [
            // eslint-disable-next-line
            (assign as any)({
              error: (context: any, event: any) => event.error
            }),
            'notifyErrorOccurred'
          ]
        },
        START_SESSION: {
          target: 'sessionRunning',
          actions: [
            assign({
              // eslint-disable-next-line
              testStatus: (context: any) => context.currentSession === 1 ? 'session-1-running' : 'session-2-running',
              // eslint-disable-next-line
              stimulusWords: (context: any, event: any) => {
                const jungWords: Word[] = Object.entries(JUNG_STIMULUS_WORDS).map(
                  ([key, value]) => ({
                    word: value.japanese,
                    key: key,
                  })
                );
                return jungWords.sort(() => 0.5 - Math.random()).slice(0, event.numberOfWords);
              },
              currentWordIndex: 0,
              // eslint-disable-next-line
              events: (context: any, event: any) => [
                ...context.events,
                {
                  timestamp: Date.now(),
                  type: 'session_started',
                  payload: { session: context.currentSession, numberOfWords: event.numberOfWords }
                }
              ]
            }),
            'notifySessionStarted'
          ]
        }
      }
    },

    sessionRunning: {
      on: {
        RECORD_WORD_RESPONSE: {
          actions: [
            // eslint-disable-next-line
            assign((context: any, event: any) => {
              const stimulusWord = context.stimulusWords[context.currentWordIndex];
              const response: WordResponse = {
                stimulusWord,
                responseWord: event.responseWord,
                reactionTimeMs: event.reactionTimeMs,
                audioBlob: event.audioBlob,
              };
              return {
                wordResponses: [...context.wordResponses, response],
                events: [
                  ...context.events,
                  {
                    timestamp: Date.now(),
                    type: 'word_response_recorded',
                    payload: {
                      stimulus: stimulusWord?.word,
                      response: event.responseWord,
                      reactionTime: event.reactionTimeMs,
                    }
                  }
                ]
              };
            }),
            'notifyWordResponseRecorded'
          ]
        },
        ADVANCE_TO_NEXT_WORD: [
          {
            // eslint-disable-next-line
            guard: (context: any) => context.currentWordIndex + 1 >= context.stimulusWords.length,
            target: 'sessionCompleted',
            actions: ['completeSession']
          },
          {
            actions: [
              // eslint-disable-next-line
              assign((context: any) => ({
                currentWordIndex: context.currentWordIndex + 1
              }))
            ]
          }
        ],
        SET_MEDIA_STATUS: {
          actions: [
            // eslint-disable-next-line
            (assign as any)({
              mediaStatus: (context: any, event: any) => event.status
            }),
            'notifyMediaStatusChanged'
          ]
        }
      }
    },

    sessionCompleted: {
      on: {
        START_SESSION: [
          {
            // eslint-disable-next-line
            guard: (context: any) => context.currentSession === 1,
            target: 'sessionRunning',
            actions: [
              assign({
                testStatus: 'session-1-complete',
                currentWordIndex: -1,
                currentSession: 2,
                // eslint-disable-next-line
              events: (context: any) => [
                  ...context.events,
                  {
                    timestamp: Date.now(),
                    type: 'session_1_completed'
                  }
                ]
              }),
              'notifySessionCompleted',
              assign({
                testStatus: 'session-2-running',
                // eslint-disable-next-line
                stimulusWords: (context: any, event: any) => {
                  const jungWords: Word[] = Object.entries(JUNG_STIMULUS_WORDS).map(
                    ([key, value]) => ({
                      word: value.japanese,
                      key: key,
                    })
                  );
                  return jungWords.sort(() => 0.5 - Math.random()).slice(0, event.numberOfWords);
                },
                currentWordIndex: 0,
                // eslint-disable-next-line
              events: (context: any, event: any) => [
                  ...context.events,
                  {
                    timestamp: Date.now(),
                    type: 'session_started',
                    payload: { session: 2, numberOfWords: event.numberOfWords }
                  }
                ]
              }),
              'notifySessionStarted'
            ]
          },
          {
            target: 'completed',
            actions: [
              assign({
                testStatus: 'completed',
                // eslint-disable-next-line
              events: (context: any) => [
                  ...context.events,
                  {
                    timestamp: Date.now(),
                    type: 'session_2_completed'
                  },
                  {
                    timestamp: Date.now(),
                    type: 'test_completed'
                  }
                ]
              }),
              'notifySessionCompleted',
              'notifyTestCompleted'
            ]
          }
        ]
      }
    },

    completed: {
      on: {
        RESET_TEST: {
          target: 'idle',
          actions: [
            assign(initialContext),
            'notifyTestReset'
          ]
        }
      }
    }
  }
}, {
  actions: {
    notifyParticipantInitialized: () => {
      // イベントバスに通知（50_adaptersで実装）
    },
    notifyPreflightStarted: () => {
      // イベントバスに通知
    },
    notifySessionStarted: () => {
      // イベントバスに通知
    },
    notifyWordResponseRecorded: () => {
      // イベントバスに通知
    },
    notifySessionCompleted: () => {
      // イベントバスに通知
    },
    notifyTestCompleted: () => {
      // イベントバスに通知
    },
    notifyDeviceStatusChanged: () => {
      // イベントバスに通知
    },
    notifyMediaStatusChanged: () => {
      // イベントバスに通知
    },
    notifyStreamSet: () => {
      // イベントバスに通知
    },
    notifyErrorOccurred: () => {
      // イベントバスに通知
    },
    notifyTestReset: () => {
      // イベントバスに通知
    },
    completeSession: () => {
      // セッション完了時の追加処理
    }
  }
});

export type JungTestActor = ActorRefFrom<typeof jungTestMachine>;
