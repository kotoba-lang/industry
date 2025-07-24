import React, { useEffect, useState } from 'react';
import { Editor } from '@kotoba/components/editor';
import { Graph } from '@kotoba/components/graph';
import type { ASTNode, ASTEdge } from '@kotoba/components/graph';

// ASTデータの型定義
interface ASTData {
  nodes: ASTNode[]
  edges: ASTEdge[]
}

function App() {
  const [astData, setAstData] = useState<ASTData>({ nodes: [], edges: [] });
  const [isLoaded, setIsLoaded] = useState(false);

  useEffect(() => {
    console.log('Host App: Component mounted');
    setIsLoaded(true);
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
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <div className="bg-white shadow-sm border-b border-gray-200">
        <div className="container-fluid">
          <div className="flex justify-between items-center py-4">
            <h1 className="text-2xl font-bold text-gray-900">Kotoba Platform</h1>
            <div className="flex items-center space-x-4">
              <span className="text-sm text-gray-500">BitDev Components Integration</span>
              {isLoaded && (
                <span className="badge badge-info">
                  Components Loaded
                </span>
              )}
              {astData.nodes.length > 0 && (
                <span className="badge badge-success">
                  AST Data Available ({astData.nodes.length} nodes)
                </span>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Main Content */}
      <div className="container-fluid py-6">
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Editor Panel */}
          <div className="panel">
            <div className="panel-header">
              <h2 className="text-lg font-semibold text-gray-900">Editor</h2>
              <p className="text-sm text-gray-500 mt-1">ProseMirror-based rich text editor with AST generation</p>
            </div>
            <div className="panel-body">
              <Editor 
                initialFileName="kotoba-document.md"
                onASTUpdate={updateASTData}
              />
            </div>
          </div>
          
          {/* Graph Panel */}
          <div className="panel">
            <div className="panel-header">
              <h2 className="text-lg font-semibold text-gray-900">Graph</h2>
              <p className="text-sm text-gray-500 mt-1">Cytoscape-based AST visualization with interactive features</p>
            </div>
            <div className="panel-body">
              <Graph 
                astData={astData}
                initialGraphType="ast"
                height="500px"
              />
            </div>
          </div>
        </div>

        {/* Status Panel */}
        <div className="mt-6 panel">
          <div className="panel-header">
            <h3 className="text-lg font-semibold text-gray-900">Integration Status</h3>
          </div>
          <div className="panel-body">
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
              <div className="text-center">
                <div className="text-2xl font-bold text-green-600">✓</div>
                <div className="text-sm text-gray-600">BitDev Components</div>
                <div className="text-xs text-gray-500">Successfully integrated</div>
              </div>
              <div className="text-center">
                <div className="text-2xl font-bold text-blue-600">✓</div>
                <div className="text-sm text-gray-600">Dependencies</div>
                <div className="text-xs text-gray-500">ProseMirror & Cytoscape</div>
              </div>
              <div className="text-center">
                <div className="text-2xl font-bold text-purple-600">✓</div>
                <div className="text-sm text-gray-600">Type Safety</div>
                <div className="text-xs text-gray-500">TypeScript integration</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Footer */}
      <div className="bg-white border-t border-gray-200">
        <div className="container-fluid py-4">
          <div className="flex items-center justify-between text-sm text-gray-500">
            <span>Kotoba Platform • ProseMirror + Cytoscape + BitDev Components</span>
            <span>AST Nodes: {astData.nodes.length} • Edges: {astData.edges.length}</span>
          </div>
        </div>
      </div>
    </div>
  );
}

export default App;
