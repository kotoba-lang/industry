import { type TestResults, type WordResponse } from "@/components/jung-voice-assessment/types";
import { type TransitionState } from "@/components/kawasaki-model/utils/stateTransition";
import { type GraphData } from "@/components/kawasaki-model/utils/generateGraphData";
import { type IntegratedModelParams, defaultModelParams } from "@/components/kawasaki-model/utils/integratedModel";
import { getWordVector } from "@/components/kawasaki-model/utils/vectors";

// Define delayed response threshold (same as in test component)
const DELAYED_REACTION_THRESHOLD_MS = 2000;

/**
 * Generate graph data from voice assessment test results.
 * Transforms voice response data into a network graph for visualization.
 * Uses a different visual pattern than word assessment to distinguish the data.
 */
export function generateGraphDataFromVoiceAssessment(
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
  const fieldName = "Jung Voice Association Field";
  const nodes = [{ id: "voice_field", group: 4, name: fieldName, x: 0, y: 0, z: 0 }];
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
    uniqueWords.add(response.word);
  });
  
  // Create node array with positions calculated based on word vectors
  Array.from(uniqueWords).forEach((word, index) => {
    // Use word vectors to determine position (5-dimensional vector projected to 3D)
    const wordVector = getWordVector(word);
    
    // Distribute in 3D space based on vector components and time
    // Using a different pattern for voice assessment
    const radius = 120 + Math.sin(time * 0.02 + index * 0.2) * 25;
    const phi = Math.acos(1 - (2 * (index + 0.5)) / uniqueWords.size);
    const theta = Math.PI * (1 + Math.sqrt(3)) * (index + 0.5);
    const rotatedTheta = theta + time * 0.008;

    // Convert to Cartesian coordinates with slight offset from word assessment
    const x = radius * Math.sin(phi) * Math.cos(rotatedTheta) + wordVector[0] * 25;
    const y = radius * Math.sin(phi) * Math.sin(rotatedTheta) + wordVector[1] * 25;
    const z = radius * Math.cos(phi) + wordVector[2] * 25 + 50; // Offset in z axis
    
    // Determine node group based on word type - using groups 5 and 6 for voice
    const nodeGroup = isFrequentWord(word, results.responses) ? 6 : 5;
    
    nodes.push({
      id: `voice_${word}`,
      group: nodeGroup,
      name: word,
      x,
      y,
      z,
    });

    // Create link between field and word node
    const strength = getStrengthBasedOnState(effectiveState, "field-word");
    links.push({
      source: "voice_field",
      target: `voice_${word}`,
      strength,
      name: `Voice Association: ${fieldName} → ${word}`,
    });
  });

  // Create links between stimulus and response words
  results.responses.forEach(response => {
    const { word, isDelayed } = response;
    
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
      source: `voice_${word}`,
      target: `voice_${word}`,
      strength,
      name: `Voice: ${word} → ${word} (${response.reactionTimeMs}ms)${isDelayed ? ' [DELAYED]' : ''}`,
    });
  });

  return { nodes, links };
}

// Helper functions

// Check if a word appears frequently in responses
function isFrequentWord(word: string, responses: WordResponse[]): boolean {
  const wordCount = responses.filter(r => r.word === word).length;
  return wordCount > 1;
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
      return interactionType === "field-word" ? 3.5 : 2.5; // Slightly stronger for voice
    case "decaying":
      return interactionType === "field-word" ? 2.5 : 1.5;
    case "stable":
    default:
      return interactionType === "field-word" ? 1.5 : 1.2;
  }
} 