import React, { Suspense, useEffect } from 'react';

const Editor = React.lazy(() => {
  console.log('Host: Attempting to load Editor...');
  return import('editor/Editor');
});
const Graph = React.lazy(() => {
  console.log('Host: Attempting to load Graph...');
  return import('graph/Graph');
});

function App() {
  useEffect(() => {
    console.log('Host: App component mounted.');
  }, []);

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
