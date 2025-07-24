declare module '@kotoba/components/editor' {
  export interface EditorProps {
    initialFileName?: string;
    initialContent?: string;
    onASTUpdate?: (astData: any) => void;
  }
  
  export function Editor(props: EditorProps): JSX.Element;
}

declare module '@kotoba/components/graph' {
  export interface ASTNode {
    id: string;
    label: string;
    type: string;
    group: string;
    level: number;
  }

  export interface ASTEdge {
    source: string;
    target: string;
    weight: number;
  }

  export interface GraphProps {
    astData?: { nodes: ASTNode[]; edges: ASTEdge[] };
    initialGraphType?: 'ast' | 'network' | 'hierarchy' | 'circular';
    height?: string;
  }
  
  export function Graph(props: GraphProps): JSX.Element;
} 