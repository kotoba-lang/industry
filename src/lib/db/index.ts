'use server'

import { drizzle } from 'drizzle-orm/neon-http'
import { neon } from '@neondatabase/serverless'
import * as schema from './schema'

const connectionString = process.env.DATABASE_URL

if (!connectionString) {
  throw new Error('DATABASE_URL is not set')
}

// Create a Neon HTTP client
const sql = neon(connectionString)

// Provide explicit schema to drizzle
export const db = drizzle(sql, { schema })
