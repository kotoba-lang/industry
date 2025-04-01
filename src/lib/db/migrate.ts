import { drizzle } from 'drizzle-orm/postgres-js';
import { migrate } from 'drizzle-orm/postgres-js/migrator';
import postgres from 'postgres';
import * as dotenv from 'dotenv';

// Load environment variables
dotenv.config({ path: '.env.local' });

const connectionString = process.env.DATABASE_URL;

if (!connectionString) {
  throw new Error('DATABASE_URL is not set');
}

// For migrations
const migrationClient = postgres(connectionString, { max: 1 });

// Run migrations
async function main() {
  console.log('Running migrations...');
  
  const db = drizzle(migrationClient);
  
  console.log('Migration client created');
  
  try {
    await migrate(db, { migrationsFolder: 'drizzle' });
    console.log('Migrations completed successfully');
  } catch (error) {
    console.error('Migration failed:', error);
    process.exit(1);
  } finally {
    await migrationClient.end();
    console.log('Migration client closed');
  }
}

main(); 