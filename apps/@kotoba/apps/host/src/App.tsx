import React, { Suspense, useEffect, useState } from 'react';

// 開発用のフォールバックコンポーネント
const EditorFallback = () => (
  <div style={{ padding: '20px', textAlign: 'center' }}>
    <h3>Editor Component</h3>
    <p>開発モードでは独立したアプリケーションとして動作します</p>
    <a href="http://localhost:5001" target="_blank" rel="noopener noreferrer" 
       style={{ color: '#007bff', textDecoration: 'none' }}>
      Editor App を開く →
    </a>
  </div>
);

const GraphFallback = () => (
  <div style={{ padding: '20px', textAlign: 'center' }}>
    <h3>Graph Component</h3>
    <p>開発モードでは独立したアプリケーションとして動作します</p>
    <a href="http://localhost:5002" target="_blank" rel="noopener noreferrer" 
       style={{ color: '#007bff', textDecoration: 'none' }}>
      Graph App を開く →
    </a>
  </div>
);

// Module Federation コンポーネント（本番環境用）
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

function App() {
  const [editorError, setEditorError] = useState(false);
  const [graphError, setGraphError] = useState(false);

  useEffect(() => {
    console.log('Host: App component mounted.');
  }, []);

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px', padding: '20px', height: '100vh' }}>
      <div style={{ border: '1px solid #ccc', borderRadius: '8px', padding: '10px' }}>
        <h2>Editor</h2>
        <Suspense fallback={<div>Loading Editor...</div>}>
          {editorError ? (
            <EditorFallback />
          ) : (
            <ErrorBoundary onError={() => setEditorError(true)}>
              <Editor />
            </ErrorBoundary>
          )}
        </Suspense>
      </div>
      <div style={{ border: '1px solid #ccc', borderRadius: '8px', padding: '10px' }}>
        <h2>Graph</h2>
        <Suspense fallback={<div>Loading Graph...</div>}>
          {graphError ? (
            <GraphFallback />
          ) : (
            <ErrorBoundary onError={() => setGraphError(true)}>
              <Graph />
            </ErrorBoundary>
          )}
        </Suspense>
      </div>
    </div>
  );
}

// エラーバウンダリーコンポーネント
class ErrorBoundary extends React.Component<
  { children: React.ReactNode; onError: () => void },
  { hasError: boolean }
> {
  constructor(props: { children: React.ReactNode; onError: () => void }) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  componentDidCatch() {
    this.props.onError();
  }

  render() {
    if (this.state.hasError) {
      return null; // フォールバックコンポーネントが表示される
    }
    return this.props.children;
  }
}

export default App;
