import { drizzle } from 'drizzle-orm/postgres-js';
import { migrate } from 'drizzle-orm/postgres-js/migrator';
import postgres from 'postgres';

// Get the database URL from the environment
const connectionString = process.env.DATABASE_URL ||
  'postgres://postgres:postgres@localhost:5432/postgres';

// Create a postgres connection
const queryClient = postgres(connectionString, { max: 1 });

// Create a drizzle client
export const db = drizzle(queryClient);

// Function to run migrations
export async function runMigrations() {
  try {
    await migrate(db, { migrationsFolder: 'drizzle' });
    console.log('Migrations completed successfully');
  } catch (error) {
    console.error('Error running migrations:', error);
    throw error;
  }
} 