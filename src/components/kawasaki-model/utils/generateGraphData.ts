import type { TransitionState } from "./stateTransition";
import {
  getFieldName,
  getInteractionName,
  getParticleName,
} from "./particleNames";
import {
  calculateProbabilityDistribution,
  defaultModelParams,
  type IntegratedModelParams,
} from "./integratedModel";

export interface GraphData {
  nodes: {
    id: string;
    group: number;
    name: string;
    x: number;
    y: number;
    z: number;
    color?: string;
    darkColor?: string;
    lightColor?: string;
    // Voice assessment properties
    reactionTime?: number;
    hasEmotionalComplex?: boolean;
    emotionIntensity?: number;
    // Parameter impact flags
    affectedByAlpha?: boolean;
    affectedByGamma?: boolean;
    affectedByLambda?: boolean;
    affectedByEta?: boolean;
    // Highlighting
    highlighted?: boolean;
  }[];
  links: {
    source: string;
    target: string;
    strength: number;
    name: string;
    latencyMs?: number;
  }[];
}

// Add cache to reuse calculations for the same time and state
let lastTime = -1;
let lastState = "";
let cachedData: GraphData | null = null;

export function generateGraphData(
  particleCount: number,
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

  // Check cache (if time and state are the same)
  const roundedTime = Math.round(time * 100) / 100;
  const stateKey = `${effectiveState}-${systemState.progress.toFixed(3)}-${
    JSON.stringify(modelParams)
  }`;
  if (roundedTime === lastTime && stateKey === lastState && cachedData) {
    return cachedData;
  }

  // Create field node
  const fieldName = getFieldName(effectiveState.split("->")[0]);
  const nodes = [{ id: "field", group: 1, name: fieldName, x: 0, y: 0, z: 0 }];
  const links = [];

  // Generate particle name list
  const particleNames: string[] = [];
  for (let i = 0; i < particleCount; i++) {
    particleNames.push(getParticleName(i, effectiveState.split("->")[0]));
  }

  // Distribute particles in 3D space
  for (let i = 0; i < particleCount; i++) {
    // Distribute evenly on a sphere (Fibonacci sphere)
    const phi = Math.acos(1 - (2 * (i + 0.5)) / particleCount);
    const theta = Math.PI * (1 + Math.sqrt(5)) * (i + 0.5);

    // Rotate based on time (smooth movement)
    const rotatedTheta = theta + time * 0.005;

    // Convert spherical coordinates to Cartesian
    const radius = 100 + Math.sin(time * 0.01 + i * 0.1) * 20;
    const x = radius * Math.sin(phi) * Math.cos(rotatedTheta);
    const y = radius * Math.sin(phi) * Math.sin(rotatedTheta);
    const z = radius * Math.cos(phi);

    // Slightly different group value for each particle
    const particleGroup = Math.random() > 0.7 ? 3 : 2;

    // Get particle name
    const particleName = particleNames[i];

    nodes.push({
      id: `particle${i}`,
      group: particleGroup,
      name: particleName,
      x,
      y,
      z,
    });
  }

  // Calculate connection strength between particles using the integrated model
  for (let i = 0; i < particleCount; i++) {
    // Current particle name
    const currentParticleName = particleNames[i];

    // Calculate probability distribution with other particles
    const otherParticleNames = particleNames.filter((_, idx) => idx !== i);
    const probDistribution = calculateProbabilityDistribution(
      currentParticleName,
      otherParticleNames,
      modelParams.alpha,
      modelParams.gamma,
      modelParams.eta,
      modelParams.lambda,
    );

    for (let j = i + 1; j < particleCount; j++) {
      // Create connection for each particle pair (i < j to avoid duplicates)
      const targetParticleName = particleNames[j];

      // Get probability from integrated model and convert to connection strength
      const probability = probDistribution[targetParticleName] || 0.1;
      // Scale probability to 0.5-5 range
      const baseStrength = 0.5 + probability * 4.5;

      // Get base strength based on state
      const stateStrength = getStrengthBasedOnState(
        effectiveState,
        "particle-particle",
      );

      // Calculate final strength (combination of state strength and probability strength)
      const strength = stateStrength * baseStrength;

      // Adjust strength based on distance (weaker connections for distant particles)
      const p1 = nodes[i + 1]; // +1 for field node
      const p2 = nodes[j + 1];
      const dx = (p1.x || 0) - (p2.x || 0);
      const dy = (p1.y || 0) - (p2.y || 0);
      const dz = (p1.z || 0) - (p2.z || 0);
      const distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

      // Attenuate strength based on distance (weaker for more distant)
      const distanceFactor = Math.max(0.1, 1 - distance / 300);
      const adjustedStrength = strength * distanceFactor;

      links.push({
        source: `particle${i}`,
        target: `particle${j}`,
        strength: adjustedStrength,
        name: getInteractionName(
          nodes.find((n) => n.id === `particle${i}`)?.name || "",
          nodes.find((n) => n.id === `particle${j}`)?.name || "",
          adjustedStrength,
        ),
      });
    }
  }

  // Cache results
  cachedData = { nodes, links };
  lastTime = roundedTime;
  lastState = stateKey;

  return cachedData;
}

// Unchanged functions
function interpolateState(
  fromState: string,
  toState: string,
  progress: number,
): string {
  return `${fromState}->${toState}:${progress.toFixed(2)}`;
}

function getStrengthBasedOnState(
  state: string,
  interactionType: "field-particle" | "particle-particle",
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
    const randomFactor = 0.5 + Math.random(); // Range 0.5-1.5
    return baseStrength * randomFactor;
  }

  // For normal states
  const baseStrength = getBaseStrength(state, interactionType);
  const randomFactor = 0.5 + Math.random();
  return baseStrength * randomFactor;
}

// Helper function to get base strength
function getBaseStrength(
  state: string,
  interactionType: "field-particle" | "particle-particle",
): number {
  switch (state) {
    case "excited":
      return interactionType === "field-particle" ? 3 : 2;
    case "decaying":
      return interactionType === "field-particle" ? 2 : 1;
    case "stable":
    default:
      return interactionType === "field-particle" ? 1 : 1;
  }
}
