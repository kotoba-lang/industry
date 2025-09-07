"use server";

import { z } from 'zod';
import { createSupabaseServerClient } from '@/lib/supabase/server';

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
    let processedData: any;
    
    if (formData instanceof FormData) {
      // Handle FormData by creating a new object
      const entries = Object.fromEntries(formData.entries());
      
      processedData = {
        userId: entries.userId as string,
        assessmentId: entries.assessmentId as string,
        stimulusWord: entries.stimulusWord as string,
        responseWord: entries.responseWord as string,
        reactionTimeMs: typeof entries.reactionTimeMs === 'string' 
          ? parseInt(entries.reactionTimeMs, 10) 
          : (entries.reactionTimeMs instanceof File ? 0 : Number(entries.reactionTimeMs)),
        timestamp: typeof entries.timestamp === 'string' 
          ? parseInt(entries.timestamp, 10) 
          : (entries.timestamp instanceof File ? Date.now() : Number(entries.timestamp) || Date.now())
      };
      
      // Parse JSON strings if they exist
      if (entries.faceEmotions) {
        processedData.faceEmotions = typeof entries.faceEmotions === 'string'
          ? JSON.parse(entries.faceEmotions)
          : entries.faceEmotions;
      }
      
      if (entries.voiceEmotions) {
        processedData.voiceEmotions = typeof entries.voiceEmotions === 'string'
          ? JSON.parse(entries.voiceEmotions)
          : entries.voiceEmotions;
      }
    } else {
      // Handle JSON/object data
      processedData = formData;
    }
    
    // Validate data
    const validatedData = SaveEmotionDataSchema.parse(processedData);
    
    // Save emotion data to database
    const client = await createSupabaseServerClient();
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
    const client = await createSupabaseServerClient();
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
    const client = await createSupabaseServerClient();
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