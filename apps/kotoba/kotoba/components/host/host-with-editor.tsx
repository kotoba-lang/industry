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

const GraphEditorFallback = () => (
  <div className="flex flex-col items-center justify-center p-8 text-center">
    <div className="bg-green-50 border border-green-200 rounded-lg p-6 max-w-md">
      <h3 className="text-lg font-semibold text-green-900 mb-2">Graph Editor Component</h3>
      <p className="text-green-700 mb-4">開発モードでは独立したアプリケーションとして動作します</p>
      <a 
        href="http://localhost:5002" 
        target="_blank" 
        rel="noopener noreferrer" 
        className="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-green-600 hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-500"
      >
        Graph Editorを独立して開く
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

export type HostWithEditorProps = {
  /**
   * エディターコンポーネント
   */
  EditorComponent?: React.ComponentType<any>;
  /**
   * グラフエディターコンポーネント
   */
  GraphEditorComponent?: React.ComponentType<any>;
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

export function HostWithEditor({ 
  EditorComponent,
  GraphEditorComponent,
  title = "Kotoba Platform with Graph Editor",
  layout = "grid",
  isDevelopment = true
}: HostWithEditorProps) {
  const [editorError, setEditorError] = useState(false);
  const [graphEditorError, setGraphEditorError] = useState(false);
  const [astData, setAstData] = useState<ASTData>({ nodes: [], edges: [] });
  const [activeTab, setActiveTab] = useState<'editor' | 'graph'>('editor');

  // 開発モードではフォールバックコンポーネントを使用
  const Editor = isDevelopment ? EditorFallback : (EditorComponent || EditorFallback);
  const GraphEditor = isDevelopment ? GraphEditorFallback : (GraphEditorComponent || GraphEditorFallback);

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
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900">
      {/* ヘッダー */}
      <header className="bg-white dark:bg-gray-800 shadow-sm border-b border-gray-200 dark:border-gray-700">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex justify-between items-center py-4">
            <div className="flex items-center space-x-3">
              <h1 className="text-2xl font-bold text-gray-900 dark:text-gray-100">{title}</h1>
              <div className="flex space-x-2">
                <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${
                  editorError 
                    ? 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200' 
                    : 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200'
                }`}>
                  Editor: {editorError ? 'Error' : 'Ready'}
                </span>
                <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${
                  graphEditorError 
                    ? 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200' 
                    : 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200'
                }`}>
                  Graph Editor: {graphEditorError ? 'Error' : 'Ready'}
                </span>
              </div>
            </div>
            <div className="text-sm text-gray-500 dark:text-gray-400">
              {isDevelopment ? 'Development Mode' : 'Production Mode'}
            </div>
          </div>
        </div>
      </header>

      {/* タブナビゲーション */}
      <div className="bg-white dark:bg-gray-800 border-b border-gray-200 dark:border-gray-700">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <nav className="flex space-x-8">
            <button
              onClick={() => setActiveTab('editor')}
              className={`py-4 px-1 border-b-2 font-medium text-sm ${
                activeTab === 'editor'
                  ? 'border-indigo-500 text-indigo-600 dark:text-indigo-400'
                  : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300 dark:text-gray-400 dark:hover:text-gray-300'
              }`}
            >
              Text Editor
            </button>
            <button
              onClick={() => setActiveTab('graph')}
              className={`py-4 px-1 border-b-2 font-medium text-sm ${
                activeTab === 'graph'
                  ? 'border-indigo-500 text-indigo-600 dark:text-indigo-400'
                  : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300 dark:text-gray-400 dark:hover:text-gray-300'
              }`}
            >
              Graph Editor
            </button>
          </nav>
        </div>
      </div>

      {/* メインコンテンツ */}
      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        {activeTab === 'editor' ? (
          /* Editor セクション */
          <div className="bg-white dark:bg-gray-800 rounded-lg shadow-sm border border-gray-200 dark:border-gray-700">
            <div className="px-6 py-4 border-b border-gray-200 dark:border-gray-700">
              <h2 className="text-lg font-medium text-gray-900 dark:text-gray-100">Text Editor</h2>
              <p className="text-sm text-gray-500 dark:text-gray-400">ProseMirror-based rich text editor with AST generation</p>
            </div>
            <div className="p-6">
              <ErrorBoundary onError={() => setEditorError(true)}>
                <Suspense fallback={
                  <div className="flex items-center justify-center p-8">
                    <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
                    <span className="ml-2 text-gray-600 dark:text-gray-400">Loading Editor...</span>
                  </div>
                }>
                  <Editor onASTUpdate={setAstData} />
                </Suspense>
              </ErrorBoundary>
            </div>
          </div>
        ) : (
          /* Graph Editor セクション */
          <div className="bg-white dark:bg-gray-800 rounded-lg shadow-sm border border-gray-200 dark:border-gray-700">
            <div className="px-6 py-4 border-b border-gray-200 dark:border-gray-700">
              <h2 className="text-lg font-medium text-gray-900 dark:text-gray-100">Graph Editor</h2>
              <p className="text-sm text-gray-500 dark:text-gray-400">Interactive graph editor with node and edge management</p>
            </div>
            <div className="p-6">
              <ErrorBoundary onError={() => setGraphEditorError(true)}>
                <Suspense fallback={
                  <div className="flex items-center justify-center p-8">
                    <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-green-600"></div>
                    <span className="ml-2 text-gray-600 dark:text-gray-400">Loading Graph Editor...</span>
                  </div>
                }>
                  <GraphEditor astData={astData} onGraphUpdate={setAstData} />
                </Suspense>
              </ErrorBoundary>
            </div>
          </div>
        )}
      </main>

      {/* フッター */}
      <footer className="bg-white dark:bg-gray-800 border-t border-gray-200 dark:border-gray-700 mt-8">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4">
          <div className="flex justify-between items-center text-sm text-gray-500 dark:text-gray-400">
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
    console.error('Error caught by boundary:', error, errorInfo);
    this.props.onError();
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex flex-col items-center justify-center p-8 text-center">
          <div className="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg p-6 max-w-md">
            <h3 className="text-lg font-semibold text-red-900 dark:text-red-100 mb-2">Component Error</h3>
            <p className="text-red-700 dark:text-red-300 mb-4">コンポーネントでエラーが発生しました</p>
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