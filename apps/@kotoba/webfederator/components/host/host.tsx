import React, { Suspense, useEffect, useState } from 'react';

// 開発用のフォールバックコンポーネント
const EditorFallback = () => (
  <div className="flex flex-col items-center justify-center p-8 text-center">
    <div className="bg-blue-50 border border-blue-200 rounded-lg p-6 max-w-md">
      <h3 className="text-lg font-semibold text-blue-900 mb-2">Editor Component</h3>
      <p className="text-blue-700 mb-4">開発モードでは独立したアプリケーションとして動作します</p>
      <a 
        href="http://localhost:5001" 
        target="_blank" 
        rel="noopener noreferrer" 
        className="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
      >
        Editorを独立して開く
      </a>
    </div>
  </div>
);

const GraphFallback = () => (
  <div className="flex flex-col items-center justify-center p-8 text-center">
    <div className="bg-green-50 border border-green-200 rounded-lg p-6 max-w-md">
      <h3 className="text-lg font-semibold text-green-900 mb-2">Graph Component</h3>
      <p className="text-green-700 mb-4">開発モードでは独立したアプリケーションとして動作します</p>
      <a 
        href="http://localhost:5002" 
        target="_blank" 
        rel="noopener noreferrer" 
        className="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-green-600 hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-500"
      >
        Graphを独立して開く
      </a>
    </div>
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

export type HostProps = {
  /**
   * エディターコンポーネント
   */
  EditorComponent?: React.ComponentType<any>;
  /**
   * グラフコンポーネント
   */
  GraphComponent?: React.ComponentType<any>;
  /**
   * アプリケーションタイトル
   */
  title?: string;
  /**
   * レイアウトタイプ
   */
  layout?: 'grid' | 'vertical' | 'horizontal';
};

export function Host({ 
  EditorComponent,
  GraphComponent,
  title = "Kotoba Platform",
  layout = "grid"
}: HostProps) {
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
    console.log('Host Component: Component mounted');
  }, []);

  // ASTデータを更新する関数
  const updateASTData = (newData: ASTData) => {
    console.log('Host Component: AST data updated', newData);
    setAstData(newData);
  };

  // グローバルウィンドウオブジェクトにAST更新関数を公開
  useEffect(() => {
    (window as any).updateASTData = updateASTData;
    return () => {
      delete (window as any).updateASTData;
    };
  }, []);

  // 使用するコンポーネントを決定
  const EditorToUse = EditorComponent || Editor;
  const GraphToUse = GraphComponent || Graph;

  // レイアウトクラスを決定
  const getLayoutClass = () => {
    switch (layout) {
      case 'vertical':
        return 'grid grid-cols-1 gap-6';
      case 'horizontal':
        return 'grid grid-cols-2 gap-6';
      case 'grid':
      default:
        return 'grid grid-cols-1 lg:grid-cols-2 gap-6';
    }
  };

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <div className="bg-white shadow-sm border-b border-gray-200">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between items-center py-4">
            <h1 className="text-2xl font-bold text-gray-900">{title}</h1>
            <div className="flex items-center space-x-4">
              <span className="text-sm text-gray-500">Module Federation Demo</span>
              {astData.nodes.length > 0 && (
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800">
                  AST Data Available
                </span>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Main Content */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
        <div className={getLayoutClass()}>
          {/* Editor Panel */}
          <div className="bg-white shadow-sm rounded-lg border border-gray-200">
            <div className="px-6 py-4 border-b border-gray-200">
              <h2 className="text-lg font-semibold text-gray-900">Editor</h2>
            </div>
            <div className="p-6">
              {editorError ? (
                <EditorFallback />
              ) : (
                <Suspense fallback={
                  <div className="flex items-center justify-center p-8">
                    <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-600"></div>
                    <span className="ml-3 text-gray-600">Loading Editor...</span>
                  </div>
                }>
                  <ErrorBoundary onError={() => setEditorError(true)}>
                    <EditorToUse onASTUpdate={updateASTData} />
                  </ErrorBoundary>
                </Suspense>
              )}
            </div>
          </div>
          
          {/* Graph Panel */}
          <div className="bg-white shadow-sm rounded-lg border border-gray-200">
            <div className="px-6 py-4 border-b border-gray-200">
              <h2 className="text-lg font-semibold text-gray-900">Graph</h2>
            </div>
            <div className="p-6">
              {graphError ? (
                <GraphFallback />
              ) : (
                <Suspense fallback={
                  <div className="flex items-center justify-center p-8">
                    <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-green-600"></div>
                    <span className="ml-3 text-gray-600">Loading Graph...</span>
                  </div>
                }>
                  <ErrorBoundary onError={() => setGraphError(true)}>
                    <GraphToUse astData={astData} />
                  </ErrorBoundary>
                </Suspense>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Footer */}
      <div className="bg-white border-t border-gray-200">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4">
          <div className="flex items-center justify-between text-sm text-gray-500">
            <span>Kotoba Platform • ProseMirror + Cytoscape + Module Federation</span>
            <span>AST Nodes: {astData.nodes.length} • Edges: {astData.edges.length}</span>
          </div>
        </div>
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
        <div className="flex flex-col items-center justify-center p-8 text-center">
          <div className="bg-red-50 border border-red-200 rounded-lg p-6 max-w-md">
            <h3 className="text-lg font-semibold text-red-900 mb-2">エラーが発生しました</h3>
            <p className="text-red-700">コンポーネントの読み込みに失敗しました</p>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
