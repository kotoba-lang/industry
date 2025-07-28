const jungStimulusWords = [
  "head",
  "green",
  "water",
  "to sing",
  "dead",
  "long",
  "ship",
  "to pay",
  "window",
  "friendly",
  "to cook",
  "to ask",
  "cold",
  "stem",
  "to dance",
  "village",
  "lake",
  "sick",
  "pride",
  "to cook",
  "ink",
  "angry",
  "needle",
  "to swim",
  "voyage",
  "blue",
  "lamp",
  "to sin",
  "bread",
  "rich",
  "tree",
  "to prick",
  "pity",
  "yellow",
  "mountain",
  "to die",
  "salt",
  "new",
  "custom",
  "to pray",
  "money",
  "foolish",
  "pamphlet",
  "despise",
  "finger",
  "expensive",
  "bird",
  "to fall",
  "book",
  "unjust",
  "frog",
  "to part",
  "hunger",
  "white",
  "child",
  "to take care",
  "lead pencil",
  "sad",
  "plum",
  "to marry",
  "house",
  "dear",
  "glass",
  "to quarrel",
  "fur",
  "big",
  "carrot",
  "to paint",
  "part",
  "old",
  "flower",
  "to beat",
  "box",
  "wild",
  "family",
  "to wash",
  "cow",
  "friend",
  "luck",
  "lie",
  "deportment",
  "narrow",
  "brother",
  "to fear",
  "stork",
  "false",
  "anxiety",
  "to kiss",
  "bride",
  "pure",
  "door",
  "to choose",
  "hay",
  "contented",
  "ridicule",
  "to sleep",
  "month",
  "nice",
  "woman",
  "to abuse",
]

// Jung's response categories (for the "excited" state)
const jungResponseCategories = [
  "Normal Response",
  "Complex Indicator",
  "Delayed Reaction",
  "No Response",
  "Misunderstood",
  "Repetition",
  "Assimilation",
  "Distraction",
  "Perseveration",
  "Unusual Response",
  "Emotional Response",
  "Physiological Response",
  "Stereotyped Response",
  "Clang Association",
  "Word Completion",
  "Identity",
]

// Jung's complex indicators (for the "decaying" state)
const jungComplexIndicators = [
  "Prolonged Reaction Time",
  "Failure to Respond",
  "Unusual Response",
  "Repetition of Stimulus",
  "Misunderstanding Stimulus",
  "Slip of Tongue",
  "Gesture",
  "Exclamation",
  "Translation to Foreign Language",
  "Multiple Responses",
  "Personal Reference",
  "Assimilation to Content",
  "Assimilation to Sound",
  "Reproduction Disturbance",
  "Forgetting Stimulus",
  "Forgetting Response",
]

// Field names
const fields = [
  "Spirit - Word Association Field",
  "Psychological Complex Field",
  "Unconscious Response Field",
  "Analytical Psychology Field",
  "Psychic Energy Field",
  "Stimulus-Response Field",
  "Consciousness Field",
  "Reaction Time Field",
]

// Get field name based on state
export function getFieldName(state: string): string {
  switch (state) {
    case "stable":
      return "Word Association Field"
    case "excited":
      return "Complex Indicator Field"
    case "decaying":
      return "Unconscious Response Field"
    default:
      return "Psychological Field"
  }
}

// Get particle name based on index and state
export function getParticleName(index: number, state: string): string {
  // Use different particle sets based on state
  let particleSet: string[]

  switch (state) {
    case "stable":
      particleSet = jungStimulusWords
      break
    case "excited":
      particleSet = jungResponseCategories
      break
    case "decaying":
      // Add "Emerging" or "Dissolving" prefix for decaying state
      particleSet = jungComplexIndicators.map((p) => `${Math.random() > 0.5 ? "Emerging" : "Dissolving"} ${p}`)
      break
    default:
      particleSet = jungStimulusWords
  }

  // Cycle through the index to get name
  return particleSet[index % particleSet.length]
}

// Get interaction name
export function getInteractionName(source: string, target: string, strength: number): string {
  // Determine interaction type based on strength
  let interactionType: string

  if (strength < 1.5) {
    interactionType = "Weak Association"
  } else if (strength < 2.5) {
    interactionType = "Strong Association"
  } else {
    interactionType = "Complex Indicator"
  }

  return `${interactionType} (${strength.toFixed(2)})`
}

