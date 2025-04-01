import { NextRequest, NextResponse } from 'next/server';
import { EmotionDataService } from '@/lib/services/emotion-data-service';
import { createSpiritInPhysicsSchema } from '@/db/migrations/create-spirit-in-physics-schema';
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

// Ensure the database schema exists
async function ensureSchemaExists() {
  try {
    await createSpiritInPhysicsSchema();
  } catch (error) {
    console.error('Failed to ensure schema exists:', error);
    throw error;
  }
}

// POST handler for saving emotion data
export async function POST(request: NextRequest) {
  try {
    // Ensure schema exists
    await ensureSchemaExists();
    
    // Parse request body
    const body = await request.json();
    
    // Validate request body
    const validatedData = SaveEmotionDataSchema.parse(body);
    
    // Save emotion data
    const result = await EmotionDataService.saveEmotionData({
      ...validatedData,
      timestamp: validatedData.timestamp || Date.now()
    });
    
    if (!result.success) {
      throw new Error('Failed to save emotion data');
    }
    
    return NextResponse.json({ success: true }, { status: 200 });
  } catch (error) {
    console.error('Error in POST /api/jung-voice-ai/emotion-data:', error);
    
    if (error instanceof z.ZodError) {
      return NextResponse.json(
        { success: false, error: 'Invalid request data', details: error.format() },
        { status: 400 }
      );
    }
    
    return NextResponse.json(
      { success: false, error: 'Internal server error' },
      { status: 500 }
    );
  }
}

// GET handler for retrieving emotion data
export async function GET(request: NextRequest) {
  try {
    // Ensure schema exists
    await ensureSchemaExists();
    
    // Get query parameters
    const userId = request.nextUrl.searchParams.get('userId');
    const assessmentId = request.nextUrl.searchParams.get('assessmentId');
    
    // Validate query parameters
    if (!userId || !assessmentId) {
      return NextResponse.json(
        { success: false, error: 'Missing required parameters: userId and assessmentId' },
        { status: 400 }
      );
    }
    
    try {
      GetEmotionDataSchema.parse({ userId, assessmentId });
    } catch (error) {
      if (error instanceof z.ZodError) {
        return NextResponse.json(
          { success: false, error: 'Invalid parameters', details: error.format() },
          { status: 400 }
        );
      }
    }
    
    // Get emotion data
    const result = await EmotionDataService.getEmotionDataByAssessment(userId, assessmentId);
    
    if (!result.success) {
      return NextResponse.json(
        { success: false, error: result.error },
        { status: 404 }
      );
    }
    
    return NextResponse.json({ success: true, data: result.data }, { status: 200 });
  } catch (error) {
    console.error('Error in GET /api/jung-voice-ai/emotion-data:', error);
    
    return NextResponse.json(
      { success: false, error: 'Internal server error' },
      { status: 500 }
    );
  }
} 