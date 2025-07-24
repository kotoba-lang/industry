import React, { Suspense, useEffect, useState } from 'react';

// 開発用のフォールバックコンポーネント
const EditorFallback = () => (
  <div style={{ padding: '20px', textAlign: 'center' }}>
    <h3>Editor Component</h3>
    <p>開発モードでは独立したアプリケーションとして動作します</p>
    <a href="http://localhost:5001" target="_blank" rel="noopener noreferrer" 
       style={{ color: '#007bff', textDecoration: 'none' }}>
      Editorを独立して開く
    </a>
  </div>
);

const GraphFallback = () => (
  <div style={{ padding: '20px', textAlign: 'center' }}>
    <h3>Graph Component</h3>
    <p>開発モードでは独立したアプリケーションとして動作します</p>
    <a href="http://localhost:5002" target="_blank" rel="noopener noreferrer" 
       style={{ color: '#007bff', textDecoration: 'none' }}>
      Graphを独立して開く
    </a>
  </div>
);

// ASTデータの型定義
interface ASTNode {
  id: string
  label: string
  type: string
  group: string
  level: number
}

interface ASTEdge {
  source: string
  target: string
  weight: number
}

interface ASTData {
  nodes: ASTNode[]
  edges: ASTEdge[]
}

function App() {
  const [editorError, setEditorError] = useState(false);
  const [graphError, setGraphError] = useState(false);
  const [astData, setAstData] = useState<ASTData>({ nodes: [], edges: [] });

  // Module Federationのリモートコンポーネントを動的インポート
  const Editor = React.lazy(() => {
    console.log('Host: Attempting to load Editor...');
    return import('editor/Editor').catch(error => {
      console.error('Host: Failed to load Editor:', error);
      throw error;
    });
  });

  const Graph = React.lazy(() => {
    console.log('Host: Attempting to load Graph...');
    return import('graph/Graph').catch(error => {
      console.error('Host: Failed to load Graph:', error);
      throw error;
    });
  });

  useEffect(() => {
    console.log('Host App: Component mounted');
  }, []);

  // ASTデータを更新する関数
  const updateASTData = (newData: ASTData) => {
    console.log('Host App: AST data updated', newData);
    setAstData(newData);
  };

  // グローバルウィンドウオブジェクトにAST更新関数を公開
  useEffect(() => {
    (window as any).updateASTData = updateASTData;
    return () => {
      delete (window as any).updateASTData;
    };
  }, []);

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px', padding: '20px', height: '100vh' }}>
      <div style={{ border: '1px solid #ccc', borderRadius: '8px', padding: '10px' }}>
        <h2>Editor</h2>
        {editorError ? (
          <EditorFallback />
        ) : (
          <Suspense fallback={<div>Loading Editor...</div>}>
            <ErrorBoundary onError={() => setEditorError(true)}>
              <Editor />
            </ErrorBoundary>
          </Suspense>
        )}
      </div>
      
      <div style={{ border: '1px solid #ccc', borderRadius: '8px', padding: '10px' }}>
        <h2>Graph</h2>
        {graphError ? (
          <GraphFallback />
        ) : (
          <Suspense fallback={<div>Loading Graph...</div>}>
            <ErrorBoundary onError={() => setGraphError(true)}>
              <Graph astData={astData} />
            </ErrorBoundary>
          </Suspense>
        )}
      </div>
    </div>
  );
}

// シンプルなエラーバウンダリーコンポーネント
class ErrorBoundary extends React.Component<{ children: React.ReactNode; onError: () => void }, { hasError: boolean }> {
  constructor(props: { children: React.ReactNode; onError: () => void }) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  componentDidCatch(error: any, errorInfo: any) {
    console.error('Error caught by boundary:', error, errorInfo);
    this.props.onError();
  }

  render() {
    if (this.state.hasError) {
      return (
        <div style={{ padding: '20px', textAlign: 'center', color: '#666' }}>
          <p>コンポーネントの読み込みに失敗しました</p>
        </div>
      );
    }

    return this.props.children;
  }
}

export default App;
