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
  /**
   * 開発モードフラグ
   */
  isDevelopment?: boolean;
};

export function Host({ 
  EditorComponent,
  GraphComponent,
  title = "Kotoba Platform",
  layout = "grid",
  isDevelopment = true
}: HostProps) {
  const [editorError, setEditorError] = useState(false);
  const [graphError, setGraphError] = useState(false);
  const [astData, setAstData] = useState<ASTData>({ nodes: [], edges: [] });

  // 開発モードではフォールバックコンポーネントを使用
  const Editor = isDevelopment ? EditorFallback : (EditorComponent || EditorFallback);
  const Graph = isDevelopment ? GraphFallback : (GraphComponent || GraphFallback);

  // グローバル関数としてAST更新関数を公開
  useEffect(() => {
    (window as any).updateASTData = (newData: ASTData) => {
      console.log('Host: Received AST data:', newData);
      setAstData(newData);
    };

    return () => {
      delete (window as any).updateASTData;
    };
  }, []);

  const getLayoutClass = () => {
    switch (layout) {
      case 'vertical':
        return 'flex flex-col space-y-4';
      case 'horizontal':
        return 'flex flex-row space-x-4';
      case 'grid':
      default:
        return 'grid grid-cols-1 lg:grid-cols-2 gap-6';
    }
  };

  return (
    <div className="min-h-screen bg-gray-50">
      {/* ヘッダー */}
      <header className="bg-white shadow-sm border-b border-gray-200">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between items-center py-4">
            <div className="flex items-center space-x-3">
              <h1 className="text-2xl font-bold text-gray-900">{title}</h1>
              <div className="flex space-x-2">
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800">
                  Editor: {editorError ? 'Error' : 'Ready'}
                </span>
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800">
                  Graph: {graphError ? 'Error' : 'Ready'}
                </span>
              </div>
            </div>
            <div className="text-sm text-gray-500">
              {isDevelopment ? 'Development Mode' : 'Production Mode'}
            </div>
          </div>
        </div>
      </header>

      {/* メインコンテンツ */}
      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className={getLayoutClass()}>
          {/* Editor セクション */}
          <div className="bg-white rounded-lg shadow-sm border border-gray-200">
            <div className="px-6 py-4 border-b border-gray-200">
              <h2 className="text-lg font-medium text-gray-900">Text Editor</h2>
              <p className="text-sm text-gray-500">ProseMirror-based rich text editor with AST generation</p>
            </div>
            <div className="p-6">
              <ErrorBoundary onError={() => setEditorError(true)}>
                <Suspense fallback={
                  <div className="flex items-center justify-center p-8">
                    <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
                    <span className="ml-2 text-gray-600">Loading Editor...</span>
                  </div>
                }>
                  <Editor />
                </Suspense>
              </ErrorBoundary>
            </div>
          </div>

          {/* Graph セクション */}
          <div className="bg-white rounded-lg shadow-sm border border-gray-200">
            <div className="px-6 py-4 border-b border-gray-200">
              <h2 className="text-lg font-medium text-gray-900">Graph Viewer</h2>
              <p className="text-sm text-gray-500">Cytoscape-based interactive graph visualization</p>
            </div>
            <div className="p-6">
              <ErrorBoundary onError={() => setGraphError(true)}>
                <Suspense fallback={
                  <div className="flex items-center justify-center p-8">
                    <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-green-600"></div>
                    <span className="ml-2 text-gray-600">Loading Graph...</span>
                  </div>
                }>
                  <Graph astData={astData} />
                </Suspense>
              </ErrorBoundary>
            </div>
          </div>
        </div>
      </main>

      {/* フッター */}
      <footer className="bg-white border-t border-gray-200 mt-8">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4">
          <div className="flex justify-between items-center text-sm text-gray-500">
            <div>
              <span className="font-medium">AST Data:</span>
              <span className="ml-2">{astData.nodes.length} nodes, {astData.edges.length} edges</span>
            </div>
            <div>
              <span className="font-medium">WebFederator Platform</span>
              <span className="ml-2">•</span>
              <span className="ml-2">Module Federation Demo</span>
            </div>
          </div>
        </div>
      </footer>
    </div>
  );
}

// エラーバウンダリーコンポーネント
class ErrorBoundary extends React.Component<{ children: React.ReactNode; onError: () => void }, { hasError: boolean }> {
  constructor(props: { children: React.ReactNode; onError: () => void }) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  componentDidCatch(error: any, errorInfo: any) {
    console.error('ErrorBoundary caught an error:', error, errorInfo);
    this.props.onError();
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex flex-col items-center justify-center p-8 text-center">
          <div className="bg-red-50 border border-red-200 rounded-lg p-6 max-w-md">
            <h3 className="text-lg font-semibold text-red-900 mb-2">Component Error</h3>
            <p className="text-red-700 mb-4">コンポーネントの読み込み中にエラーが発生しました</p>
            <button
              onClick={() => this.setState({ hasError: false })}
              className="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-red-600 hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500"
            >
              再試行
            </button>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
