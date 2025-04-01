import { z } from "zod";

/**
 * Environment variable schema validation using Zod
 * This ensures all required environment variables are present and properly formatted
 */
const envSchema = z.object({
    // Hume AI API Key
    NEXT_PUBLIC_HUME_API_KEY: z.string().min(1, "Hume API Key is required"),

    // Supabase Configuration
    NEXT_PUBLIC_SUPABASE_URL: z.string().url(
        "Supabase URL must be a valid URL",
    ),
    NEXT_PUBLIC_SUPABASE_ANON_KEY: z.string().min(
        1,
        "Supabase Anon Key is required",
    ),

    // Database Connection
    DATABASE_URL: z.string().min(1, "Database URL is required"),
});

/**
 * Validated environment variables
 * This throws an error if any required environment variables are missing
 */
export const env = envSchema.parse({
    NEXT_PUBLIC_HUME_API_KEY: process.env.NEXT_PUBLIC_HUME_API_KEY,
    NEXT_PUBLIC_SUPABASE_URL: process.env.NEXT_PUBLIC_SUPABASE_URL,
    NEXT_PUBLIC_SUPABASE_ANON_KEY: process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY,
    DATABASE_URL: process.env.DATABASE_URL,
});

/**
 * Safe client-side environment variables
 * Only includes variables prefixed with NEXT_PUBLIC_
 */
export const clientEnv = {
    NEXT_PUBLIC_HUME_API_KEY: process.env.NEXT_PUBLIC_HUME_API_KEY,
    NEXT_PUBLIC_SUPABASE_URL: process.env.NEXT_PUBLIC_SUPABASE_URL,
    NEXT_PUBLIC_SUPABASE_ANON_KEY: process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY,
};

/**
 * Validates that all required environment variables are set
 * Call this function early in your application startup
 */
export function validateEnv(): void {
    try {
        envSchema.parse({
            NEXT_PUBLIC_HUME_API_KEY: process.env.NEXT_PUBLIC_HUME_API_KEY,
            NEXT_PUBLIC_SUPABASE_URL: process.env.NEXT_PUBLIC_SUPABASE_URL,
            NEXT_PUBLIC_SUPABASE_ANON_KEY:
                process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY,
            DATABASE_URL: process.env.DATABASE_URL,
        });
        console.log("✅ Environment variables validated successfully");
    } catch (error) {
        console.error("❌ Environment variable validation failed:", error);
        throw new Error("Missing or invalid environment variables");
    }
}
