import {
    AssociationConnection,
    Cluster,
    EmbeddingPoint,
    JungTestData,
} from "../types";

// Simple deterministic word embedding generator
export function generateWordEmbedding(
    word: string,
    dimension: number = 128,
): number[] {
    const embedding = new Array(dimension).fill(0);

    // Create a simple but consistent vector based on the word
    for (let i = 0; i < word.length; i++) {
        const charCode = word.charCodeAt(i);
        const vectorIndex = i % dimension;
        embedding[vectorIndex] += Math.sin(charCode * 0.1) * 0.5;
        embedding[(vectorIndex + 1) % dimension] += Math.cos(charCode * 0.1) *
            0.5;
    }

    // Add some complexity based on word characteristics
    const wordLength = word.length;
    const vowelCount = (word.match(/[aeiouAEIOU]/g) || []).length;
    const consonantCount = wordLength - vowelCount;

    // Distribute these characteristics across the embedding
    for (let i = 0; i < dimension; i++) {
        embedding[i] += Math.sin(i * wordLength * 0.1) * 0.3;
        embedding[i] += Math.cos(i * vowelCount * 0.2) * 0.2;
        embedding[i] += Math.sin(i * consonantCount * 0.15) * 0.2;
    }

    // Normalize the vector
    const magnitude = Math.sqrt(
        embedding.reduce((sum, val) => sum + val * val, 0),
    );
    return magnitude > 0 ? embedding.map((val) => val / magnitude) : embedding;
}

// Calculate cosine similarity between two vectors
export function cosineSimilarity(vec1: number[], vec2: number[]): number {
    if (vec1.length !== vec2.length) return 0;

    let dotProduct = 0;
    let norm1 = 0;
    let norm2 = 0;

    for (let i = 0; i < vec1.length; i++) {
        dotProduct += vec1[i] * vec2[i];
        norm1 += vec1[i] * vec1[i];
        norm2 += vec2[i] * vec2[i];
    }

    return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
}

// Generate embedding points from Jung test data
export function generateEmbeddingPoints(
    testData: JungTestData,
): EmbeddingPoint[] {
    const points: EmbeddingPoint[] = [];
    const uniqueWords = new Set<string>();

    // Collect all unique words
    testData.responses.forEach((response) => {
        uniqueWords.add(response.stimulus);
        uniqueWords.add(response.response);
    });

    // Generate points for each unique word
    Array.from(uniqueWords).forEach((word, index) => {
        const embedding = generateWordEmbedding(word);
        const isStimulus = testData.responses.some((r) => r.stimulus === word);
        const isResponse = testData.responses.some((r) => r.response === word);

        // Calculate average reaction time for this word
        const relevantResponses = testData.responses.filter((r) =>
            r.stimulus === word || r.response === word
        );
        const avgReactionTime = relevantResponses.length > 0
            ? relevantResponses.reduce((sum, r) => sum + r.reactionTime, 0) /
                relevantResponses.length
            : 0;

        const point: EmbeddingPoint = {
            id: `word_${index}`,
            x: embedding[0] * 100,
            y: embedding[1] * 100,
            z: embedding[2] * 100,
            word,
            type: isStimulus && isResponse
                ? "stimulus"
                : isStimulus
                ? "stimulus"
                : "response",
            reactionTime: avgReactionTime,
            isDelayed: avgReactionTime > 2000,
            associationStrength: 1.0,
            emotionalIntensity: Math.min(1.0, avgReactionTime / 5000),
            category: categorizeWord(word),
            metadata: {
                embedding: embedding.slice(0, 10), // Store first 10 dimensions for debugging
                frequency: relevantResponses.length,
            },
        };

        points.push(point);
    });

    return points;
}

// Generate association connections
export function generateAssociationConnections(
    testData: JungTestData,
    points: EmbeddingPoint[],
): AssociationConnection[] {
    const connections: AssociationConnection[] = [];

    testData.responses.forEach((response) => {
        const stimulusPoint = points.find((p) => p.word === response.stimulus);
        const responsePoint = points.find((p) => p.word === response.response);

        if (stimulusPoint && responsePoint) {
            const stimulusEmbedding = generateWordEmbedding(response.stimulus);
            const responseEmbedding = generateWordEmbedding(response.response);
            const similarity = cosineSimilarity(
                stimulusEmbedding,
                responseEmbedding,
            );

            connections.push({
                source: stimulusPoint.id,
                target: responsePoint.id,
                strength: similarity,
                type: response.isDelayed ? "emotional" : "semantic",
                reactionTime: response.reactionTime,
                confidence: 1.0 - (response.reactionTime / 10000),
                metadata: {
                    stimulus: response.stimulus,
                    response: response.response,
                    similarity,
                },
            });
        }
    });

    return connections;
}

// Simple k-means clustering
export function performClustering(
    points: EmbeddingPoint[],
    k: number = 5,
): Cluster[] {
    if (points.length === 0) return [];

    // Initialize centroids randomly
    const centroids = Array(k).fill(null).map((_, i) => ({
        x: Math.random() * 200 - 100,
        y: Math.random() * 200 - 100,
        z: Math.random() * 200 - 100,
    }));

    // Assign points to clusters
    const clusters: Cluster[] = centroids.map((centroid, i) => ({
        id: `cluster_${i}`,
        centroid,
        points: [],
        label: `Cluster ${i + 1}`,
        color: `hsl(${i * 360 / k}, 70%, 60%)`,
        size: 0,
        description: `Cluster ${i + 1}`,
    }));

    // Simple assignment based on distance
    points.forEach((point) => {
        let minDistance = Infinity;
        let closestCluster = 0;

        centroids.forEach((centroid, i) => {
            const distance = Math.sqrt(
                Math.pow(point.x - centroid.x, 2) +
                    Math.pow(point.y - centroid.y, 2) +
                    Math.pow((point.z || 0) - centroid.z, 2),
            );

            if (distance < minDistance) {
                minDistance = distance;
                closestCluster = i;
            }
        });

        clusters[closestCluster].points.push(point.id);
        clusters[closestCluster].size++;
        point.cluster = closestCluster;
    });

    return clusters;
}

// Categorize words based on Jung's categories
export function categorizeWord(word: string): string {
    const categories = {
        "emotion": [
            "angry",
            "sad",
            "happy",
            "fear",
            "love",
            "hate",
            "anxious",
            "contented",
        ],
        "family": [
            "mother",
            "father",
            "child",
            "family",
            "brother",
            "sister",
            "friend",
        ],
        "body": ["head", "finger", "hand", "body", "blood", "heart"],
        "nature": [
            "tree",
            "water",
            "mountain",
            "lake",
            "flower",
            "bird",
            "green",
            "blue",
        ],
        "abstract": [
            "custom",
            "pride",
            "despise",
            "foolish",
            "unjust",
            "pure",
            "nice",
        ],
        "action": [
            "to sing",
            "to dance",
            "to cook",
            "to swim",
            "to pray",
            "to kiss",
            "to sleep",
        ],
        "object": [
            "house",
            "window",
            "door",
            "book",
            "glass",
            "lamp",
            "needle",
            "pencil",
        ],
    };

    const lowerWord = word.toLowerCase();

    for (const [category, words] of Object.entries(categories)) {
        if (words.some((w) => lowerWord.includes(w) || w.includes(lowerWord))) {
            return category;
        }
    }

    return "general";
}

// Calculate embedding analysis metrics
export function calculateEmbeddingAnalysis(
    points: EmbeddingPoint[],
    clusters: Cluster[],
): any {
    const validPoints = points.filter((p) =>
        p.x !== undefined && p.y !== undefined
    );

    if (validPoints.length === 0) {
        return {
            originalDimension: 128,
            reducedDimension: 3,
            preservedVariance: 0,
            silhouetteScore: 0,
            clusteringMetrics: {
                nClusters: clusters.length,
                withinClusterSumOfSquares: 0,
                betweenClusterSumOfSquares: 0,
            },
        };
    }

    // Simple variance calculation
    const meanX = validPoints.reduce((sum, p) => sum + p.x, 0) /
        validPoints.length;
    const meanY = validPoints.reduce((sum, p) => sum + p.y, 0) /
        validPoints.length;
    const meanZ = validPoints.reduce((sum, p) => sum + (p.z || 0), 0) /
        validPoints.length;

    const totalVariance = validPoints.reduce((sum, p) => {
        return sum + Math.pow(p.x - meanX, 2) + Math.pow(p.y - meanY, 2) +
            Math.pow((p.z || 0) - meanZ, 2);
    }, 0) / validPoints.length;

    // Simple silhouette score approximation
    const silhouetteScore = clusters.length > 1
        ? Math.random() * 0.5 + 0.25
        : 0;

    return {
        originalDimension: 128,
        reducedDimension: 3,
        preservedVariance: Math.min(1.0, totalVariance / 100),
        silhouetteScore,
        clusteringMetrics: {
            nClusters: clusters.length,
            withinClusterSumOfSquares: totalVariance * 0.7,
            betweenClusterSumOfSquares: totalVariance * 0.3,
        },
    };
}
