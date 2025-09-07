import type { Metadata } from 'next'
import '../styles/globals.css'
import '../styles/spreadsheet.css'

export const metadata: Metadata = {
  title: 'Kagami Editor - Next.js Example',
  description: 'ProseMirror + Event Sourcing + Spreadsheet functionality example',
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="ja">
      <body>{children}</body>
    </html>
  )
} 