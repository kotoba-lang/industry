"use server";

import { z } from 'zod';
import { createServerClient } from '@/lib/supabase/server';

// Input validation schema for saving emotion data
const SaveEmotionDataSchema = z.object({
  userId: z.string().min(1),
  assessmentId: z.string().uuid(),
  stimulusWord: z.string(),
  responseWord: z.string(),
  reactionTimeMs: z.number().int().nonnegative(),
  faceEmotions: z.record(z.string(), z.number()).optional(),
  voiceEmotions: z.record(z.string(), z.number()).optional(),
  timestamp: z.number().optional(),
});

// Input validation schema for retrieving emotion data
const GetEmotionDataSchema = z.object({
  userId: z.string().min(1),
  assessmentId: z.string().uuid(),
});

/**
 * Save emotion data from the Jung Voice Assessment
 */
export async function saveEmotionData(formData: FormData | any) {
  try {
    // Parse and validate the data
    let data;
    
    if (formData instanceof FormData) {
      // Handle FormData
      const rawData = Object.fromEntries(formData.entries());
      
      // Convert string numbers to actual numbers
      if (typeof rawData.reactionTimeMs === 'string') {
        rawData.reactionTimeMs = parseInt(rawData.reactionTimeMs, 10);
      }
      
      // Parse JSON strings if they exist
      if (typeof rawData.faceEmotions === 'string') {
        rawData.faceEmotions = JSON.parse(rawData.faceEmotions);
      }
      
      if (typeof rawData.voiceEmotions === 'string') {
        rawData.voiceEmotions = JSON.parse(rawData.voiceEmotions);
      }
      
      if (typeof rawData.timestamp === 'string') {
        rawData.timestamp = parseInt(rawData.timestamp, 10);
      }
      
      data = rawData;
    } else {
      // Handle JSON/object data
      data = formData;
    }
    
    // Validate data
    const validatedData = SaveEmotionDataSchema.parse(data);
    
    // Save emotion data to database
    const client = await createServerClient();
    const { error } = await client.from('emotion_data').insert({
      user_id: validatedData.userId,
      assessment_id: validatedData.assessmentId,
      stimulus_word: validatedData.stimulusWord,
      response_word: validatedData.responseWord,
      reaction_time_ms: validatedData.reactionTimeMs,
      face_emotions: validatedData.faceEmotions,
      voice_emotions: validatedData.voiceEmotions,
      timestamp: new Date(validatedData.timestamp || Date.now()).toISOString()
    });
    
    if (error) {
      return {
        success: false,
        error: error.message
      };
    }
    
    return { success: true };
  } catch (error) {
    console.error('Error in saveEmotionData:', error);
    
    if (error instanceof z.ZodError) {
      return {
        success: false,
        error: 'Invalid request data',
        details: error.format()
      };
    }
    
    return {
      success: false,
      error: 'Failed to save emotion data'
    };
  }
}

/**
 * Save facial emotion data
 */
export async function saveFacialEmotionData(
  userId: string,
  assessmentId: string,
  stimulusWord: string,
  responseWord: string,
  reactionTimeMs: number,
  emotionData: { emotions: Record<string, number>, timestamp: number }
) {
  try {
    // Save emotion data to database
    const client = await createServerClient();
    const { error } = await client.from('face_emotion_data').insert({
      user_id: userId,
      assessment_id: assessmentId,
      stimulus_word: stimulusWord,
      response_word: responseWord,
      reaction_time_ms: reactionTimeMs,
      emotions: emotionData.emotions,
      timestamp: new Date(emotionData.timestamp).toISOString()
    });
    
    if (error) {
      return {
        success: false,
        error: error.message
      };
    }
    
    return { success: true };
  } catch (error) {
    console.error('Error in saveFacialEmotionData:', error);
    return {
      success: false,
      error: 'Failed to save facial emotion data'
    };
  }
}

/**
 * Get emotion data by assessment ID
 */
export async function getEmotionData(userId: string, assessmentId: string) {
  try {
    // Validate parameters
    GetEmotionDataSchema.parse({ userId, assessmentId });
    
    // Get emotion data
    const result = await getEmotionDataByAssessment(userId, assessmentId);
    
    if (!result.success) {
      return {
        success: false,
        error: result.error
      };
    }
    
    return {
      success: true,
      data: result.data
    };
  } catch (error) {
    console.error('Error in getEmotionData:', error);
    
    if (error instanceof z.ZodError) {
      return {
        success: false,
        error: 'Invalid parameters',
        details: error.format()
      };
    }
    
    return {
      success: false,
      error: 'Internal server error'
    };
  }
}

/**
 * Get emotion data by assessment ID from database
 */
export async function getEmotionDataByAssessment(userId: string, assessmentId: string) {
  try {
    const client = await createServerClient();
    const { data, error } = await client
      .from('emotion_data')
      .select('*')
      .eq('user_id', userId)
      .eq('assessment_id', assessmentId);
    
    if (error) {
      return {
        success: false,
        error: error.message
      };
    }
    
    return {
      success: true,
      data: data || []
    };
  } catch (error) {
    console.error('Error in getEmotionDataByAssessment:', error);
    return {
      success: false,
      error: 'Failed to get emotion data'
    };
  }
} 