export interface EmotionAnalysisResult {
  participantId: string;
  videoFile: string;
  sessionType: string;
  emotions: Array<{
    name: string;
    score: number;
    confidence: number;
  }>;
  timestamp: string;
  processingTime: number;
}

export interface HumeEmotionResponse {
  predictions: Array<{
    emotions: Array<{
      name: string;
      score: number;
    }>;
    confidence?: number;
  }>;
}

export interface EmotionStatistics {
  totalAnalyses: number;
  averageEmotions: Record<string, number>;
  dominantEmotions: Array<{ emotion: string; count: number }>;
  processingStats: {
    averageTime: number;
    totalTime: number;
  };
}
