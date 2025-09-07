import {
    ConsentData,
    SessionData,
    SpiritAnalysisResult,
} from "@/types/session";
import { analyzeSessionData } from "./sessionParser";

/**
 * Load and parse session data from JSON file
 */
export async function loadSessionData(filePath: string): Promise<SessionData> {
    try {
        const response = await fetch(filePath);
        if (!response.ok) {
            throw new Error(
                `Failed to load session data: ${response.statusText}`,
            );
        }
        return await response.json();
    } catch (error) {
        console.error("Error loading session data:", error);
        throw error;
    }
}

/**
 * Load and parse consent data from JSON file
 */
export async function loadConsentData(filePath: string): Promise<ConsentData> {
    try {
        const response = await fetch(filePath);
        if (!response.ok) {
            throw new Error(
                `Failed to load consent data: ${response.statusText}`,
            );
        }
        return await response.json();
    } catch (error) {
        console.error("Error loading consent data:", error);
        throw error;
    }
}

/**
 * Load both session and consent data and perform analysis
 */
export async function loadAndAnalyzeData(
    sessionPath: string,
    consentPath: string,
): Promise<SpiritAnalysisResult> {
    try {
        const [sessionData, consentData] = await Promise.all([
            loadSessionData(sessionPath),
            loadConsentData(consentPath),
        ]);

        return analyzeSessionData(sessionData, consentData);
    } catch (error) {
        console.error("Error loading and analyzing data:", error);
        throw error;
    }
}

/**
 * Static data loading for demo purposes
 */
export async function loadDemoData(): Promise<SpiritAnalysisResult> {
    // In a real application, these would be actual file paths
    const sessionPath = "/api/session-data";
    const consentPath = "/api/consent-data";

    return loadAndAnalyzeData(sessionPath, consentPath);
}
