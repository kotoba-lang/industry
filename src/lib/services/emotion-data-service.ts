"use server";

import { createServerClient } from '@/lib/supabase/server';

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
      const supabase = await createServerClient();
      
      const { error } = await supabase
        .from('emotion_data')
        .insert({
          user_id: data.userId,
          assessment_id: data.assessmentId,
          stimulus_word: data.stimulusWord,
          response_word: data.responseWord,
          reaction_time_ms: data.reactionTimeMs,
          face_emotions: data.faceEmotions,
          timestamp: new Date(data.timestamp).toISOString()
        });
      
      if (error) {
        console.error('Error saving emotion data:', error);
        return { success: false, error: error.message };
      }
      
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
      const supabase = await createServerClient();
      
      const { error } = await supabase
        .from('face_emotion_data')
        .insert({
          user_id: userId,
          assessment_id: assessmentId,
          stimulus_word: stimulusWord,
          response_word: responseWord,
          reaction_time_ms: reactionTimeMs,
          emotions: emotionData.emotions,
          timestamp: new Date(emotionData.timestamp).toISOString()
        });
      
      if (error) {
        console.error('Error saving facial emotion data:', error);
        return { success: false, error: error.message };
      }
      
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
      const supabase = await createServerClient();
      
      const { data, error } = await supabase
        .from('emotion_data')
        .select('*')
        .eq('user_id', userId)
        .eq('assessment_id', assessmentId);
      
      if (error) {
        console.error('Error retrieving emotion data:', error);
        return { success: false, error: error.message };
      }
      
      return { success: true, data: data || [] };
    } catch (error) {
      console.error('Error retrieving emotion data:', error);
      return { success: false, error: 'Failed to retrieve emotion data' };
    }
  }
}; 