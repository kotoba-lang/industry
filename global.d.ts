import { vi } from 'vitest';

declare global {
  // Vitest global
  const vi: typeof import('vitest')['vi'];

  // Add DOM Element style access for tests
  interface Element {
    style?: {
      width?: string;
    };
  }
}

export {}; 