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

// MDXファイルの型宣言
declare module '*.mdx' {
  import { MDXProps } from 'mdx/types'
  export default function MDXComponent(props: MDXProps): JSX.Element
}

export {}; 