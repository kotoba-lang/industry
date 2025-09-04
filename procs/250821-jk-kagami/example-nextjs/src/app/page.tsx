'use client'

import { useEffect, useState } from 'react'
import dynamic from 'next/dynamic'

// Kagami Editor関連のコンポーネントを動的インポート（SSR無効）
const KagamiEditorComponent = dynamic(() => import('../components/KagamiEditorComponent'), {
  ssr: false,
  loading: () => <div>Loading Kagami Editor...</div>
})

// テーマトグルボタンを動的インポート
const ThemeToggle = dynamic(() => import('../components/ThemeToggle'), {
  ssr: false,
  loading: () => null
})

export default function HomePage() {
  const [mounted, setMounted] = useState(false)

  useEffect(() => {
    setMounted(true)
  }, [])

  if (!mounted) {
    return <div>Loading...</div>
  }

  return (
    <div className="container">
      {/* テーマトグルボタン */}
      <ThemeToggle />
      
      <h1>🪞 Kagami Editor Example (Next.js)</h1>
      
      <div className="demo-section">
        <h2>リッチテキストエディタ (ProseMirror)</h2>
        <KagamiEditorComponent />
      </div>

      <div className="footer">
        <p>Kagami Editor - ProseMirror + Event Sourcing + Spreadsheet (Next.js版)</p>
        <p>右上のボタンでダークモード/ライトモードを切り替えできます</p>
      </div>
    </div>
  )
} 