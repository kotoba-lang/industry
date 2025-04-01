"use server";

import { z } from 'zod';

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
    
    // Save emotion data
    const result = await saveEmotionData({
      ...validatedData,
      timestamp: validatedData.timestamp || Date.now()
    });
    
    if (!result.success) {
      throw new Error('Failed to save emotion data');
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
      error: 'Internal server error'
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