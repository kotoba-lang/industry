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
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <div className="bg-white shadow-sm border-b border-gray-200">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between items-center py-4">
            <h1 className="text-2xl font-bold text-gray-900">Kotoba Platform</h1>
            <div className="flex items-center space-x-4">
              <span className="text-sm text-gray-500">BitDev Components Integration</span>
              {astData.nodes.length > 0 && (
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800">
                  AST Data Available ({astData.nodes.length} nodes)
                </span>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Main Content */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Editor Panel */}
          <div className="bg-white shadow-sm rounded-lg border border-gray-200">
            <div className="px-6 py-4 border-b border-gray-200">
              <h2 className="text-lg font-semibold text-gray-900">Editor</h2>
              <p className="text-sm text-gray-500 mt-1">ProseMirror-based rich text editor</p>
            </div>
            <div className="p-6">
              <Editor 
                initialFileName="kotoba-document.md"
                onASTUpdate={updateASTData}
              />
            </div>
          </div>
          
          {/* Graph Panel */}
          <div className="bg-white shadow-sm rounded-lg border border-gray-200">
            <div className="px-6 py-4 border-b border-gray-200">
              <h2 className="text-lg font-semibold text-gray-900">Graph</h2>
              <p className="text-sm text-gray-500 mt-1">Cytoscape-based AST visualization</p>
            </div>
            <div className="p-6">
              <Graph 
                astData={astData}
                initialGraphType="ast"
                height="500px"
              />
            </div>
          </div>
        </div>
      </div>

      {/* Footer */}
      <div className="bg-white border-t border-gray-200">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4">
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
