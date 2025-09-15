// LLM-BOUNDARY: 50_adapters - RouteHandler/ServerActions/外部API実装

import { StoragePort } from '@/20_ports';
import { ConsentData, SaveStructuredDataPayload, EmotionAnalysisResult, Participant, ParticipantWithFiles, SessionData } from '@/00_schema';
import { kuzuManager } from '@/lib/database/kuzu-manager';
import type { Participant as KuzuParticipant } from '@/lib/database/kuzu-manager';

export class StorageAdapter implements StoragePort {
  async saveStructuredData(payload: SaveStructuredDataPayload): Promise<void> {
    // Kuzuデータベースに保存（一本化）
    if (payload.type === "consent") {
      await this.saveConsentData(payload.data);
    } else if (payload.type === "session-data") {
      await kuzuManager.saveSession({
        id: `${payload.data.participantId}_session`,
        participantId: payload.data.participantId,
        events: payload.data.events,
        createdAt: payload.data.events[0]?.timestamp || new Date().toISOString()
      });
    }
  }

  async saveConsentData(data: ConsentData): Promise<void> {
    // Kuzuデータベースに保存（一本化）
    await kuzuManager.saveParticipant({
      id: data.participantId,
      signature: data.signature,
      agreedAt: data.agreedAt,
      agreements: data.agreements
    });
  }

  async saveEmotionAnalysis(participantId: string, result: EmotionAnalysisResult): Promise<void> {
    // Kuzuデータベースに保存（一本化）
    const analysis = {
      id: `${result.participantId}_${result.videoFile}_${Date.now()}`,
      participantId: result.participantId,
      videoFileId: `${result.participantId}_${result.videoFile}`,
      sessionType: result.sessionType,
      timestamp: result.timestamp,
      processingTime: result.processingTime,
      emotions: result.emotions
    };

    await kuzuManager.saveEmotionAnalysis(analysis);
  }

  async loadEmotionAnalysis(participantId: string): Promise<EmotionAnalysisResult[]> {
    // Kuzuデータベースから読み込み（一本化）
    try {
      const kuzuResults = await kuzuManager.getEmotionAnalysis(participantId);
      return kuzuResults.map(ka => ({
        participantId: ka.participantId,
        videoFile: ka.videoFileId.replace(`${ka.participantId}_`, ''),
        sessionType: ka.sessionType,
        emotions: ka.emotions,
        timestamp: ka.timestamp,
        processingTime: ka.processingTime
      }));
    } catch (error) {
      console.warn('Failed to load emotion analysis from Kuzu:', error);
      return [];
    }
  }

  async saveArtifact(participantId: string, type: string, filename: string, data: Buffer): Promise<string> {
    // アーティファクト保存は未実装（必要に応じて実装）
    // 現在はURLを返すダミー実装
    return `kuzu://artifacts/${participantId}/${filename}`;
  }

  // data-loader.ts から統合した追加メソッド
  async loadAllParticipants(): Promise<ParticipantWithFiles[]> {
    // Kuzuデータベースから参加者データを取得（一本化）
    try {
      const kuzuParticipants: KuzuParticipant[] = await kuzuManager.getAllParticipants();
      return kuzuParticipants.map(kp => ({
        id: kp.id,
        age: undefined, // KuzuParticipantにはない
        gender: undefined, // KuzuParticipantにはない
        handedness: undefined, // KuzuParticipantにはない
        createdAt: new Date(), // 仮の日付
        signature: kp.signature,
        agreedAt: kp.agreedAt,
        agreements: kp.agreements,
        hasSessionData: false, // 後で更新
        hasVideoFiles: false, // 後で更新
        videoFiles: []
      }));
    } catch (error) {
      console.warn('Failed to load participants from Kuzu:', error);
      return [];
    }
  }

  async loadSessionData(participantId: string): Promise<SessionData | null> {
    // Kuzuデータベースからセッションデータを取得（一本化）
    try {
      // KuzuManagerにgetSessionDataメソッドが必要
      // 現時点では仮の実装
      console.log(`Loading session data from Kuzu for ${participantId}`);
      return null; // 仮実装
    } catch (error) {
      console.warn('Failed to load session data from Kuzu:', error);
      return null;
    }
  }
}

export const storageAdapter = new StorageAdapter();

