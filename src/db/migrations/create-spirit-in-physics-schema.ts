import { sql } from "drizzle-orm";
import { db } from "../index";
import { SCHEMA_NAME } from "../schema/spirit-in-physics";

export async function createSpiritInPhysicsSchema() {
  try {
    // Check if the schema already exists
    const schemaExists = await db.execute(sql`
      SELECT schema_name 
      FROM information_schema.schemata 
      WHERE schema_name = ${SCHEMA_NAME};
    `);

    // If schema doesn't exist, create it
    if (schemaExists.rows.length === 0) {
      await db.execute(sql`CREATE SCHEMA IF NOT EXISTS ${sql.raw(SCHEMA_NAME)};`);
      console.log(`Schema '${SCHEMA_NAME}' created successfully.`);
    } else {
      console.log(`Schema '${SCHEMA_NAME}' already exists.`);
    }

    // Create the emotion_assessments table
    await db.execute(sql`
      CREATE TABLE IF NOT EXISTS ${sql.raw(`${SCHEMA_NAME}.emotion_assessments`)} (
        id UUID PRIMARY KEY,
        user_id TEXT NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
      );
    `);

    // Create the facial_emotion_records table
    await db.execute(sql`
      CREATE TABLE IF NOT EXISTS ${sql.raw(`${SCHEMA_NAME}.facial_emotion_records`)} (
        id UUID PRIMARY KEY,
        assessment_id UUID NOT NULL REFERENCES ${sql.raw(`${SCHEMA_NAME}.emotion_assessments`)}(id) ON DELETE CASCADE,
        stimulus_word TEXT NOT NULL,
        response_word TEXT NOT NULL,
        reaction_time_ms INTEGER NOT NULL,
        emotions JSONB NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
      );
    `);

    // Create the voice_emotion_records table
    await db.execute(sql`
      CREATE TABLE IF NOT EXISTS ${sql.raw(`${SCHEMA_NAME}.voice_emotion_records`)} (
        id UUID PRIMARY KEY,
        assessment_id UUID NOT NULL REFERENCES ${sql.raw(`${SCHEMA_NAME}.emotion_assessments`)}(id) ON DELETE CASCADE,
        stimulus_word TEXT NOT NULL,
        response_word TEXT NOT NULL,
        reaction_time_ms INTEGER NOT NULL,
        emotions JSONB NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
      );
    `);

    // Create the raw_emotion_data table
    await db.execute(sql`
      CREATE TABLE IF NOT EXISTS ${sql.raw(`${SCHEMA_NAME}.raw_emotion_data`)} (
        id UUID PRIMARY KEY,
        assessment_id UUID NOT NULL REFERENCES ${sql.raw(`${SCHEMA_NAME}.emotion_assessments`)}(id) ON DELETE CASCADE,
        data_type TEXT NOT NULL,
        raw_data JSONB NOT NULL,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
      );
    `);

    console.log("All tables in spirit_in_physics schema created successfully.");
    return true;
  } catch (error) {
    console.error("Error creating spirit_in_physics schema:", error);
    throw error;
  }
}

export async function runMigration() {
  try {
    await createSpiritInPhysicsSchema();
    console.log("Migration completed successfully.");
  } catch (error) {
    console.error("Migration failed:", error);
    process.exit(1);
  }
}

// Run the migration if this file is executed directly
if (require.main === module) {
  runMigration();
} 