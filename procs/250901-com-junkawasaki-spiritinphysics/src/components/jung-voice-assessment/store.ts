import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import { v4 as uuidv4 } from 'uuid';
import { JUNG_STIMULUS_WORDS } from './constants';

// --- Type Definitions (from types.ts) ---

export type Word = {
  word: string;
  key: string;
};

export type WordResponse = {
  stimulusWord: Word;
  responseWord: string;
  reactionTimeMs: number;
  audioBlob?: Blob;
  isDelayed?: boolean;
};

export interface TestResult {
  totalWords: number;
  averageReactionTimeMs: number;
  responses: WordResponse[];
  completedAt?: Date;
}

export interface JungVoiceTestProps {
    numberOfWords?: number;
    stimulusWords?: Word[];
    onTestComplete?: (results: TestResult) => void;
    voiceName?: string;
    speechRecognitionLang?: string;
    className?: string;
    onComplete?: () => void;
}

export type MediaStatus = 'idle' | 'recording_session' | 'recording_response' | 'processing';

export interface KawasakiStoreState {
  testStatus: 'idle' | 'preflight' | 'session-1-running' | 'session-1-complete' | 'session-2-running' | 'completed';
  deviceStatus: 'idle' | 'pending' | 'success' | 'error';
  stream: MediaStream | null;
  error: string | null;
  stimulusWords: Word[];
  currentSession: 1 | 2;
  currentWordIndex: number;
  wordResponses: WordResponse[];
  mediaStatus: MediaStatus;
  events: { timestamp: number; type: string; payload?: object }[];
  sessionVideoUrl: string | null;
  participantId: string | null;
}

export interface KawasakiStoreActions {
  startSession: (numberOfWords: number) => void;
  completeSession: () => void;
  advanceToNextWord: () => void;
  recordWordResponse: (response: { responseWord: string; reactionTimeMs: number; audioBlob: Blob }) => void;
  saveSessionData: () => Promise<void>;
  resetTest: () => void;
  setMediaStatus: (status: MediaStatus) => void;
  setDeviceStatus: (status: 'idle' | 'pending' | 'success' | 'error') => void;
  setStream: (stream: MediaStream | null) => void;
  setError: (error: string | null) => void;
  logEvent: (type: string, payload?: object) => void;
  startPreflight: () => void;
  saveSessionVideo: (session: 1 | 2, blob: Blob) => void;
  initializeParticipant: () => void;
}

export type KawasakiStore = KawasakiStoreState & KawasakiStoreActions;


// --- Zustand Store Implementation (from kawasakiStore.ts) ---

const JUNG_WORDS: Word[] = Object.entries(JUNG_STIMULUS_WORDS).map(
  ([key, value]) => ({
    word: value.japanese,
    key: key,
  })
);

const initialState: KawasakiStoreState = {
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

export const useKawasakiStore = create<KawasakiStore>()(
  immer((set, get) => ({
    ...initialState,

    initializeParticipant: () => {
        const participantId = uuidv4();
        set({ participantId });
        get().logEvent('participant_initialized', { participantId });
    },
    
    startPreflight: () => {
        set({ testStatus: 'preflight', deviceStatus: 'pending' });
        get().logEvent('preflight_started');
    },

    setDeviceStatus: (status) => set({ deviceStatus: status }),
    setStream: (stream) => set({ stream }),
    setError: (error) => set({ error }),
    setMediaStatus: (status) => set({ mediaStatus: status }),

    logEvent: (type, payload = {}) => {
        set(state => {
            state.events.push({ timestamp: Date.now(), type, payload });
        });
    },

    startSession: (numberOfWords) => {
        const sessionNumber = get().currentSession === 1 ? 1 : 2;
        const shuffledWords = [...JUNG_WORDS].sort(() => 0.5 - Math.random()).slice(0, numberOfWords);

        set(state => {
            state.testStatus = sessionNumber === 1 ? 'session-1-running' : 'session-2-running';
            state.stimulusWords = shuffledWords;
            state.currentWordIndex = 0;
            state.currentSession = sessionNumber === 1 ? 1 : 2;
        });
        get().logEvent('session_started', { session: get().currentSession, numberOfWords });
    },

    completeSession: () => {
        const { logEvent, currentSession } = get();
        console.log(`completeSession called for session: ${currentSession}`);
        if (currentSession === 1) {
            set({ testStatus: 'session-1-complete', currentWordIndex: -1, currentSession: 2 });
            logEvent('session_1_completed');
        } else {
            set({ testStatus: 'completed' });
            get().logEvent('session_2_completed');
            get().logEvent('test_completed');
        }
    },
    
    advanceToNextWord: () => {
        const { currentWordIndex, stimulusWords, completeSession, testStatus } = get();
        if (testStatus === 'completed') return;

        get().saveSessionData();

        if (currentWordIndex + 1 >= stimulusWords.length) {
            completeSession();
        } else {
            set(state => {
                state.currentWordIndex += 1;
            });
        }
    },

    recordWordResponse: ({ responseWord, reactionTimeMs, audioBlob }) => {
        const { currentWordIndex, stimulusWords, advanceToNextWord, logEvent } = get();
        const stimulusWord = stimulusWords[currentWordIndex];

        const response: WordResponse = {
            stimulusWord,
            responseWord,
            reactionTimeMs,
            audioBlob,
        };
        
        set(state => {
            state.wordResponses.push(response);
        });

        logEvent('word_response_recorded', {
            stimulus: stimulusWord.word,
            response: responseWord,
            reactionTime: reactionTimeMs,
        });
        
        get().saveSessionData();
        
        advanceToNextWord();
    },

    saveSessionData: async () => {
        const { participantId, events, wordResponses } = get();
        const payload = {
            type: 'session-data' as const,
            data: {
                participantId,
                events,
                wordResponses: wordResponses.map(r => ({
                    ...r,
                    audioBlob: undefined,
                })),
            }
        };
        console.log('Attempting to save session data:', payload);
        try {
            const response = await fetch('/api/save-data', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload),
            });
            const responseData = await response.json().catch(() => response.text());
            console.log('Server response from /api/save-data:', { status: response.status, body: responseData });

            if (!response.ok) {
                throw new Error(`Failed to save session data. Status: ${response.status}. Details: ${JSON.stringify(responseData)}`);
            }
            get().logEvent('session_data_saved');
        } catch (error) {
            console.error('Error in saveSessionData:', error);
            get().setError('セッションデータの保存に失敗しました。');
        }
    },

    resetTest: () => {
        set(initialState);
        get().logEvent('test_reset');
    },

    saveSessionVideo: async (session, blob) => {
        const { participantId, logEvent, setError } = get();
        if (!participantId) {
            setError('Participant ID is not set, cannot save video.');
            return;
        }

        const formData = new FormData();
        formData.append('file', blob, `session-${session}.webm`);
        formData.append('sessionId', participantId);
        formData.append('fileName', `session-${session}-video.webm`);
        
        console.log(`Attempting to save session video for session ${session}`);

        try {
            const response = await fetch('/api/save-artifact', {
                method: 'POST',
                body: formData,
            });

            const responseText = await response.text();
            console.log(`Server response from /api/save-artifact for session ${session}:`, { status: response.status, body: responseText });

            if (!response.ok) {
                throw new Error(`Failed to save session video: ${responseText}`);
            }
            const url = `/artifacts_cache/${participantId}/session-${session}-video.webm`;
            set({ sessionVideoUrl: url });
            logEvent(`session_${session}_video_saved`, { url });

        } catch (error) {
            console.error(error);
            setError('動画の保存に失敗しました。');
        }
    },
  }))
); 