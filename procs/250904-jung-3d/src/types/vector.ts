export interface Vector3D {
    x: number;
    y: number;
    z: number;
}

export interface SpiritVector {
    stimulus: string;
    response: string;
    energy: number;
    vector: [number, number, number];
    timestamp: number;
}

export interface TimeSeriesPoint {
    timestamp: number;
    energy: number;
    entropy: number;
}
