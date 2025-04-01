import { HumeFaceResponse, HumeVoiceResponse } from '@/lib/services/hume-service';
import { v4 as uuidv4 } from 'uuid';
import postgres from 'postgres';

// Get the database client directly - need to recreate it to avoid the drizzle ORM conflict
const connectionString = process.env.DATABASE_URL || '';
const client = postgres(connectionString, { prepare: false });

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
      const timestamp = new Date(data.timestamp);
      
      // まず、アセスメント情報を保存/更新
      await client`
        INSERT INTO spirit_in_physics.emotion_assessments (id, user_id, created_at, updated_at)
        VALUES (${data.assessmentId}, ${data.userId}, ${timestamp}, ${timestamp})
        ON CONFLICT (id) DO UPDATE
        SET updated_at = ${timestamp}
      `;
        
      // 顔の感情データがある場合は保存
      if (data.faceEmotions && Object.keys(data.faceEmotions).length > 0) {
        await client`
          INSERT INTO spirit_in_physics.facial_emotions
          (id, assessment_id, stimulus_word, response_word, reaction_time_ms, emotions, created_at)
          VALUES (${uuidv4()}, ${data.assessmentId}, ${data.stimulusWord}, ${data.responseWord}, 
                  ${data.reactionTimeMs}, ${JSON.stringify(data.faceEmotions)}, ${timestamp})
        `;
      }
        
      // 音声の感情データがある場合は保存
      if (data.voiceEmotions && Object.keys(data.voiceEmotions).length > 0) {
        await client`
          INSERT INTO spirit_in_physics.emotion_records
          (id, assessment_id, stimulus_word, response_word, reaction_time_ms, emotions, created_at)
          VALUES (${uuidv4()}, ${data.assessmentId}, ${data.stimulusWord}, ${data.responseWord}, 
                  ${data.reactionTimeMs}, ${JSON.stringify(data.voiceEmotions)}, ${timestamp})
        `;
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
      const assessmentResults = await client`
        SELECT * FROM spirit_in_physics.emotion_assessments
        WHERE id = ${assessmentId} AND user_id = ${userId}
      `;
      
      const assessment = assessmentResults[0];
      
      if (!assessment) {
        return { success: false, error: 'Assessment not found' };
      }
      
      // 顔の感情データの取得
      const facialEmotions = await client`
        SELECT * FROM spirit_in_physics.facial_emotions
        WHERE assessment_id = ${assessmentId}
        ORDER BY created_at ASC
      `;
      
      // 音声の感情データの取得
      const voiceEmotions = await client`
        SELECT * FROM spirit_in_physics.emotion_records
        WHERE assessment_id = ${assessmentId}
        ORDER BY created_at ASC
      `;
      
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