export interface SessionEvent {
    timestamp: number;
    type: string;
    payload: any;
}

export interface SessionData {
    participantId: string;
    events: SessionEvent[];
}

export interface ConsentData {
    participantId: string;
    signature: string;
    agreements: {
        understand: boolean;
        voluntary: boolean;
        withdraw: boolean;
        recording: boolean;
    };
    agreedAt: string;
}

export interface WordAssociationEvent {
    stimulusWord: string;
    stimulusKey: string;
    responseWord: string;
    responseKey: string;
    timestamp: number;
    reactionTimeMs?: number;
}

export interface SpiritMetrics {
    participantId: string;
    totalResponses: number;
    averageReactionTime: number;
    delayedResponses: number;
    wordAssociations: WordAssociationEvent[];
    sessionStartTime: number;
    sessionEndTime: number;
}

export interface EmotionData {
    timestamp: number;
    emotion: string;
    confidence: number;
    intensity: number;
}

export interface SpiritAnalysisResult {
    metrics: SpiritMetrics;
    emotions: EmotionData[];
    consent: ConsentData;
    kawasakiModelData: {
        energy: number;
        vectors: Array<{
            stimulus: string;
            response: string;
            energy: number;
            vector: [number, number, number];
        }>;
        timeSeries: Array<{
            timestamp: number;
            energy: number;
            entropy: number;
        }>;
    };
}
