export interface Vector {
    word: string;
    vector: [number, number, number];
    reactionTime: number;
    associationCount: number;
}

export interface Association {
    source: string;
    target: string;
    strength: number;
}
