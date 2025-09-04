import {
  type TestResults,
  type WordResponse,
} from "@/components/jung-voice-assessment/types";
import { type TransitionState } from "@/components/kawasaki-model/utils/stateTransition";
import { type GraphData } from "@/components/kawasaki-model/utils/generateGraphData";
import {
  defaultModelParams,
  type IntegratedModelParams,
} from "@/components/kawasaki-model/utils/integratedModel";
import { getWordVector } from "@/components/kawasaki-model/utils/vectors";

// Define delayed response threshold (same as in test component)
const DELAYED_REACTION_THRESHOLD_MS = 2000;
// Max reaction time to consider (for normalization)
const MAX_REACTION_TIME_MS = 5000;
// Minimum reaction time to consider valid (avoid zero values)
const MIN_REACTION_TIME_MS = 100;

/**
 * Generate graph data from voice assessment test results.
 * Transforms voice response data into a network graph for visualization.
 * Uses a different visual pattern than word assessment to distinguish the data.
 */
export function generateGraphDataFromVoiceAssessment(
  results: TestResults,
  systemState: TransitionState,
  time: number,
  modelParams: IntegratedModelParams = defaultModelParams,
): GraphData {
  // Get current effective state
  const effectiveState = systemState.targetState === null
    ? systemState.currentState
    : interpolateState(
      systemState.currentState,
      systemState.targetState,
      systemState.progress,
    );

  // Create field node (central node)
  const fieldName = "Jung Voice Association Field";
  const nodes = [{
    id: "voice_field",
    group: 4,
    name: fieldName,
    x: 0,
    y: 0,
    z: 0,
  }];
  const links: {
    source: string;
    target: string;
    strength: number;
    name: string;
    latencyMs?: number;
  }[] = [];

  // Create sets for unique stimulus and response words
  const uniqueWords = new Set<string>();

  // Map to store reaction times for each word
  const wordReactionTimes = new Map<string, number[]>();

  // First, collect all unique words and their reaction times
  if (results && results.responses) {
    results.responses.forEach((response) => {
      const { stimulusWord, responseWord, reactionTimeMs } = response;

      // Ensure we only use valid reaction times
      const validReactionTime = reactionTimeMs < MIN_REACTION_TIME_MS
        ? MIN_REACTION_TIME_MS
        : reactionTimeMs;

      // Add both stimulus and response words to our sets
      uniqueWords.add(stimulusWord);
      if (responseWord && responseWord !== stimulusWord) {
        uniqueWords.add(responseWord);
      }

      // Store reaction times for positioning
      if (!wordReactionTimes.has(stimulusWord)) {
        wordReactionTimes.set(stimulusWord, []);
      }
      wordReactionTimes.get(stimulusWord)?.push(validReactionTime);

      // Also store reaction times for response words
      if (responseWord && responseWord !== stimulusWord) {
        if (!wordReactionTimes.has(responseWord)) {
          wordReactionTimes.set(responseWord, []);
        }
        wordReactionTimes.get(responseWord)?.push(validReactionTime);
      }
    });
  }

  // Create node array with positions calculated based on word vectors and reaction times
  Array.from(uniqueWords).forEach((word, index) => {
    // Use word vectors to determine position (5-dimensional vector projected to 3D)
    const wordVector = getWordVector(word);

    // Get average reaction time for this word (if available)
    const reactionTimes = wordReactionTimes.get(word) || [MIN_REACTION_TIME_MS]; // Default if no data
    const avgReactionTime = reactionTimes.reduce((sum, time) => sum + time, 0) /
      reactionTimes.length;

    // Calculate distance factor: longer reaction times = closer to center
    // Map reaction time to distance factor (0.5-1.5)
    // Normalize reaction time to 0-1 range and invert (1 - normalized value)
    const normalizedReactionTime =
      Math.min(avgReactionTime, MAX_REACTION_TIME_MS) / MAX_REACTION_TIME_MS;
    const distanceFactor = 1.5 - normalizedReactionTime; // Longer reaction time = smaller distance factor

    // Distribute in 3D space based on vector components, time, and reaction time
    // Using a different pattern for voice assessment
    const baseRadius = 120;
    const radius = baseRadius * distanceFactor +
      Math.sin(time * 0.02 + index * 0.2) * 25;
    const phi = Math.acos(1 - (2 * (index + 0.5)) / uniqueWords.size);
    const theta = Math.PI * (1 + Math.sqrt(3)) * (index + 0.5);
    const rotatedTheta = theta + time * 0.008;

    // Convert to Cartesian coordinates with slight offset from word assessment
    const x = radius * Math.sin(phi) * Math.cos(rotatedTheta) +
      wordVector[0] * 25;
    const y = radius * Math.sin(phi) * Math.sin(rotatedTheta) +
      wordVector[1] * 25;
    const z = radius * Math.cos(phi) + wordVector[2] * 25 + 50; // Offset in z axis

    // Determine node group based on word type and reaction time
    // Group 7: Delayed responses (potential complexes)
    // Group 6: Frequent words
    // Group 5: Regular words
    const isDelayed = avgReactionTime > DELAYED_REACTION_THRESHOLD_MS;
    const nodeGroup = isDelayed
      ? 7
      : (isFrequentWord(word, results.responses) ? 6 : 5);

    // Note: In a real implementation, actual skin conductance (GSR) data would be collected
    // For visualization purposes only: flag words with long reaction times as potential
    // indicators of emotional complexes (Jung's theory)
    const hasEmotionalComplex = avgReactionTime > DELAYED_REACTION_THRESHOLD_MS;

    // Calculate emotion intensity factor based solely on word vector properties
    // In a real implementation, this would come from facial emotion analysis
    const vectorMagnitude = Math.sqrt(
      wordVector.reduce((sum, component) => sum + component * component, 0),
    );
    const emotionIntensity = Math.min(
      1.0,
      vectorMagnitude * modelParams.eta * 0.2,
    );

    nodes.push(
      {
        id: `voice_${word}`,
        group: nodeGroup,
        name: `${word} (${Math.round(avgReactionTime)}ms)`,
        x,
        y,
        z,
        // Add properties for parameter impact visualization
        reactionTime: avgReactionTime,
        // We don't collect skin potential data from voice assessment
        // Instead use reaction time threshold to indicate potential complexes
        hasEmotionalComplex,
        emotionIntensity: emotionIntensity,
      } as GraphData["nodes"][0],
    );

    // Create link between field and word node
    const strength = getStrengthBasedOnState(effectiveState, "field-word") *
      (isDelayed ? 1.5 : 1.0); // Stronger connections for delayed responses
    links.push({
      source: "voice_field",
      target: `voice_${word}`,
      strength,
      name: `Voice Association: ${fieldName} → ${word} (${
        Math.round(avgReactionTime)
      }ms)`,
      latencyMs: Math.round(avgReactionTime),
    });
  });

  // Create links between stimulus and response words
  if (results && results.responses) {
    results.responses.forEach((response) => {
      const { stimulusWord, responseWord, reactionTimeMs } = response;

      // Skip if response word is empty or same as stimulus
      if (!responseWord || responseWord === stimulusWord) return;

      // Ensure we only use valid reaction times
      const validReactionTime = reactionTimeMs < MIN_REACTION_TIME_MS
        ? MIN_REACTION_TIME_MS
        : reactionTimeMs;
      const isDelayed = validReactionTime > DELAYED_REACTION_THRESHOLD_MS;

      // Calculate link strength based on reaction time - now longer time means stronger connection
      const baseStrength = getStrengthFromReactionTime(validReactionTime);
      const stateStrength = getStrengthBasedOnState(
        effectiveState,
        "word-word",
      );
      let strength = baseStrength * stateStrength;

      // Emphasize delayed responses (potential complexes)
      if (isDelayed) {
        strength *= modelParams.gamma; // Amplify by gamma parameter
      }

      // Connect stimulus to response
      links.push({
        source: `voice_${stimulusWord}`,
        target: `voice_${responseWord}`,
        strength,
        name:
          `Voice: ${stimulusWord} → ${responseWord} (${validReactionTime}ms)${
            isDelayed ? " [DELAYED]" : ""
          }`,
        latencyMs: validReactionTime,
      });
    });
  }

  return { nodes, links };
}

// Helper functions

// Check if a word appears frequently in responses
function isFrequentWord(word: string, responses: WordResponse[]): boolean {
  if (!responses) return false;

  const wordCount =
    responses.filter((r) => r.stimulusWord === word || r.responseWord === word)
      .length;
  return wordCount > 1;
}

// Calculate strength based on reaction time
function getStrengthFromReactionTime(reactionTimeMs: number): number {
  // Direct relationship between reaction time and strength
  // Longer reactions (high ms) = stronger connections (Jung's theory of complexes)
  // Normalize to 0.5-5 range with a cap at MAX_REACTION_TIME_MS
  const normalizedTime = Math.min(reactionTimeMs, MAX_REACTION_TIME_MS) /
    MAX_REACTION_TIME_MS;

  // Scale to the desired range (0.5-5)
  return 0.5 + normalizedTime * 4.5;
}

// Interpolate between states
function interpolateState(
  fromState: string,
  toState: string,
  progress: number,
): string {
  return `${fromState}->${toState}:${progress.toFixed(2)}`;
}

// Get strength based on system state
function getStrengthBasedOnState(
  state: string,
  interactionType: "field-word" | "word-word",
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
  interactionType: "field-word" | "word-word",
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
