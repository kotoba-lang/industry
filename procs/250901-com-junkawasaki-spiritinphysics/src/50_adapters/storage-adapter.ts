// LLM-BOUNDARY: 50_adapters - RouteHandler/ServerActions/外部API実装

import { StoragePort } from '@/20_ports';
import { ConsentData, SaveStructuredDataPayload, EmotionAnalysisResult } from '@/00_schema';
import { blobStorage } from '@/lib/blob';
import { databaseManager } from '@/lib/database/kuzu-manager';

export class StorageAdapter implements StoragePort {
  async saveStructuredData(payload: SaveStructuredDataPayload): Promise<void> {
    // 現在のsave-data APIのロジックを移植
    if (payload.type === "consent") {
      await this.saveConsentData(payload.data);
    } else if (payload.type === "session-data") {
      // セッションデータの保存
      const jsonData = JSON.stringify({
        participantId: payload.data.participantId,
        ...payload.data
      }, null, 2);
      const buffer = Buffer.from(jsonData);

      await blobStorage.uploadArtifact(buffer, {
        participantId: payload.data.participantId,
        type: "session_data",
        filename: "session_data.json",
      });
    }
  }

  async saveConsentData(data: ConsentData): Promise<void> {
    const jsonData = JSON.stringify(data, null, 2);
    const buffer = Buffer.from(jsonData);

    await blobStorage.uploadArtifact(buffer, {
      participantId: data.participantId,
      type: "consent",
      filename: "consent.json",
    });
  }

  async saveEmotionAnalysis(participantId: string, result: EmotionAnalysisResult): Promise<void> {
    // Blobに保存
    await blobStorage.saveEmotionAnalysis(participantId, result);

    // Kuzuに保存（利用可能な場合）
    try {
      const analysis = {
        id: `${result.participantId}_${result.videoFile}_${Date.now()}`,
        participantId: result.participantId,
        videoFileId: `${result.participantId}_${result.videoFile}`,
        sessionType: result.sessionType,
        timestamp: result.timestamp,
        processingTime: result.processingTime,
        emotions: result.emotions
      };

      await databaseManager.saveEmotionAnalysis(analysis);
    } catch (error) {
      console.warn('Failed to save emotion analysis to Kuzu:', error);
    }
  }

  async loadEmotionAnalysis(participantId: string): Promise<EmotionAnalysisResult[]> {
    // Blobから読み込みを試行
    try {
      const blobResults = await blobStorage.getEmotionAnalysis(participantId);
      if (blobResults) {
        return [blobResults];
      }
    } catch (error) {
      console.warn(`Failed to load emotion analysis from Blob for ${participantId}:`, error);
    }

    // Kuzuから読み込みを試行
    try {
      const kuzuResults = await databaseManager.getEmotionAnalysis(participantId);
      if (kuzuResults.length > 0) {
        return kuzuResults.map(ka => ({
          participantId: ka.participantId,
          videoFile: ka.videoFileId.replace(`${ka.participantId}_`, ''),
          sessionType: ka.sessionType,
          emotions: ka.emotions,
          timestamp: ka.timestamp,
          processingTime: ka.processingTime
        }));
      }
    } catch (error) {
      console.warn('Failed to load from Kuzu:', error);
    }

    return [];
  }

  async saveArtifact(participantId: string, type: string, filename: string, data: Buffer): Promise<string> {
    return await blobStorage.uploadArtifact(data, {
      participantId,
      type,
      filename,
    });
  }
}

export const storageAdapter = new StorageAdapter();
