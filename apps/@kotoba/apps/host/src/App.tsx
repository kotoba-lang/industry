import React, { Suspense } from 'react';

const Editor = React.lazy(() => import('editor/Editor'));
const Graph = React.lazy(() => import('graph/Graph'));

function App() {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px', padding: '20px', height: '100vh' }}>
      <div style={{ border: '1px solid #ccc', borderRadius: '8px', padding: '10px' }}>
        <h2>Editor</h2>
        <Suspense fallback={<div>Loading Editor...</div>}>
          <Editor />
        </Suspense>
      </div>
      <div style={{ border: '1px solid #ccc', borderRadius: '8px', padding: '10px' }}>
        <h2>Graph</h2>
        <Suspense fallback={<div>Loading Graph...</div>}>
          <Graph />
        </Suspense>
      </div>
    </div>
  );
}

export default App;
