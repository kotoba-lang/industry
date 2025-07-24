import type { ReactNode } from 'react';

export type EditorProps = {
  /**
   * sets the component children.
   */
  children?: ReactNode;
};

export function Editor({ children }: EditorProps) {
  return (
    <div>
      {children}
    </div>
  );
}
