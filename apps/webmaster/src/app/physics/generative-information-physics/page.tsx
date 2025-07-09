import { Metadata } from 'next'
import { GenerativeInformationPhysicsVisualization } from '@/components/physics/GenerativeInformationPhysicsVisualization'

export const metadata: Metadata = {
  title: '生成情報物理学: 情報処理を通じた宇宙進化の統一理論',
  description: 'BigBangから現在まで：情報密度・計算複雑性・意識の創発を可視化したインタラクティブな物理学体験',
  keywords: ['生成情報物理学', '宇宙論', '情報理論', '量子重力', '意識', 'σ₈問題', 'H₀問題'],
  openGraph: {
    title: '生成情報物理学: 宇宙進化の統一理論',
    description: 'BigBangから意識の創発まで - 革新的な物理理論のインタラクティブ可視化',
    images: ['/assets/posts/spirit-in-physics/cover.jpg'],
  },
  twitter: {
    card: 'summary_large_image',
    title: '生成情報物理学: 宇宙進化の統一理論',
    description: 'BigBangから意識の創発まで - 革新的な物理理論のインタラクティブ可視化',
  },
}

export default function GenerativeInformationPhysicsPage() {
  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 via-blue-900 to-indigo-900">
      <div className="container mx-auto px-4 py-8">
        <header className="text-center mb-8">
          <h1 className="text-4xl md:text-6xl font-bold text-white mb-4 bg-gradient-to-r from-cyan-400 to-blue-400 bg-clip-text text-transparent">
            生成情報物理学
          </h1>
          <p className="text-xl md:text-2xl text-gray-300 mb-2">
            情報処理を通じた宇宙進化の統一理論
          </p>
          <p className="text-lg text-gray-400">
            BigBangから現在まで: 情報密度・計算複雑性・意識の創発
          </p>
        </header>
        
        <GenerativeInformationPhysicsVisualization />
        
        <footer className="mt-12 text-center text-gray-400">
          <p className="mb-4">
            このインタラクティブ可視化は、宇宙の進化を情報処理の観点から理解する新しいパラダイムを提示します。
          </p>
          <div className="flex flex-wrap justify-center gap-4 text-sm">
            <a 
              href="https://arxiv.org/abs/2501.XXXXX" 
              className="text-cyan-400 hover:text-cyan-300 transition-colors"
              target="_blank"
              rel="noopener noreferrer"
            >
              📖 arXiv論文
            </a>
            <a 
              href="https://github.com/junkawasaki/generative-physics-cosmology" 
              className="text-cyan-400 hover:text-cyan-300 transition-colors"
              target="_blank"
              rel="noopener noreferrer"
            >
              💻 GitHub
            </a>
            <a 
              href="/posts/spirit-in-physics" 
              className="text-cyan-400 hover:text-cyan-300 transition-colors"
            >
              🔬 関連記事
            </a>
          </div>
        </footer>
      </div>
    </div>
  )
} 