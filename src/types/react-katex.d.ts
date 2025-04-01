declare module 'react-katex' {
  import * as React from 'react';

  interface KaTeXProps {
    children?: React.ReactNode;
    math?: string;
    block?: boolean;
    errorColor?: string;
    renderError?: (error: Error | string) => React.ReactNode;
    settings?: {
      maxSize?: number;
      maxExpand?: number;
      strict?: boolean | string;
      trust?: boolean | ((context: { command: string; url: string; protocol: string }) => boolean);
    };
  }

  export const InlineMath: React.FC<KaTeXProps>;
  export const BlockMath: React.FC<KaTeXProps>;
} 