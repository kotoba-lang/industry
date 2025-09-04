import { EmbeddingPoint, UMAPConfig } from "../types";

// Simple implementation of UMAP-like dimensionality reduction
export class SimpleDimensionReducer {
    private config: UMAPConfig;
    private embeddings: number[][];
    private reducedEmbeddings: number[][];

    constructor(config: UMAPConfig) {
        this.config = config;
        this.embeddings = [];
        this.reducedEmbeddings = [];
    }

    // Fit the reducer on high-dimensional data
    fit(embeddings: number[][]): void {
        this.embeddings = embeddings;
        this.reducedEmbeddings = this.performReduction();
    }

    // Transform new data using the fitted reducer
    transform(newEmbeddings: number[][]): number[][] {
        // For simplicity, just apply the same reduction process
        return this.performReductionOn(newEmbeddings);
    }

    // Get the reduced embeddings
    getReduced(): number[][] {
        return this.reducedEmbeddings;
    }

    private performReduction(): number[][] {
        const n = this.embeddings.length;
        const targetDim = this.config.nComponents;

        if (n === 0) return [];

        // Initialize reduced embeddings randomly
        const reduced = Array(n).fill(null).map(() =>
            Array(targetDim).fill(0).map(() => (Math.random() - 0.5) * 2)
        );

        // Simple iterative optimization (simplified UMAP-like process)
        const maxIterations = 200;
        const learningRate = 0.1;

        for (let iter = 0; iter < maxIterations; iter++) {
            for (let i = 0; i < n; i++) {
                for (let j = 0; j < n; j++) {
                    if (i === j) continue;

                    const highDimDist = this.euclideanDistance(
                        this.embeddings[i],
                        this.embeddings[j],
                    );
                    const lowDimDist = this.euclideanDistance(
                        reduced[i],
                        reduced[j],
                    );

                    // Attractive force for close neighbors
                    if (highDimDist < this.config.minDist * 5) {
                        const force = (highDimDist - lowDimDist) * learningRate;
                        for (let d = 0; d < targetDim; d++) {
                            const direction = reduced[j][d] - reduced[i][d];
                            reduced[i][d] += force * direction * 0.1;
                        }
                    }

                    // Repulsive force for distant points
                    if (lowDimDist < this.config.minDist && lowDimDist > 0) {
                        const force = (this.config.minDist - lowDimDist) *
                            learningRate;
                        for (let d = 0; d < targetDim; d++) {
                            const direction = (reduced[i][d] - reduced[j][d]) /
                                lowDimDist;
                            reduced[i][d] += force * direction * 0.05;
                        }
                    }
                }
            }
        }

        return reduced;
    }

    private performReductionOn(embeddings: number[][]): number[][] {
        // Simple projection for new data
        return embeddings.map((embedding) => {
            const reduced = Array(this.config.nComponents).fill(0);
            for (
                let i = 0;
                i < Math.min(embedding.length, this.config.nComponents);
                i++
            ) {
                reduced[i] = embedding[i];
            }
            return reduced;
        });
    }

    private euclideanDistance(vec1: number[], vec2: number[]): number {
        let sum = 0;
        for (let i = 0; i < Math.min(vec1.length, vec2.length); i++) {
            sum += Math.pow(vec1[i] - vec2[i], 2);
        }
        return Math.sqrt(sum);
    }
}

// Apply UMAP-like reduction to embedding points
export function applyDimensionReduction(
    points: EmbeddingPoint[],
    config: UMAPConfig = {
        nNeighbors: 15,
        minDist: 0.1,
        metric: "euclidean",
        nComponents: 2,
        randomState: 42,
    },
): EmbeddingPoint[] {
    if (points.length === 0) return [];

    // Extract high-dimensional embeddings from metadata
    const highDimEmbeddings = points.map((point) =>
        point.metadata?.embedding ||
        Array(128).fill(0).map(() => Math.random() - 0.5)
    );

    // Apply dimension reduction
    const reducer = new SimpleDimensionReducer(config);
    reducer.fit(highDimEmbeddings);
    const reducedEmbeddings = reducer.getReduced();

    // Update points with reduced coordinates
    const updatedPoints = points.map((point, index) => {
        const reduced = reducedEmbeddings[index];
        return {
            ...point,
            x: reduced[0] * 100,
            y: reduced[1] * 100,
            z: reduced.length > 2 ? reduced[2] * 100 : point.z,
        };
    });

    return updatedPoints;
}

// Create animated sequence of dimension reduction steps
export function createAnimatedReduction(
    points: EmbeddingPoint[],
    config: UMAPConfig,
    steps: number = 50,
): EmbeddingPoint[][] {
    const sequence: EmbeddingPoint[][] = [];

    if (points.length === 0) return sequence;

    // Start with original positions
    sequence.push([...points]);

    // Extract high-dimensional embeddings
    const highDimEmbeddings = points.map((point) =>
        point.metadata?.embedding ||
        Array(128).fill(0).map(() => Math.random() - 0.5)
    );

    const reducer = new SimpleDimensionReducer(config);
    const n = highDimEmbeddings.length;
    const targetDim = config.nComponents;

    // Initialize positions
    let currentPositions = points.map(
        (point) => [point.x, point.y, point.z || 0]
    );

    // Target positions (after full reduction)
    reducer.fit(highDimEmbeddings);
    const finalPositions = reducer.getReduced();

    // Create interpolated steps
    for (let step = 1; step <= steps; step++) {
        const t = step / steps;
        const interpolatedPoints = points.map((point, index) => {
            const current = currentPositions[index];
            const final = finalPositions[index];

            return {
                ...point,
                x: current[0] + (final[0] * 100 - current[0]) * t,
                y: current[1] + (final[1] * 100 - current[1]) * t,
                z: current[2] + ((final[2] || 0) * 100 - current[2]) * t,
            };
        });

        sequence.push(interpolatedPoints);
    }

    return sequence;
}

// Calculate preservation metrics
export function calculatePreservationMetrics(
    originalPoints: EmbeddingPoint[],
    reducedPoints: EmbeddingPoint[],
): {
    stressValue: number;
    correlationCoefficient: number;
    neighborhoodPreservation: number;
} {
    if (
        originalPoints.length !== reducedPoints.length ||
        originalPoints.length === 0
    ) {
        return {
            stressValue: 1.0,
            correlationCoefficient: 0,
            neighborhoodPreservation: 0,
        };
    }

    const n = originalPoints.length;

    // Calculate pairwise distances in both spaces
    const originalDistances: number[] = [];
    const reducedDistances: number[] = [];

    for (let i = 0; i < n; i++) {
        for (let j = i + 1; j < n; j++) {
            // Original distance (using first 3 dimensions as proxy)
            const origDist = Math.sqrt(
                Math.pow(originalPoints[i].x - originalPoints[j].x, 2) +
                    Math.pow(originalPoints[i].y - originalPoints[j].y, 2) +
                    Math.pow(
                        (originalPoints[i].z || 0) - (originalPoints[j].z || 0),
                        2,
                    ),
            );

            // Reduced distance
            const redDist = Math.sqrt(
                Math.pow(reducedPoints[i].x - reducedPoints[j].x, 2) +
                    Math.pow(reducedPoints[i].y - reducedPoints[j].y, 2) +
                    Math.pow(
                        (reducedPoints[i].z || 0) - (reducedPoints[j].z || 0),
                        2,
                    ),
            );

            originalDistances.push(origDist);
            reducedDistances.push(redDist);
        }
    }

    // Calculate stress value (Kruskal's stress)
    const meanOrigDist = originalDistances.reduce((sum, d) => sum + d, 0) /
        originalDistances.length;
    let numerator = 0;
    let denominator = 0;

    for (let i = 0; i < originalDistances.length; i++) {
        numerator += Math.pow(originalDistances[i] - reducedDistances[i], 2);
        denominator += Math.pow(originalDistances[i] - meanOrigDist, 2);
    }

    const stressValue = Math.sqrt(numerator / denominator);

    // Calculate correlation coefficient
    const correlationCoefficient = calculateCorrelation(
        originalDistances,
        reducedDistances,
    );

    // Simple neighborhood preservation (simplified)
    const neighborhoodPreservation = Math.max(0, 1 - stressValue);

    return {
        stressValue: Math.min(1, stressValue),
        correlationCoefficient: Math.max(
            -1,
            Math.min(1, correlationCoefficient),
        ),
        neighborhoodPreservation,
    };
}

function calculateCorrelation(x: number[], y: number[]): number {
    if (x.length !== y.length || x.length === 0) return 0;

    const n = x.length;
    const meanX = x.reduce((sum, val) => sum + val, 0) / n;
    const meanY = y.reduce((sum, val) => sum + val, 0) / n;

    let numerator = 0;
    let denomX = 0;
    let denomY = 0;

    for (let i = 0; i < n; i++) {
        const dx = x[i] - meanX;
        const dy = y[i] - meanY;
        numerator += dx * dy;
        denomX += dx * dx;
        denomY += dy * dy;
    }

    const denominator = Math.sqrt(denomX * denomY);
    return denominator === 0 ? 0 : numerator / denominator;
}
