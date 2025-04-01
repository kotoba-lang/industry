import { db } from '../db';
import { 
  emotionRecords, 
  facialEmotions, 
  facialRecognition, 
  voiceEmotions, 
  voiceRecognition 
} from '../db/schema/hume_emotion';
import { 
  HumeFaceResponse, 
  HumeVoiceResponse 
} from './hume-service';
import { v4 as uuidv4 } from 'uuid';
import { createSpiritInPhysicsSchema } from '../db/migrations/create-schema';

// 感情データを保存するサービス
export class EmotionDataService {

  /**
   * スキーマが存在することを確認する
   */
  static async ensureSchemaExists(): Promise<void> {
    await createSpiritInPhysicsSchema();
  }
  
  /**
   * 顔の感情データを保存する
   */
  static async saveFacialEmotionData(
    userId: string,
    assessmentId: string,
    stimulusWord: string,
    responseWord: string,
    reactionTimeMs: number,
    faceData: HumeFaceResponse
  ): Promise<string> {
    try {
      // スキーマを確認
      await this.ensureSchemaExists();

      // emotionRecordsにレコードを挿入
      const [record] = await db.insert(emotionRecords)
        .values({
          id: uuidv4(),
          userId,
          assessmentId,
          testType: 'voice',
          stimulusWord,
          responseWord,
          reactionTimeMs,
          recordedAt: new Date()
        })
        .returning({ id: emotionRecords.id });

      if (!record || !record.id) {
        throw new Error('Failed to insert emotion record');
      }

      const emotionRecordId = record.id;

      // 顔の感情データを挿入
      if (faceData.emotions?.length > 0) {
        const emotionValues = faceData.emotions.map(emotion => ({
          id: uuidv4(),
          emotionRecordId,
          emotionName: emotion.name,
          score: emotion.score
        }));

        await db.insert(facialEmotions).values(emotionValues);
      }

      // 顔認識の追加メタデータを挿入
      if (faceData) {
        await db.insert(facialRecognition).values({
          id: uuidv4(),
          emotionRecordId,
          bboxX: faceData.bbox?.x,
          bboxY: faceData.bbox?.y,
          bboxWidth: faceData.bbox?.w,
          bboxHeight: faceData.bbox?.h,
          confidence: faceData.confidence,
          rawData: faceData as any
        });
      }

      return emotionRecordId;
    } catch (error) {
      console.error('Error saving facial emotion data:', error);
      throw error;
    }
  }

  /**
   * 音声の感情データを保存する
   */
  static async saveVoiceEmotionData(
    userId: string,
    assessmentId: string,
    stimulusWord: string,
    responseWord: string,
    reactionTimeMs: number,
    voiceData: HumeVoiceResponse
  ): Promise<string> {
    try {
      // スキーマを確認
      await this.ensureSchemaExists();
      
      // emotionRecordsにレコードを挿入
      const [record] = await db.insert(emotionRecords)
        .values({
          id: uuidv4(),
          userId,
          assessmentId,
          testType: 'voice',
          stimulusWord,
          responseWord,
          reactionTimeMs,
          recordedAt: new Date()
        })
        .returning({ id: emotionRecords.id });

      if (!record || !record.id) {
        throw new Error('Failed to insert emotion record');
      }

      const emotionRecordId = record.id;

      // 音声の感情データを挿入
      if (voiceData.emotions?.length > 0) {
        const emotionValues = voiceData.emotions.map(emotion => ({
          id: uuidv4(),
          emotionRecordId,
          emotionName: emotion.name,
          score: emotion.score
        }));

        await db.insert(voiceEmotions).values(emotionValues);
      }

      // 音声認識の追加メタデータを挿入
      await db.insert(voiceRecognition).values({
        id: uuidv4(),
        emotionRecordId,
        speechDurationMs: voiceData.metadata?.duration_ms,
        speakingRate: voiceData.metadata?.speaking_rate,
        pauseCount: voiceData.metadata?.pause_count,
        confidence: voiceData.confidence,
        rawData: voiceData as any
      });

      return emotionRecordId;
    } catch (error) {
      console.error('Error saving voice emotion data:', error);
      throw error;
    }
  }

  /**
   * 特定のユーザーの顔の感情データを取得する
   */
  static async getFacialEmotionsByUserId(userId: string) {
    try {
      // スキーマを確認
      await this.ensureSchemaExists();
      
      const records = await db.query.emotionRecords.findMany({
        where: (records, { eq, and }) => and(
          eq(records.userId, userId),
          eq(records.testType, 'voice')
        ),
        with: {
          facialEmotions: true,
          facialRecognition: true
        }
      });

      return records;
    } catch (error) {
      console.error('Error retrieving facial emotion data:', error);
      throw error;
    }
  }

  /**
   * 特定のユーザーの音声の感情データを取得する
   */
  static async getVoiceEmotionsByUserId(userId: string) {
    try {
      // スキーマを確認
      await this.ensureSchemaExists();
      
      const records = await db.query.emotionRecords.findMany({
        where: (records, { eq, and }) => and(
          eq(records.userId, userId),
          eq(records.testType, 'voice')
        ),
        with: {
          voiceEmotions: true,
          voiceRecognition: true
        }
      });

      return records;
    } catch (error) {
      console.error('Error retrieving voice emotion data:', error);
      throw error;
    }
  }

  /**
   * 特定の評価セッションの感情データを取得する
   */
  static async getEmotionsByAssessmentId(assessmentId: string) {
    try {
      // スキーマを確認
      await this.ensureSchemaExists();
      
      const records = await db.query.emotionRecords.findMany({
        where: (records, { eq }) => eq(records.assessmentId, assessmentId),
        with: {
          facialEmotions: true,
          facialRecognition: true,
          voiceEmotions: true,
          voiceRecognition: true
        }
      });

      return records;
    } catch (error) {
      console.error('Error retrieving emotion data by assessment ID:', error);
      throw error;
    }
  }
} 