"use server";

import { createSupabaseServerClient } from '@/lib/supabase/server';
import { z } from 'zod';

// Define schemas for input validation
const EmotionDataSchema = z.object({
  userId: z.string(),
  assessmentId: z.string(),
  stimulusWord: z.string(),
  responseWord: z.string(),
  reactionTimeMs: z.number(),
  faceEmotions: z.record(z.number()).optional(),
  voiceEmotions: z.record(z.number()).optional(),
  timestamp: z.number()
});

const FacialEmotionDataSchema = z.object({
  userId: z.string(),
  assessmentId: z.string(),
  stimulusWord: z.string(),
  responseWord: z.string(),
  reactionTimeMs: z.number(),
  emotionData: z.object({
    emotions: z.record(z.number()),
    timestamp: z.number()
  })
});

/**
 * Save emotion data from the Jung Voice Assessment
 */
export async function saveEmotionData(data: z.infer<typeof EmotionDataSchema>) {
  try {
    // Validate input data
    const validatedData = EmotionDataSchema.parse(data);
    
    const supabase = await createSupabaseServerClient();
    
    const { error } = await supabase
      .from('emotion_data')
      .insert({
        user_id: validatedData.userId,
        assessment_id: validatedData.assessmentId,
        stimulus_word: validatedData.stimulusWord,
        response_word: validatedData.responseWord,
        reaction_time_ms: validatedData.reactionTimeMs,
        face_emotions: validatedData.faceEmotions || {},
        voice_emotions: validatedData.voiceEmotions || {},
        timestamp: new Date(validatedData.timestamp).toISOString()
      });
    
    if (error) {
      console.error('Error saving emotion data:', error);
      return { success: false, error: error.message };
    }
    
    return { success: true };
  } catch (error) {
    console.error('Error saving emotion data:', error);
    return { success: false, error: error instanceof z.ZodError 
      ? 'Invalid input data' 
      : 'Failed to save emotion data' 
    };
  }
}

/**
 * Save facial emotion data 
 */
export async function saveFacialEmotionData(params: z.infer<typeof FacialEmotionDataSchema>) {
  try {
    // Validate input data
    const validatedData = FacialEmotionDataSchema.parse(params);
    
    const supabase = await createSupabaseServerClient();
    
    const { error } = await supabase
      .from('face_emotion_data')
      .insert({
        user_id: validatedData.userId,
        assessment_id: validatedData.assessmentId,
        stimulus_word: validatedData.stimulusWord,
        response_word: validatedData.responseWord,
        reaction_time_ms: validatedData.reactionTimeMs,
        emotions: validatedData.emotionData.emotions,
        timestamp: new Date(validatedData.emotionData.timestamp).toISOString()
      });
    
    if (error) {
      console.error('Error saving facial emotion data:', error);
      return { success: false, error: error.message };
    }
    
    return { success: true };
  } catch (error) {
    console.error('Error saving facial emotion data:', error);
    return { success: false, error: error instanceof z.ZodError 
      ? 'Invalid input data' 
      : 'Failed to save facial emotion data' 
    };
  }
}

/**
 * Get emotion data by assessment ID
 */
export async function getEmotionDataByAssessment(userId: string, assessmentId: string) {
  try {
    if (!userId || !assessmentId) {
      return { success: false, error: 'User ID and assessment ID are required' };
    }
    
    const supabase = await createSupabaseServerClient();
    
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