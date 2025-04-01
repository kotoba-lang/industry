import { validateEnv } from "./env";

/**
 * Initialize server-side components and validate configuration
 * This is called early in the application lifecycle
 */
export function initializeServer() {
    // Validate environment variables
    validateEnv();

    // Add other server initialization logic here
    // For example: database connections, external service setup, etc.
}

// Run initialization in development and production
// This will be executed when the file is imported
if (process.env.NODE_ENV !== "test") {
    try {
        initializeServer();
    } catch (error) {
        console.error("❌ Server initialization failed:", error);
        // In production, we might want to exit the process
        if (process.env.NODE_ENV === "production") {
            process.exit(1);
        }
    }
}
