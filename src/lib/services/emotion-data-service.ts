"use server";

import { db } from '@/lib/db';
import { emotionData, faceEmotionData } from '@/lib/db/schema';
import { sql } from 'drizzle-orm';

/**
 * Service for handling emotion data operations
 */
export const EmotionDataService = {
  /**
   * Save emotion data from the Jung Voice Assessment
   */
  saveEmotionData: async (data: {
    userId: string;
    assessmentId: string;
    stimulusWord: string;
    responseWord: string;
    reactionTimeMs: number;
    faceEmotions: Record<string, number>;
    timestamp: number;
  }) => {
    try {
      await db.insert(emotionData).values({
        userId: data.userId,
        assessmentId: data.assessmentId,
        stimulusWord: data.stimulusWord,
        responseWord: data.responseWord,
        reactionTimeMs: data.reactionTimeMs,
        faceEmotions: data.faceEmotions,
        timestamp: new Date(data.timestamp)
      });
      
      return { success: true };
    } catch (error) {
      console.error('Error saving emotion data:', error);
      return { success: false, error: 'Failed to save emotion data' };
    }
  },
  
  /**
   * Save facial emotion data 
   */
  saveFacialEmotionData: async (
    userId: string,
    assessmentId: string,
    stimulusWord: string,
    responseWord: string,
    reactionTimeMs: number,
    emotionData: {
      emotions: Record<string, number>;
      timestamp: number;
    }
  ) => {
    try {
      await db.insert(faceEmotionData).values({
        userId,
        assessmentId,
        stimulusWord,
        responseWord,
        reactionTimeMs,
        emotions: emotionData.emotions,
        timestamp: new Date(emotionData.timestamp)
      });
      
      return { success: true };
    } catch (error) {
      console.error('Error saving facial emotion data:', error);
      return { success: false, error: 'Failed to save facial emotion data' };
    }
  },
  
  /**
   * Get emotion data by assessment ID
   */
  getEmotionDataByAssessment: async (userId: string, assessmentId: string) => {
    try {
      const result = await db.query.emotionData.findMany({
        where: sql`user_id = ${userId} AND assessment_id = ${assessmentId}`
      });
      
      return { success: true, data: result };
    } catch (error) {
      console.error('Error retrieving emotion data:', error);
      return { success: false, error: 'Failed to retrieve emotion data' };
    }
  }
}; 