"use server";

import { HumeFaceResponse, HumeVoiceResponse } from "./hume-service";

/**
 * Hume REST API service for emotion analysis
 * Based on https://dev.hume.ai/docs/expression-measurement/rest
 */

// The Hume API endpoint for face emotion analysis
const HUME_API_ENDPOINT = "https://api.hume.ai/v0/batch/jobs";

// Error response type from Hume API
interface HumeErrorResponse {
    error: {
        message: string;
        code: string;
    };
}

/**
 * Send an image to Hume API for face emotion analysis via the REST API
 * @param imageData Base64 encoded image data (without data:image/jpeg;base64, prefix)
 * @param apiKey Hume API key
 * @returns Face emotion analysis result or error
 */
export async function analyzeFaceEmotion(
    imageData: string,
    apiKey: string,
): Promise<HumeFaceResponse | { error: string }> {
    try {
        // Validate API key
        if (!apiKey || apiKey.trim() === "") {
            return { error: "API key is empty or undefined" };
        }

        // Check if imageData is base64-encoded without the prefix
        if (imageData.startsWith("data:image")) {
            // Remove the prefix if it exists
            const base64Content = imageData.split(",")[1];
            imageData = base64Content;
        }

        console.log(
            "Sending request to Hume API using the batch/jobs endpoint...",
        );

        // API request options based on Hume documentation
        const response = await fetch(HUME_API_ENDPOINT, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "X-Hume-Api-Key": apiKey,
            },
            body: JSON.stringify({
                models: {
                    face: {},
                },
                data: imageData,
            }),
        });

        // Check for HTTP error
        if (!response.ok) {
            // Try to parse the error message from the API
            try {
                const errorData = (await response.json()) as HumeErrorResponse;
                return {
                    error: `Hume API error (${response.status}): ${
                        errorData.error?.message || "Unknown API error"
                    }`,
                };
            } catch (e) {
                return {
                    error:
                        `Hume API HTTP error: ${response.status} ${response.statusText}`,
                };
            }
        }

        // Parse the response
        const jobData = await response.json();
        console.log("Hume API job creation response:", jobData);

        // For the batch API, we need to check the job status until it's complete
        if (!jobData.job_id) {
            return {
                error: "Invalid response from Hume API - no job ID returned",
            };
        }

        // This is an asynchronous API, we would need to poll for results
        // For this demo, we'll return a message explaining the situation
        return {
            error:
                "The Hume batch API requires polling for results. This implementation only shows how to submit jobs. Please check server logs for the job ID and use the Hume dashboard to view results.",
        };
    } catch (error) {
        console.error("Error analyzing face emotion:", error);
        return {
            error: `Error analyzing face emotion: ${
                error instanceof Error ? error.message : "Unknown error"
            }`,
        };
    }
}

/**
 * Send an audio sample to Hume API for voice emotion analysis
 * @param audioData Base64 encoded audio data
 * @param apiKey Hume API key
 * @returns Voice emotion analysis result or error
 */
export async function analyzeVoiceEmotion(
    audioData: string,
    apiKey: string,
): Promise<HumeVoiceResponse | { error: string }> {
    // Implement voice emotion analysis if needed
    return { error: "Voice emotion analysis via REST API not implemented yet" };
}
