import { db } from '@/lib/db';
import { 
  facialEmotionRecords, 
  voiceEmotionRecords,
  emotionAssessments
} from '@/lib/db/schema/spirit_in_physics';

import { HumeFaceResponse, HumeVoiceResponse } from './hume-service';
import { v4 as uuidv4 } from 'uuid';

export interface EmotionData {
  emotions?: Record<string, number>;
  timestamp: number;
}

export interface EmotionSaveParams {
  userId: string;
  assessmentId: string;
  stimulusWord: string;
  responseWord: string;
  reactionTimeMs: number;
  faceEmotions?: Record<string, number>;
  voiceEmotions?: Record<string, number>;
  timestamp: number;
}

export class EmotionDataService {
  /**
   * 新しい感情データの保存
   */
  static async saveEmotionData(data: EmotionSaveParams) {
    try {
      // まず、アセスメント情報を保存/更新
      await db.insert(emotionAssessments)
        .values({
          id: data.assessmentId,
          userId: data.userId,
          createdAt: new Date(data.timestamp),
          updatedAt: new Date(data.timestamp),
        })
        .onConflictDoUpdate({
          target: emotionAssessments.id,
          set: {
            updatedAt: new Date(data.timestamp),
          }
        });
        
      // 顔の感情データがある場合は保存
      if (data.faceEmotions && Object.keys(data.faceEmotions).length > 0) {
        await db.insert(facialEmotionRecords)
          .values({
            id: uuidv4(),
            assessmentId: data.assessmentId,
            stimulusWord: data.stimulusWord,
            responseWord: data.responseWord,
            reactionTimeMs: data.reactionTimeMs,
            emotions: data.faceEmotions,
            createdAt: new Date(data.timestamp),
          });
      }
        
      // 音声の感情データがある場合は保存
      if (data.voiceEmotions && Object.keys(data.voiceEmotions).length > 0) {
        await db.insert(voiceEmotionRecords)
          .values({
            id: uuidv4(),
            assessmentId: data.assessmentId,
            stimulusWord: data.stimulusWord,
            responseWord: data.responseWord,
            reactionTimeMs: data.reactionTimeMs,
            emotions: data.voiceEmotions,
            createdAt: new Date(data.timestamp),
          });
      }
      
      return { success: true };
    } catch (error) {
      console.error('Error saving emotion data:', error);
      return { success: false, error };
    }
  }

  /**
   * 顔の感情データの保存（従来メソッド - 互換性のために残す）
   */
  static async saveFacialEmotionData(
    userId: string,
    assessmentId: string,
    stimulusWord: string,
    responseWord: string,
    reactionTimeMs: number,
    faceData: EmotionData
  ) {
    return this.saveEmotionData({
      userId,
      assessmentId,
      stimulusWord,
      responseWord,
      reactionTimeMs,
      faceEmotions: faceData.emotions,
      timestamp: faceData.timestamp
    });
  }

  /**
   * 音声の感情データの保存（従来メソッド - 互換性のために残す）
   */
  static async saveVoiceEmotionData(
    userId: string,
    assessmentId: string,
    stimulusWord: string,
    responseWord: string,
    reactionTimeMs: number,
    voiceData: HumeVoiceResponse
  ) {
    // 音声感情データを適切な形式に変換
    const emotions: Record<string, number> = {};
    
    if (voiceData && voiceData.emotions) {
      voiceData.emotions.forEach(emotion => {
        emotions[emotion.name] = emotion.score;
      });
    }
    
    return this.saveEmotionData({
      userId,
      assessmentId,
      stimulusWord,
      responseWord,
      reactionTimeMs,
      voiceEmotions: emotions,
      timestamp: Date.now()
    });
  }

  /**
   * ユーザーIDとアセスメントIDに基づく感情データの取得
   */
  static async getEmotionDataByAssessment(userId: string, assessmentId: string) {
    try {
      // アセスメント情報の取得
      const assessment = await db.query.emotionAssessments.findFirst({
        where: (fields, { eq, and }) => and(
          eq(fields.id, assessmentId),
          eq(fields.userId, userId)
        ),
      });
      
      if (!assessment) {
        return { success: false, error: 'Assessment not found' };
      }
      
      // 顔の感情データの取得
      const facialEmotions = await db.query.facialEmotionRecords.findMany({
        where: (fields, { eq }) => eq(fields.assessmentId, assessmentId),
        orderBy: (fields, { asc }) => [asc(fields.createdAt)],
      });
      
      // 音声の感情データの取得
      const voiceEmotions = await db.query.voiceEmotionRecords.findMany({
        where: (fields, { eq }) => eq(fields.assessmentId, assessmentId),
        orderBy: (fields, { asc }) => [asc(fields.createdAt)],
      });
      
      return { 
        success: true,
        data: {
          assessment,
          facialEmotions,
          voiceEmotions
        }
      };
    } catch (error) {
      console.error('Error retrieving emotion data:', error);
      return { success: false, error };
    }
  }
} 