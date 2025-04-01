import { NextRequest, NextResponse } from 'next/server';
import { createSpiritInPhysicsSchema } from '@/lib/db/migrations/create-schema';

export async function POST(request: NextRequest) {
  try {
    // Run the migration to create the spirit_in_physics schema
    await createSpiritInPhysicsSchema();
    
    return NextResponse.json({ 
      success: true, 
      message: 'Database schema created successfully' 
    });
  } catch (error) {
    console.error('Error setting up database schema:', error);
    return NextResponse.json({ 
      success: false, 
      error: 'Failed to set up database schema',
      details: error instanceof Error ? error.message : String(error)
    }, { status: 500 });
  }
} 