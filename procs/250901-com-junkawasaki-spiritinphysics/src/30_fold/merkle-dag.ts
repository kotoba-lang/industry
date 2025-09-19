// LLM-BOUNDARY: 30_fold - 純関数（MDAG -> 投影）※副作用禁止

import { KawasakiStoreState, Word, WordResponse, MediaStatus } from '@/00_schema';
import { EventType } from '@/10_events';

// MerkleDAG: イベントのシーケンス（イミュータブルなイベント履歴）
export type MerkleDAG = ReadonlyArray<{
  type: EventType;
  payload?: any;
  timestamp: number;
  aggregateId?: string;
}>;

// 初期状態
export const initialState: KawasakiStoreState = {
  testStatus: 'idle',
  deviceStatus: 'idle',
  stream: null,
  error: null,
  stimulusWords: [],
  currentSession: 1,
  currentWordIndex: -1,
  wordResponses: [],
  mediaStatus: 'idle',
  events: [],
  sessionVideoUrl: null,
  participantId: null,
};

// 純関数: MerkleDAGから現在の状態を投影
export function foldMerkleDAG(dag: MerkleDAG): KawasakiStoreState {
  return dag.reduce((state, event) => foldEvent(state, event), initialState);
}

// 個別のイベントを状態に適用する純関数
function foldEvent(state: KawasakiStoreState, event: MerkleDAG[0]): KawasakiStoreState {
  switch (event.type) {
    case 'EV_PARTICIPANT_INITIALIZED':
      return {
        ...state,
        participantId: event.payload.participantId,
      };

    case 'EV_PREFLIGHT_STARTED':
      return {
        ...state,
        testStatus: 'preflight',
        deviceStatus: 'pending',
      };

    case 'EV_SESSION_STARTED':
      return {
        ...state,
        testStatus: event.payload.sessionNumber === 1 ? 'session-1-running' : 'session-2-running',
        stimulusWords: event.payload.shuffledWords,
        currentWordIndex: 0,
        currentSession: event.payload.sessionNumber,
      };

    case 'EV_SESSION_COMPLETED':
      if (event.payload.sessionNumber === 1) {
        return {
          ...state,
          testStatus: 'session-1-complete',
          currentWordIndex: -1,
          currentSession: 2,
        };
      } else {
        return {
          ...state,
          testStatus: 'completed',
        };
      }

    case 'EV_WORD_RESPONSE_RECORDED':
      return {
        ...state,
        wordResponses: [...state.wordResponses, event.payload.response],
      };

    case 'EV_DEVICE_STATUS_CHANGED':
      return {
        ...state,
        deviceStatus: event.payload.status,
      };

    case 'EV_MEDIA_STATUS_CHANGED':
      return {
        ...state,
        mediaStatus: event.payload.status,
      };

    case 'EV_STREAM_SET':
      return {
        ...state,
        stream: event.payload.stream,
      };

    case 'EV_ERROR_OCCURRED':
      return {
        ...state,
        error: event.payload.error,
      };

    case 'EV_EVENT_LOGGED':
      return {
        ...state,
        events: [...state.events, event.payload.loggedEvent],
      };

    case 'EV_SESSION_VIDEO_SAVED':
      return {
        ...state,
        sessionVideoUrl: event.payload.url,
      };

    case 'EV_TEST_RESET':
      return initialState;

    default:
      return state;
  }
}
