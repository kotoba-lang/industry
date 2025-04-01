/**
 * Calculate dot product of two vectors
 */
export function dotProduct(v1: number[], v2: number[]): number {
  return v1.reduce((sum, val, i) => sum + val * v2[i], 0);
}

/**
 * Generate a deterministic vector for a word (used for consistent positioning)
 */
export function generateWordVector(word: string): number[] {
  // Use word as seed for pseudo-random generation
  const seed = word.split("").reduce((acc, char) => acc + char.charCodeAt(0), 0);
  const vector = [];
  for (let i = 0; i < 5; i++) {
    // Generate values between -1 and 1 using a simple PRNG
    const val = ((seed * (i + 1) * 13) % 200) / 100 - 1;
    vector.push(val);
  }
  return vector;
}

// Word vector cache
const wordVectors: Record<string, number[]> = {};

/**
 * Get or generate a word vector
 */
export function getWordVector(word: string): number[] {
  if (!wordVectors[word]) {
    wordVectors[word] = generateWordVector(word);
  }
  return wordVectors[word];
}

/**
 * Calculate cosine similarity between two word vectors
 */
export function calculateCosineSimilarity(word1: string, word2: string): number {
  const vector1 = getWordVector(word1);
  const vector2 = getWordVector(word2);
  
  const dotProd = dotProduct(vector1, vector2);
  const mag1 = Math.sqrt(dotProduct(vector1, vector1));
  const mag2 = Math.sqrt(dotProduct(vector2, vector2));
  
  return dotProd / (mag1 * mag2);
}

/**
 * Find nearest neighbors to a word in vector space
 */
export function findNearestNeighbors(word: string, candidates: string[], n: number = 5): string[] {
  const similarities = candidates
    .filter(w => w !== word)
    .map(candidate => ({
      word: candidate,
      similarity: calculateCosineSimilarity(word, candidate)
    }))
    .sort((a, b) => b.similarity - a.similarity);
  
  return similarities.slice(0, n).map(item => item.word);
} 