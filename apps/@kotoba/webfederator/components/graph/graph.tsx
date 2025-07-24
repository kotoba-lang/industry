import type { ReactNode } from 'react';

export type GraphProps = {
  /**
   * sets the component children.
   */
  children?: ReactNode;
};

export function Graph({ children }: GraphProps) {
  return (
    <div>
      {children}
    </div>
  );
}
