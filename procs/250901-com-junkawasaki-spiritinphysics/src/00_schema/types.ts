import { z } from "zod";

// --- Core Data Structures ---

export const ParticipantSchema = z.object({
  id: z.string().uuid(),
  age: z.number().int().optional(),
  gender: z.string().optional(),
  handedness: z.string().optional(),
  createdAt: z.date(),
});
export type Participant = z.infer<typeof ParticipantSchema>;

// ファイルシステムベースの参加者情報（拡張情報付き）
export interface ParticipantWithFiles extends Participant {
  signature: string;
  agreedAt: string;
  agreements: Record<string, any>;
  hasSessionData: boolean;
  hasVideoFiles: boolean;
  videoFiles: string[];
}

const ExperimentSessionSchema = z.object({
  participantId: z.string().uuid(),
  sessionId: z.string().uuid(),
  sessionType: z.enum(["session-1", "session-2"]),
  startTime: z.string().datetime(),
  endTime: z.string().datetime(),
});

export const WordResponseSchema = z.object({
  stimulusWord: z.object({
    word: z.string(),
    key: z.string(),
  }),
  responseWord: z.string(),
  reactionTimeMs: z.number(),
  isDelayed: z.boolean().optional(),
  audioBlob: z.any().optional(),
});
export type WordResponse = z.infer<typeof WordResponseSchema>;

const SessionDataSchema = z.object({
  participantId: z.string().uuid(),
  events: z.array(z.any()),
  wordResponses: z.array(WordResponseSchema),
});
export type SessionData = z.infer<typeof SessionDataSchema>;

export const WordStimulusSchema = z.object({
  id: z.number().int(),
  word: z.string(),
});
export type WordStimulus = z.infer<typeof WordStimulusSchema>;

const ResponseDataSchema = z.object({
  participantId: z.string().uuid(),
  experimentId: z.string().uuid(),
  wordStimulusId: z.number().int(),
  stimulusWord: z.string(),
  responseWord: z.string(),
  reactionTimeMs: z.number().int(),
  session: z.union([z.literal(1), z.literal(2)]),
  timestamp: z.date(),
  audioFilePath: z.string().optional(),
  videoFilePath: z.string().optional(),
  skinPotential: z.number().optional(),
  emotion: z.string().optional(),
  emotionConfidence: z.number().optional(),
});
export type ResponseData = z.infer<typeof ResponseDataSchema>;

export const ConsentDataSchema = z.object({
  participantId: z.string().uuid(),
  signature: z.string().min(1, { message: "Signature cannot be empty" }),
  agreements: z.object({
    understand: z.literal(true),
    voluntary: z.literal(true),
    withdraw: z.literal(true),
    recording: z.literal(true),
  }),
  agreedAt: z.string().datetime(),
});
export type ConsentData = z.infer<typeof ConsentDataSchema>;

// --- API Payloads ---

const ConsentPayloadSchema = z.object({
  type: z.literal("consent"),
  data: ConsentDataSchema,
});

const ParticipantPayloadSchema = z.object({
  type: z.literal("participant"),
  data: ParticipantSchema,
});

const ExperimentSessionPayloadSchema = z.object({
  type: z.literal("experimentSession"),
  data: ExperimentSessionSchema,
});

const SessionDataPayloadSchema = z.object({
  type: z.literal("session-data"),
  data: SessionDataSchema,
});

const ResponseDataPayloadSchema = z.object({
  type: z.literal("responseData"),
  data: ResponseDataSchema,
});

export const SaveStructuredDataPayloadSchema = z.discriminatedUnion("type", [
  ConsentPayloadSchema,
  ParticipantPayloadSchema,
  ExperimentSessionPayloadSchema,
  ResponseDataPayloadSchema,
  SessionDataPayloadSchema,
]);
export type SaveStructuredDataPayload = z.infer<
  typeof SaveStructuredDataPayloadSchema
>;

// --- Domain Types ---

export type Word = {
  word: string;
  key: string;
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
