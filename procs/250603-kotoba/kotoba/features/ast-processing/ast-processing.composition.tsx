import React, { useState } from 'react';
import { ASTProcessor } from './ast-processor';

export const ASTProcessingDemo = () => {
  const [inputText, setInputText] = useState(`# Kotoba Platform
## 機能
- テキストエディター
- グラフエディター
- AST生成
  - マークダウン解析
  - 構造化データ生成
## 技術スタック
- React
- TypeScript
- ProseMirror
- Cytoscape.js`);

  const [astData, setAstData] = useState<any>(null);

  const processText = () => {
    try {
      const result = ASTProcessor.processText(inputText);
      setAstData(result);
    } catch (error) {
      console.error('AST processing error:', error);
    }
  };

  return (
    <div className="p-6 max-w-4xl mx-auto">
      <h2 className="text-2xl font-bold mb-4">AST Processing Demo</h2>
      
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div>
          <h3 className="text-lg font-semibold mb-2">Input Text</h3>
          <textarea
            value={inputText}
            onChange={(e) => setInputText(e.target.value)}
            className="w-full h-64 p-3 border border-gray-300 rounded-md font-mono text-sm"
            placeholder="Enter markdown text here..."
          />
          <button
            onClick={processText}
            className="mt-2 px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700"
          >
            Process Text
          </button>
        </div>
        
        <div>
          <h3 className="text-lg font-semibold mb-2">Generated AST Data</h3>
          <div className="bg-gray-100 p-3 rounded-md h-64 overflow-auto">
            <pre className="text-xs">
              {astData ? JSON.stringify(astData, null, 2) : 'No AST data generated yet'}
            </pre>
          </div>
        </div>
      </div>
      
      {astData && (
        <div className="mt-6">
          <h3 className="text-lg font-semibold mb-2">AST Statistics</h3>
          <div className="grid grid-cols-3 gap-4">
            <div className="bg-blue-50 p-3 rounded-md">
              <div className="text-2xl font-bold text-blue-600">{astData.nodes?.length || 0}</div>
              <div className="text-sm text-blue-800">Nodes</div>
            </div>
            <div className="bg-green-50 p-3 rounded-md">
              <div className="text-2xl font-bold text-green-600">{astData.edges?.length || 0}</div>
              <div className="text-sm text-green-800">Edges</div>
            </div>
            <div className="bg-purple-50 p-3 rounded-md">
              <div className="text-2xl font-bold text-purple-600">
                {ASTProcessor.validateASTData(astData) ? 'Valid' : 'Invalid'}
              </div>
              <div className="text-sm text-purple-800">Validation</div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}; 