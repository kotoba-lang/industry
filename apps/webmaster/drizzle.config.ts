import type { Config } from "drizzle-kit";
import dotenv from "dotenv";

// Load environment variables from .env.local
dotenv.config({ path: ".env.local" });

// Get database URL from environment variables
const connectionString = process.env.DATABASE_URL;

if (!connectionString) {
  throw new Error("DATABASE_URL environment variable is not set");
}

export default {
  schema: "./src/lib/db/schema/*",
  out: "./supabase/migrations",
  dialect: "postgresql",
  dbCredentials: {
    url: connectionString,
  },
  // Use Supabase schema namespace
  schemaFilter: ["spirit_in_physics"],
  // Configure entities for handling roles and policies
  entities: {
    roles: {
      provider: "supabase", // Use predefined Supabase roles
    },
  },
} satisfies Config;
