import type { ReactNode } from 'react';

export type HostProps = {
  /**
   * sets the component children.
   */
  children?: ReactNode;
};

export function Host({ children }: HostProps) {
  return (
    <div>
      {children}
    </div>
  );
}
