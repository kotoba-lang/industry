import {
    ConsentData,
    SessionData,
    SpiritAnalysisResult,
    SpiritMetrics,
    WordAssociationEvent,
} from "@/types/session";

/**
 * Parse session data and extract word association events
 */
export function parseWordAssociations(
    sessionData: SessionData,
): WordAssociationEvent[] {
    const associations: WordAssociationEvent[] = [];
    const wordDisplayMap = new Map<
        number,
        { word: string; key: string; timestamp: number }
    >();

    for (const event of sessionData.events) {
        if (event.type === "word_displayed") {
            wordDisplayMap.set(event.timestamp, {
                word: event.payload.word,
                key: event.payload.key,
                timestamp: event.timestamp,
            });
        } else if (event.type === "speech_detected") {
            // Find the most recent word_displayed event before this speech detection
            const recentDisplays = Array.from(wordDisplayMap.entries())
                .filter(([timestamp]) => timestamp < event.timestamp)
                .sort(([a], [b]) => b - a);

            if (recentDisplays.length > 0) {
                const [, stimulus] = recentDisplays[0];
                const reactionTime = Math.max(
                    0,
                    event.timestamp - stimulus.timestamp,
                ); // Ensure non-negative

                associations.push({
                    stimulusWord: stimulus.word,
                    stimulusKey: stimulus.key,
                    responseWord: event.payload.word,
                    responseKey: event.payload.key,
                    timestamp: event.timestamp,
                    reactionTimeMs: reactionTime > 0 ? reactionTime : undefined, // Only include valid reaction times
                });
            }
        }
    }

    return associations;
}

/**
 * Calculate spirit metrics from word associations
 */
export function calculateSpiritMetrics(
    sessionData: SessionData,
    wordAssociations: WordAssociationEvent[],
): SpiritMetrics {
    const sessionStart =
        sessionData.events.find((e) => e.type === "session_started")
            ?.timestamp || 0;
    const sessionEnd =
        sessionData.events.find((e) => e.type === "session_1_completed")
            ?.timestamp || Date.now();

    const reactionTimes = wordAssociations
        .filter((assoc) => assoc.reactionTimeMs)
        .map((assoc) => assoc.reactionTimeMs!);

    const averageReactionTime = reactionTimes.length > 0
        ? reactionTimes.reduce((sum, time) => sum + time, 0) /
            reactionTimes.length
        : 0;

    const delayedResponses = reactionTimes.filter((time) => time > 2000).length;

    return {
        participantId: sessionData.participantId,
        totalResponses: wordAssociations.length,
        averageReactionTime,
        delayedResponses,
        wordAssociations,
        sessionStartTime: sessionStart,
        sessionEndTime: sessionEnd,
    };
}

/**
 * Generate Kawasaki Model data from spirit metrics
 */
export function generateKawasakiModelData(metrics: SpiritMetrics) {
    // Filter out associations without reaction time data
    const validAssociations = metrics.wordAssociations.filter((assoc) =>
        assoc.reactionTimeMs !== undefined
    );

    if (validAssociations.length === 0) {
        // Return empty data if no valid associations
        return {
            energy: 0,
            vectors: [],
            timeSeries: [],
        };
    }

    const vectors = validAssociations.map((assoc, index) => {
        // Simple energy calculation based on reaction time
        const reactionTime = assoc.reactionTimeMs || 1000; // Default 1 second if undefined
        const energy = Math.exp(-reactionTime / 1000);

        // Generate 3D vector based on word properties and reaction time
        const vector: [number, number, number] = [
            Math.sin(index * 0.1) * energy,
            Math.cos(index * 0.1) * energy,
            (reactionTime / 1000) * 0.5,
        ];

        return {
            stimulus: assoc.stimulusWord,
            response: assoc.responseWord,
            energy,
            vector,
        };
    });

    // Generate time series data
    const timeSeries = validAssociations.map((assoc, index) => {
        const reactionTime = assoc.reactionTimeMs || 1000;
        const energy = Math.exp(-reactionTime / 1000);
        const entropy = -energy * Math.log(energy + 1e-10); // Avoid log(0)

        return {
            timestamp: assoc.timestamp,
            energy,
            entropy,
        };
    });

    // Ensure we return valid arrays
    const validVectors = Array.isArray(vectors) ? vectors : [];
    const validTimeSeries = Array.isArray(timeSeries) ? timeSeries : [];

    return {
        energy: validVectors.length > 0
            ? validVectors.reduce((sum, v) => sum + v.energy, 0) /
                validVectors.length
            : 0,
        vectors: validVectors,
        timeSeries: validTimeSeries,
    };
}

/**
 * Main function to analyze session data
 */
export function analyzeSessionData(
    sessionData: SessionData,
    consentData: ConsentData,
): SpiritAnalysisResult {
    const wordAssociations = parseWordAssociations(sessionData);
    const metrics = calculateSpiritMetrics(sessionData, wordAssociations);
    const kawasakiModelData = generateKawasakiModelData(metrics);

    // Ensure kawasakiModelData is properly structured
    const safeKawasakiModelData = {
        energy: typeof kawasakiModelData.energy === "number"
            ? kawasakiModelData.energy
            : 0,
        vectors: Array.isArray(kawasakiModelData.vectors)
            ? kawasakiModelData.vectors
            : [],
        timeSeries: Array.isArray(kawasakiModelData.timeSeries)
            ? kawasakiModelData.timeSeries
            : [],
    };

    return {
        metrics,
        emotions: [], // Will be populated if emotion data is available
        consent: consentData,
        kawasakiModelData: safeKawasakiModelData,
    };
}
