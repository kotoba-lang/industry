import { z } from 'zod';

// Schema for a single word response
export const WordResponseSchema = z.object({
  stimulus: z.string(),
  response: z.string(),
  reactionTimeMs: z.number(),
  isDelayed: z.boolean(),
});

// Schema for the complete test results
export const TestResultsSchema = z.object({
  responses: z.array(WordResponseSchema),
  averageReactionTimeMs: z.number(),
  delayedResponseCount: z.number(),
  completedAt: z.date(),
});

export type WordResponse = z.infer<typeof WordResponseSchema>;
export type TestResults = z.infer<typeof TestResultsSchema>;

// Categories for potential complexes based on Jung's observations
export type ComplexCategory = {
  name: string;
  description: string;
  relatedWords: string[];
};

export const complexCategories: ComplexCategory[] = [
  {
    name: 'Family/Relationship',
    description: 'Potential complexes related to family dynamics or relationships',
    relatedWords: ['mother', 'father', 'family', 'child', 'to marry', 'friend', 'friendly', 'to quarrel', 'to kiss']
  },
  {
    name: 'Emotional',
    description: 'Emotional reactions that may indicate hidden feelings',
    relatedWords: ['happy', 'sad', 'angry', 'anxious', 'contented', 'to fear', 'happiness', 'despise', 'dear', 'ridicule']
  },
  {
    name: 'Ethical/Moral',
    description: 'Complexes related to moral or ethical dilemmas',
    relatedWords: ['to sin', 'to pray', 'false', 'unjust', 'rich', 'to pay', 'expensive', 'pride']
  },
  {
    name: 'Self-concept',
    description: 'Issues related to self-image and identity',
    relatedWords: ['to choose', 'pride', 'head', 'finger', 'clean', 'sick', 'new', 'to fall']
  }
];

export interface NodeAttributes {
  color?: string;
  darkColor?: string;
  lightColor?: string;
  x?: number;
  y?: number;
  z?: number;
} 