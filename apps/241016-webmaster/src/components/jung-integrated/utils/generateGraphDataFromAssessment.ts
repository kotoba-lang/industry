import { type TestResults, type WordResponse } from "@/components/jung-word-assessment/types";
import { type TransitionState } from "@/components/kawasaki-model/utils/stateTransition";
import { type GraphData } from "@/components/kawasaki-model/utils/generateGraphData";
import { type IntegratedModelParams, defaultModelParams } from "@/components/kawasaki-model/utils/integratedModel";
import { getWordVector } from "@/components/kawasaki-model/utils/vectors";

// Define delayed response threshold (same as in test component)
const DELAYED_REACTION_THRESHOLD_MS = 2000;

/**
 * Generate graph data from word assessment test results.
 * This transforms the test responses into a network graph that can be visualized.
 */
export function generateGraphDataFromWordAssessment(
  results: TestResults,
  systemState: TransitionState,
  time: number,
  modelParams: IntegratedModelParams = defaultModelParams
): GraphData {
  // Get current effective state
  const effectiveState =
    systemState.targetState === null
      ? systemState.currentState
      : interpolateState(systemState.currentState, systemState.targetState, systemState.progress);

  // Create field node (central node)
  const fieldName = "Jung Word Association Field";
  const nodes = [{ id: "field", group: 1, name: fieldName, x: 0, y: 0, z: 0 }];
  const links: {
    source: string;
    target: string;
    strength: number;
    name: string;
  }[] = [];

  // Create nodes for each unique word (both stimulus and response)
  const uniqueWords = new Set<string>();
  
  // First, collect all unique words
  results.responses.forEach(response => {
    uniqueWords.add(response.stimulus);
    uniqueWords.add(response.response);
  });
  
  // Create node array with positions calculated based on word vectors
  Array.from(uniqueWords).forEach((word, index) => {
    // Use word vectors to determine position (5-dimensional vector projected to 3D)
    const wordVector = getWordVector(word);
    
    // Distribute in 3D space based on vector components and time
    const radius = 100 + Math.sin(time * 0.01 + index * 0.1) * 20;
    const phi = Math.acos(1 - (2 * (index + 0.5)) / uniqueWords.size);
    const theta = Math.PI * (1 + Math.sqrt(5)) * (index + 0.5);
    const rotatedTheta = theta + time * 0.005;

    // Convert to Cartesian coordinates
    const x = radius * Math.sin(phi) * Math.cos(rotatedTheta) + wordVector[0] * 20;
    const y = radius * Math.sin(phi) * Math.sin(rotatedTheta) + wordVector[1] * 20;
    const z = radius * Math.cos(phi) + wordVector[2] * 20;
    
    // Determine node group based on word type
    const nodeGroup = isFrequentWord(word, results.responses) ? 3 : 2;
    
    nodes.push({
      id: `word_${word}`,
      group: nodeGroup,
      name: word,
      x,
      y,
      z,
      ...(word.length % 2 === 0 
        ? { color: '#ffffff', darkColor: '#ffffff', lightColor: '#333333' } 
        : { color: '#e0e0e0', darkColor: '#e0e0e0', lightColor: '#555555' }),
    });

    // Create link between field and word node
    const strength = getStrengthBasedOnState(effectiveState, "field-word");
    links.push({
      source: "field",
      target: `word_${word}`,
      strength,
      name: `Association: ${fieldName} → ${word}`,
    });
  });

  // Create links between stimulus and response words
  results.responses.forEach(response => {
    const { stimulus, response: responseWord, isDelayed } = response;
    
    // Calculate link strength based on reaction time
    const baseStrength = getStrengthFromReactionTime(response.reactionTimeMs);
    const stateStrength = getStrengthBasedOnState(effectiveState, "word-word");
    let strength = baseStrength * stateStrength;
    
    // Emphasize delayed responses (potential complexes)
    if (isDelayed) {
      strength *= modelParams.gamma; // Amplify by gamma parameter
    }
    
    // Connect stimulus to response
    links.push({
      source: `word_${stimulus}`,
      target: `word_${responseWord}`,
      strength,
      name: `${stimulus} → ${responseWord} (${response.reactionTimeMs}ms)${isDelayed ? ' [DELAYED]' : ''}`,
    });
  });

  return { nodes, links };
}

// Helper functions

// Check if a word appears frequently in responses
function isFrequentWord(word: string, responses: WordResponse[]): boolean {
  const stimulusCount = responses.filter(r => r.stimulus === word).length;
  const responseCount = responses.filter(r => r.response === word).length;
  return (stimulusCount + responseCount) > 1;
}

// Calculate strength based on reaction time
function getStrengthFromReactionTime(reactionTimeMs: number): number {
  // Inverse relationship between reaction time and strength
  // Faster reactions (low ms) = stronger connections
  const strengthFactor = DELAYED_REACTION_THRESHOLD_MS / Math.max(100, reactionTimeMs);
  
  // Normalize to 0.5-5 range
  return 0.5 + Math.min(4.5, strengthFactor * 2);
}

// Interpolate between states
function interpolateState(fromState: string, toState: string, progress: number): string {
  return `${fromState}->${toState}:${progress.toFixed(2)}`;
}

// Get strength based on system state
function getStrengthBasedOnState(
  state: string, 
  interactionType: "field-word" | "word-word"
): number {
  // For transition states
  if (state.includes("->")) {
    const [fromState, rest] = state.split("->");
    const [toState, progressStr] = rest.split(":");
    const progress = Number.parseFloat(progressStr);

    // Calculate and interpolate strengths for two states
    const fromStrength = getBaseStrength(fromState, interactionType);
    const toStrength = getBaseStrength(toState, interactionType);

    // Linear interpolation
    const baseStrength = fromStrength * (1 - progress) + toStrength * progress;

    // Add random element
    const randomFactor = 0.5 + Math.random() * 0.5; // Range 0.5-1.0
    return baseStrength * randomFactor;
  }

  // For normal states
  const baseStrength = getBaseStrength(state, interactionType);
  const randomFactor = 0.5 + Math.random() * 0.5;
  return baseStrength * randomFactor;
}

// Get base strength for a state
function getBaseStrength(
  state: string, 
  interactionType: "field-word" | "word-word"
): number {
  switch (state) {
    case "excited":
      return interactionType === "field-word" ? 3 : 2;
    case "decaying":
      return interactionType === "field-word" ? 2 : 1;
    case "stable":
    default:
      return interactionType === "field-word" ? 1 : 1;
  }
} 