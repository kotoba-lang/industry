function dotProduct(v1: number[], v2: number[]): number {
  return v1.reduce((sum, val, i) => sum + val * v2[i], 0)
}

// Generate random vector for a word
function generateWordVector(word: string): number[] {
  // Use word as seed for pseudo-random generation
  const seed = word.split("").reduce((acc, char) => acc + char.charCodeAt(0), 0)
  const vector = []
  for (let i = 0; i < 5; i++) {
    // Generate values between 0 and 1 using a simple PRNG
    const val = ((seed * (i + 1) * 13) % 100) / 100
    vector.push(val)
  }
  return vector
}

// Word vector cache (dynamically generated for Jung's 100 words)
const wordVectors: Record<string, number[]> = {
  // Stimulus words (first 20 from Jung's list)
  head: generateWordVector("head"),
  green: generateWordVector("green"),
  water: generateWordVector("water"),
  "to sing": generateWordVector("to sing"),
  dead: generateWordVector("dead"),
  long: generateWordVector("long"),
  ship: generateWordVector("ship"),
  "to pay": generateWordVector("to pay"),
  window: generateWordVector("window"),
  friendly: generateWordVector("friendly"),
  "to cook": generateWordVector("to cook"),
  "to ask": generateWordVector("to ask"),
  cold: generateWordVector("cold"),
  stem: generateWordVector("stem"),
  "to dance": generateWordVector("to dance"),
  village: generateWordVector("village"),
  lake: generateWordVector("lake"),
  sick: generateWordVector("sick"),
  pride: generateWordVector("pride"),
  ink: generateWordVector("ink"),

  // Response categories
  "Normal Response": generateWordVector("Normal Response"),
  "Complex Indicator": generateWordVector("Complex Indicator"),
  "Delayed Reaction": generateWordVector("Delayed Reaction"),
  "Emotional Response": generateWordVector("Emotional Response"),

  // Complex indicators
  "Prolonged Reaction Time": generateWordVector("Prolonged Reaction Time"),
  "Unusual Response": generateWordVector("Unusual Response"),
  "Personal Reference": generateWordVector("Personal Reference"),
}

// Add more words from Jung's list as needed
// This function will dynamically add vectors for words not in the cache
function getWordVector(word: string): number[] {
  if (!wordVectors[word]) {
    wordVectors[word] = generateWordVector(word)
  }
  return wordVectors[word]
}

// Reaction time simulation
const reactionTimes: Record<string, Record<string, number>> = {
  head: { "Normal Response": 0.8, "Complex Indicator": 1.5, "Delayed Reaction": 2.3 },
  green: { "Normal Response": 0.7, "Emotional Response": 1.2, "Unusual Response": 1.8 },
  water: { "Normal Response": 0.6, "Personal Reference": 1.4, "Prolonged Reaction Time": 2.5 },
}

// Skin potential change simulation
const skinPotentialChanges: Record<string, Record<string, number>> = {
  head: { "Normal Response": 0.2, "Complex Indicator": 1.5, "Delayed Reaction": 0.8 },
  green: { "Normal Response": 0.1, "Emotional Response": 1.8, "Unusual Response": 1.2 },
  water: { "Normal Response": 0.3, "Personal Reference": 1.6, "Prolonged Reaction Time": 0.9 },
}

// Facial emotion score simulation
const facialEmotionScores: Record<string, Record<string, number>> = {
  head: { "Normal Response": 0.1, "Complex Indicator": 0.8, "Delayed Reaction": 0.4 },
  green: { "Normal Response": 0.2, "Emotional Response": 0.9, "Unusual Response": 0.6 },
  water: { "Normal Response": 0.1, "Personal Reference": 0.7, "Prolonged Reaction Time": 0.5 },
}

// Calculate integrated probability
export function calculateIntegratedProbability(
  inputWord: string,
  outputWord: string,
  alpha = 1.0, // Reaction speed factor weight
  gamma = 1.0, // Skin potential weight
  eta = 1.0, // Facial emotion analysis weight
  lambda = 1.0, // Skin potential scale adjustment
  epsilon = 0.1, // Zero division prevention constant
): number {
  // Get word vectors (dynamically if needed)
  const inputVector = getWordVector(inputWord)
  const outputVector = getWordVector(outputWord)

  // Semantic similarity (vector dot product)
  const semanticSimilarity = Math.exp(dotProduct(inputVector, outputVector))

  // Generate pseudo-random reaction time if not in the dataset
  const reactionTime = reactionTimes[inputWord]?.[outputWord] || 0.5 + Math.random() * 2.0 // Random between 0.5 and 2.5 seconds
  const reactionFactor = Math.pow(1 / (reactionTime + epsilon), alpha)

  // Generate pseudo-random skin potential if not in the dataset
  const skinPotential = skinPotentialChanges[inputWord]?.[outputWord] || Math.random() * 2.0 // Random between 0 and 2.0
  const skinFactor = Math.exp((gamma * skinPotential) / lambda)

  // Generate pseudo-random emotion score if not in the dataset
  const emotionScore = facialEmotionScores[inputWord]?.[outputWord] || Math.random() * 1.0 // Random between 0 and 1.0
  const emotionFactor = Math.exp(eta * emotionScore)

  // Integrated probability (pre-normalization)
  return semanticSimilarity * reactionFactor * skinFactor * emotionFactor
}

// Calculate and normalize probability distribution for a list of words
export function calculateProbabilityDistribution(
  inputWord: string,
  outputWords: string[],
  alpha = 1.0,
  gamma = 1.0,
  eta = 1.0,
  lambda = 1.0,
): Record<string, number> {
  // Calculate probability for each output word
  const unnormalizedProbs = outputWords.map((word) => ({
    word,
    prob: calculateIntegratedProbability(inputWord, word, alpha, gamma, eta, lambda),
  }))

  // Calculate total for normalization
  const totalProb = unnormalizedProbs.reduce((sum, item) => sum + item.prob, 0)

  // Return normalized probability distribution
  return unnormalizedProbs.reduce(
    (dist, item) => {
      dist[item.word] = item.prob / totalProb
      return dist
    },
    {} as Record<string, number>,
  )
}

// Integrated model parameter type definition
export interface IntegratedModelParams {
  alpha: number // Reaction speed factor weight
  gamma: number // Skin potential weight
  eta: number // Facial emotion analysis weight
  lambda: number // Skin potential scale adjustment
}

// Default parameters
export const defaultModelParams: IntegratedModelParams = {
  alpha: 1.0,
  gamma: 1.0,
  eta: 1.0,
  lambda: 1.0,
}

