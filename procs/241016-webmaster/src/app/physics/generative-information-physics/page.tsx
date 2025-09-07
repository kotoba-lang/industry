import { Metadata } from 'next'
import { GenerativeInformationPhysicsVisualization } from '@/components/physics/GenerativeInformationPhysicsVisualization'

export const metadata: Metadata = {
  title: 'Generative Information Physics: Unified Theory of Cosmic Evolution through Information Processing',
  description: 'From Big Bang to Present: Interactive physics experience visualizing information density, computational complexity, and emergence of consciousness',
  keywords: ['Generative Information Physics', 'Cosmology', 'Information Theory', 'Quantum Gravity', 'Consciousness', 'σ₈ problem', 'H₀ problem'],
  openGraph: {
    title: 'Generative Information Physics: Unified Theory of Cosmic Evolution',
    description: 'From Big Bang to Consciousness Emergence - Interactive visualization of revolutionary physics theory',
    images: ['/assets/posts/spirit-in-physics/cover.jpg'],
  },
  twitter: {
    card: 'summary_large_image',
    title: 'Generative Information Physics: Unified Theory of Cosmic Evolution',
    description: 'From Big Bang to Consciousness Emergence - Interactive visualization of revolutionary physics theory',
  },
}

export default function GenerativeInformationPhysicsPage() {
  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 via-blue-900 to-indigo-900">
      <div className="container mx-auto px-4 py-8">
        <header className="text-center mb-8">
          <h1 className="text-4xl md:text-6xl font-bold text-white mb-4 bg-gradient-to-r from-cyan-400 to-blue-400 bg-clip-text text-transparent">
            Generative Information Physics
          </h1>
          <p className="text-xl md:text-2xl text-gray-300 mb-2">
            Unified Theory of Cosmic Evolution through Information Processing
          </p>
          <p className="text-lg text-gray-400">
            From Big Bang to Present: Information Density, Computational Complexity & Consciousness Emergence
          </p>
        </header>
        
        <GenerativeInformationPhysicsVisualization />
        
        <footer className="mt-12 text-center text-gray-400">
          <p className="mb-4">
            This interactive visualization presents a new paradigm for understanding cosmic evolution from an information processing perspective.
          </p>
          <div className="flex flex-wrap justify-center gap-4 text-sm">
            <a 
              href="https://arxiv.org/abs/2501.XXXXX" 
              className="text-cyan-400 hover:text-cyan-300 transition-colors"
              target="_blank"
              rel="noopener noreferrer"
            >
              📖 arXiv Paper
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
              🔬 Related Articles
            </a>
          </div>
        </footer>
      </div>
    </div>
  )
} 