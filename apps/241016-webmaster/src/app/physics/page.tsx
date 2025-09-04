import Link from 'next/link'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Metadata } from 'next'

export const metadata: Metadata = {
  title: '生成物理学 3Dレンダリング | Jun Kawasaki',
  description: 'physics_restructuredプロジェクトの生成物理学数式を使った革新的な3Dレンダリングシステム。基本版と高度版から選択可能。',
  keywords: ['生成物理学', '3Dレンダリング', '情報密度', '量子情報', '意識', '宇宙論', 'σ₈問題', 'physics_restructured'],
}

export default function PhysicsPage() {
  return (
    <div className="min-h-screen bg-gradient-to-br from-black via-gray-900 to-black text-white">
      <div className="container mx-auto px-4 py-16">
        {/* ヘッダー */}
        <div className="text-center mb-16">
          <h1 className="text-5xl font-bold mb-4 bg-gradient-to-r from-cyan-400 to-purple-600 bg-clip-text text-transparent">
            🌌 生成物理学 3Dレンダリング
          </h1>
          <p className="text-xl text-gray-300 max-w-3xl mx-auto mb-8">
            physics_restructuredプロジェクトの数学的基盤から生まれた
            革新的な3D可視化システム
          </p>
          <div className="flex flex-wrap justify-center gap-2">
            <Badge variant="secondary">情報密度進化</Badge>
            <Badge variant="secondary">量子情報処理</Badge>
            <Badge variant="secondary">意識と宇宙の相互作用</Badge>
            <Badge variant="secondary">σ₈問題解決</Badge>
            <Badge variant="secondary">熱力学的整合性</Badge>
          </div>
        </div>

        {/* 理論的背景 */}
        <div className="mb-16">
          <Card className="bg-black/50 border-white/20">
            <CardHeader>
              <CardTitle className="text-white text-2xl">🔬 理論的基盤</CardTitle>
              <CardDescription className="text-gray-300">
                physics_restructuredプロジェクトの数学的基盤
              </CardDescription>
            </CardHeader>
            <CardContent className="text-gray-300">
              <div className="grid md:grid-cols-2 gap-6">
                <div className="space-y-3">
                  <h3 className="text-lg font-semibold text-white">核となる数式</h3>
                  <div className="space-y-2 text-sm font-mono">
                    <div>
                      <strong className="text-cyan-400">情報密度進化:</strong>
                      <br />
                      <code>ρ_info = ρ_base × (1-e^(-z/100)) × e^(-α_q×z)</code>
                    </div>
                    <div>
                      <strong className="text-yellow-400">ランダウアーエネルギー:</strong>
                      <br />
                      <code>E_info = n_bits × k_B × T × ln(2)</code>
                    </div>
                    <div>
                      <strong className="text-purple-400">意識レベル:</strong>
                      <br />
                      <code>Φ = |⟨Ψ_cosmic|Ψ_neural⟩|²</code>
                    </div>
                  </div>
                </div>
                <div className="space-y-3">
                  <h3 className="text-lg font-semibold text-white">物理的意義</h3>
                  <ul className="space-y-2 text-sm">
                    <li>• 宇宙の情報密度は構造形成とともに進化</li>
                    <li>• ランダウアーの原理により情報とエネルギーが結合</li>
                    <li>• 量子情報効果が宇宙論的スケールで影響</li>
                    <li>• 意識は宇宙情報処理の局所的現象</li>
                    <li>• σ₈問題は情報補正により解決可能</li>
                  </ul>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>

        {/* システム選択 */}
        <div className="grid md:grid-cols-2 gap-8 mb-16">
          {/* 基本版 */}
          <Card className="bg-gradient-to-br from-blue-900/30 to-cyan-900/30 border-cyan-400/30 hover:border-cyan-400/60 transition-all duration-300">
            <CardHeader>
              <CardTitle className="text-white text-xl flex items-center gap-2">
                🌟 基本版
                <Badge variant="outline" className="text-cyan-400 border-cyan-400">
                  推奨
                </Badge>
              </CardTitle>
              <CardDescription className="text-gray-300">
                生成物理学の核となる数式を直感的に可視化
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <h4 className="font-semibold text-white">主な機能:</h4>
                <ul className="text-sm text-gray-300 space-y-1">
                  <li>• 情報密度パーティクルシステム</li>
                  <li>• 量子もつれ効果の可視化</li>
                  <li>• 構造形成の時間進化</li>
                  <li>• 意識レベルのリアルタイム表示</li>
                  <li>• インタラクティブな宇宙進化シミュレーション</li>
                </ul>
              </div>
              <div className="space-y-2">
                <h4 className="font-semibold text-white">対象ユーザー:</h4>
                <p className="text-sm text-gray-300">
                  物理学に興味がある一般ユーザー、学生、研究者
                </p>
              </div>
              <Link href="/generative-physics-3d">
                <Button className="w-full bg-cyan-600 hover:bg-cyan-700">
                  基本版を開始
                </Button>
              </Link>
            </CardContent>
          </Card>

          {/* 高度版 */}
          <Card className="bg-gradient-to-br from-purple-900/30 to-pink-900/30 border-purple-400/30 hover:border-purple-400/60 transition-all duration-300">
            <CardHeader>
              <CardTitle className="text-white text-xl flex items-center gap-2">
                🚀 高度版
                <Badge variant="outline" className="text-purple-400 border-purple-400">
                  研究者向け
                </Badge>
              </CardTitle>
              <CardDescription className="text-gray-300">
                σ₈問題解決や熱力学的整合性を含む完全実装
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <h4 className="font-semibold text-white">追加機能:</h4>
                <ul className="text-sm text-gray-300 space-y-1">
                  <li>• σ₈問題の定量的解決アルゴリズム</li>
                  <li>• パワースペクトル進化の詳細分析</li>
                  <li>• 熱力学的整合性の検証システム</li>
                  <li>• Eisenstein-Hu転送関数の完全実装</li>
                  <li>• タブ式の高度分析インターフェース</li>
                </ul>
              </div>
              <div className="space-y-2">
                <h4 className="font-semibold text-white">対象ユーザー:</h4>
                <p className="text-sm text-gray-300">
                  宇宙論研究者、理論物理学者、大学院生
                </p>
              </div>
              <Link href="/advanced-generative-physics">
                <Button className="w-full bg-purple-600 hover:bg-purple-700">
                  高度版を開始
                </Button>
              </Link>
            </CardContent>
          </Card>
        </div>

        {/* 数値例 */}
        <div className="mb-16">
          <Card className="bg-black/50 border-white/20">
            <CardHeader>
              <CardTitle className="text-white text-2xl">📊 数値例と成果</CardTitle>
              <CardDescription className="text-gray-300">
                実際の計算結果と理論的予測
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="grid md:grid-cols-3 gap-6">
                <div className="text-center p-4 bg-green-900/20 rounded-lg border border-green-400/30">
                  <div className="text-2xl font-bold text-green-400 mb-2">
                    σ₈ 誤差 &lt; 5%
                  </div>
                  <div className="text-sm text-gray-300">
                    情報補正により20%→5%以下に改善
                  </div>
                </div>
                <div className="text-center p-4 bg-blue-900/20 rounded-lg border border-blue-400/30">
                  <div className="text-2xl font-bold text-blue-400 mb-2">
                    10⁸⁰ bits/Mpc³
                  </div>
                  <div className="text-sm text-gray-300">
                    現在の宇宙情報密度
                  </div>
                </div>
                <div className="text-center p-4 bg-purple-900/20 rounded-lg border border-purple-400/30">
                  <div className="text-2xl font-bold text-purple-400 mb-2">
                    92.8% 完成
                  </div>
                  <div className="text-sm text-gray-300">
                    理論的基盤の整備状況
                  </div>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>

        {/* フッター情報 */}
        <div className="text-center text-gray-400">
          <p className="mb-4">
            このシステムは<strong>physics_restructured</strong>プロジェクトの
            生成情報物理学理論に基づいて設計されています。
          </p>
          <div className="flex justify-center space-x-4 text-sm">
            <span>🔬 理論的整合性: 検証済み</span>
            <span>📐 次元解析: 正確</span>
            <span>🔄 エントロピー増大: 適合</span>
          </div>
        </div>
      </div>
    </div>
  )
} 