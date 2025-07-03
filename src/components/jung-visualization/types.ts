export interface EmbeddingPoint {
    id: string;
    x: number;
    y: number;
    z?: number;
    word: string;
    type: "stimulus" | "response" | "document";
    reactionTime?: number;
    isDelayed?: boolean;
    associationStrength?: number;
    emotionalIntensity?: number;
    cluster?: number;
    category?: string;
    metadata?: Record<string, any>;
}

export interface EmbeddingVisualizationData {
    points: EmbeddingPoint[];
    connections: AssociationConnection[];
    clusters: Cluster[];
    evaluationMetrics: EvaluationMetrics;
    timestamp: number;
}

export interface AssociationConnection {
    source: string;
    target: string;
    strength: number;
    type: "semantic" | "temporal" | "emotional";
    reactionTime?: number;
    confidence?: number;
    metadata?: Record<string, any>;
}

export interface Cluster {
    id: string;
    centroid: { x: number; y: number; z?: number };
    points: string[];
    label: string;
    color: string;
    size: number;
    description?: string;
}

export interface EvaluationMetrics {
    semanticCoherence: number;
    temporalConsistency: number;
    emotionalResonance: number;
    clusterQuality: number;
    retrievalAccuracy: number;
    generationFluency: number;
    overallScore: number;
}

export interface AnimationState {
    frame: number;
    totalFrames: number;
    isPlaying: boolean;
    speed: number;
    currentPhase:
        | "initialization"
        | "clustering"
        | "association"
        | "evaluation"
        | "complete";
}

export interface VisualizationConfig {
    width: number;
    height: number;
    pointSize: number;
    connectionOpacity: number;
    clusterOpacity: number;
    animationDuration: number;
    showLabels: boolean;
    showConnections: boolean;
    showClusters: boolean;
    colorScheme: "default" | "jung" | "emotion" | "temporal";
    interactionMode: "pan" | "zoom" | "select" | "hover";
}

export interface JungTestData {
    responses: Array<{
        stimulus: string;
        response: string;
        reactionTime: number;
        isDelayed: boolean;
    }>;
    averageReactionTime: number;
    delayedResponseCount: number;
    testType: "word" | "voice" | "integrated";
    timestamp: number;
}

export interface UMAPConfig {
    nNeighbors: number;
    minDist: number;
    metric: "euclidean" | "cosine" | "manhattan";
    nComponents: number;
    randomState: number;
}

export interface EmbeddingAnalysis {
    originalDimension: number;
    reducedDimension: number;
    preservedVariance: number;
    silhouetteScore: number;
    clusteringMetrics: {
        nClusters: number;
        withinClusterSumOfSquares: number;
        betweenClusterSumOfSquares: number;
    };
}
