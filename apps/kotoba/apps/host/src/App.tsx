import React, { useState } from 'react'
import { Editor } from '@junkawasaki/kotoba.components.editor'
import { Graph, GraphEditor } from '@junkawasaki/kotoba.components.graph'
import { ThemeProvider } from './contexts/ThemeContext'
import './App.css'

function App() {
  const [astData, setAstData] = useState<any>(null)
  const [activeTab, setActiveTab] = useState<'text' | 'graph'>('text')

  const handleASTUpdate = (data: any) => {
    setAstData(data)
    console.log('AST data updated in App:', data)
  }

  const handleGraphUpdate = (data: any) => {
    setAstData(data)
    console.log('Graph data updated in App:', data)
  }

  return (
    <ThemeProvider>
      <div className="min-h-screen bg-gray-50 dark:bg-gray-900">
        {/* Header */}
        <header className="bg-white dark:bg-gray-800 shadow-sm border-b border-gray-200 dark:border-gray-700">
          <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
            <div className="flex justify-between items-center py-4">
              <div className="flex items-center space-x-4">
                <h1 className="text-2xl font-bold text-gray-900 dark:text-gray-100">Kotoba</h1>
                <div className="flex space-x-1">
                  <button
                    onClick={() => setActiveTab('text')}
                    className={`px-3 py-2 text-sm font-medium rounded-md transition-colors duration-200 ${
                      activeTab === 'text'
                        ? 'bg-indigo-100 dark:bg-indigo-900 text-indigo-700 dark:text-indigo-300'
                        : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-300'
                    }`}
                  >
                    Text Editor
                  </button>
                  <button
                    onClick={() => setActiveTab('graph')}
                    className={`px-3 py-2 text-sm font-medium rounded-md transition-colors duration-200 ${
                      activeTab === 'graph'
                        ? 'bg-indigo-100 dark:bg-indigo-900 text-indigo-700 dark:text-indigo-300'
                        : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-300'
                    }`}
                  >
                    Graph Editor
                  </button>
                </div>
              </div>
            </div>
          </div>
        </header>

        {/* Main Content */}
        <main className="flex-1">
          {activeTab === 'text' ? (
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 p-6">
              <div className="bg-white dark:bg-gray-800 shadow-sm rounded-lg border border-gray-200 dark:border-gray-700">
                <Editor onASTUpdate={handleASTUpdate} />
              </div>
              <div className="bg-white dark:bg-gray-800 shadow-sm rounded-lg border border-gray-200 dark:border-gray-700">
                <Graph astData={astData} height="600px" />
              </div>
            </div>
          ) : (
            <div className="p-6">
              <GraphEditor 
                astData={astData} 
                onGraphUpdate={handleGraphUpdate}
                height="calc(100vh - 120px)"
              />
            </div>
          )}
        </main>
      </div>
    </ThemeProvider>
  )
}

export default App
