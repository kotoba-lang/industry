// LLM-BOUNDARY: 20_ports - 抽象Port（ドメインが依存するだけ）

import { ConsentData, SaveStructuredDataPayload, EmotionAnalysisResult } from '@/00_schema';

export interface StoragePort {
  saveStructuredData(payload: SaveStructuredDataPayload): Promise<void>;
  saveConsentData(data: ConsentData): Promise<void>;
  saveEmotionAnalysis(participantId: string, result: EmotionAnalysisResult): Promise<void>;
  loadEmotionAnalysis(participantId: string): Promise<EmotionAnalysisResult[]>;
  saveArtifact(participantId: string, type: string, filename: string, data: Buffer): Promise<string>;
}
