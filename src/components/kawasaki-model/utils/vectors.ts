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
 * A utility for generating word vectors for the Jung word association tests.
 * This uses a simple deterministic approach to generate vectors based on word characteristics.
 */

const VECTOR_DIMENSION = 5;

/**
 * Generate a pseudo-semantic vector for a word.
 * This is a simple deterministic implementation to create reproducible vectors.
 * In a production environment, this should be replaced with actual word embeddings.
 */
export function getWordVector(word: string): number[] {
  if (!word) return Array(VECTOR_DIMENSION).fill(0);
  
  // Create a simple but consistent vector based on the word
  const vector = Array(VECTOR_DIMENSION).fill(0);
  
  // Use character codes to generate vector components
  for (let i = 0; i < word.length; i++) {
    const charCode = word.charCodeAt(i);
    const vectorIndex = i % VECTOR_DIMENSION;
    vector[vectorIndex] += charCode / 100; // Scale down to have reasonable values
  }
  
  // Normalize the vector
  const magnitude = Math.sqrt(vector.reduce((sum, val) => sum + val * val, 0));
  return vector.map(val => magnitude ? val / magnitude : 0);
}

/**
 * Calculate cosine similarity between two word vectors.
 */
export function calculateSimilarity(vector1: number[], vector2: number[]): number {
  if (vector1.length !== vector2.length) {
    throw new Error('Vectors must have the same dimensions');
  }
  
  // Calculate dot product
  const dotProduct = vector1.reduce((sum, val, i) => sum + val * vector2[i], 0);
  
  // Calculate magnitudes
  const magnitude1 = Math.sqrt(vector1.reduce((sum, val) => sum + val * val, 0));
  const magnitude2 = Math.sqrt(vector2.reduce((sum, val) => sum + val * val, 0));
  
  // Avoid division by zero
  if (magnitude1 === 0 || magnitude2 === 0) return 0;
  
  // Return cosine similarity
  return dotProduct / (magnitude1 * magnitude2);
}

/**
 * Find the closest word from a list by vector similarity.
 */
export function findClosestWord(targetWord: string, wordList: string[]): string {
  if (wordList.length === 0) return '';
  
  const targetVector = getWordVector(targetWord);
  let maxSimilarity = -1;
  let closestWord = '';
  
  for (const word of wordList) {
    const similarity = calculateSimilarity(targetVector, getWordVector(word));
    if (similarity > maxSimilarity) {
      maxSimilarity = similarity;
      closestWord = word;
    }
  }
  
  return closestWord;
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