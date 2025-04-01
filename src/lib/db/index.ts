import { drizzle } from 'drizzle-orm/postgres-js'
// @ts-ignore - Handle CommonJS import
import postgres from 'postgres'
import { demographicData } from './schema'

const connectionString = process.env.DATABASE_URL

if (!connectionString) {
  throw new Error('DATABASE_URL is not set')
}

// Disable prefetch as it is not supported for "Transaction" pool mode
const client = postgres(connectionString, { prepare: false })
if (!client) {
  throw new Error('Failed to connect to the database')
}

// @ts-ignore - Force types to match
export const db = drizzle(client);
