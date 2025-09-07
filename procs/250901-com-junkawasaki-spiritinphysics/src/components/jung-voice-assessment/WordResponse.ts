import { WordResponse } from "./schema";

// Extend the WordResponse interface with runtime-only properties
export interface WordResponseWithExtras extends WordResponse {
  isDelayed: boolean;
}

/**
 * Helper function to cast WordResponse to WordResponseWithExtras
 */
export function withExtras(response: WordResponse): WordResponseWithExtras {
  return response as WordResponseWithExtras;
}
