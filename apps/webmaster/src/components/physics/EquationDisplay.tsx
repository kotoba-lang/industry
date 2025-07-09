'use client'

import { motion } from 'framer-motion'
import { PhysicsPhase } from './GenerativeInformationPhysicsVisualization'
import { BookOpen, Calculator, Atom } from 'lucide-react'

interface EquationDisplayProps {
  equation: string
  description: string
  phase: PhysicsPhase
}

export function EquationDisplay({ equation, description, phase }: EquationDisplayProps) {
  return (
    <motion.div 
      className="bg-gradient-to-r from-black/20 to-black/30 backdrop-blur-sm rounded-2xl border border-white/10 p-6"
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.6, delay: 0.4 }}
    >
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* 方程式セクション */}
        <motion.div 
          className="bg-black/30 rounded-xl p-6 border border-white/5"
          key={phase.id}
          initial={{ opacity: 0, scale: 0.9 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: 0.5 }}
        >
          <div className="flex items-center gap-3 mb-4">
            <Calculator className="text-cyan-400" size={24} />
            <h3 className="text-xl font-bold text-white">支配方程式</h3>
          </div>
          
          <div className="bg-black/40 rounded-lg p-4 border border-cyan-500/20">
            <motion.div
              className="font-mono text-lg md:text-xl text-cyan-300 text-center"
              style={{ 
                textShadow: '0 0 10px rgba(103, 232, 249, 0.5)',
                fontFamily: 'Fira Code, Monaco, Consolas, monospace'
              }}
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={{ duration: 0.8, delay: 0.2 }}
            >
              {equation}
            </motion.div>
          </div>
          
          {/* 方程式の意味 */}
          <div className="mt-4 text-sm text-gray-300">
            <div className="flex items-start gap-2">
              <Atom className="text-purple-400 mt-1 flex-shrink-0" size={16} />
              <div>
                <p className="font-semibold text-purple-400 mb-1">物理的意味:</p>
                <p>{getEquationMeaning(phase.name)}</p>
              </div>
            </div>
          </div>
        </motion.div>

        {/* 説明セクション */}
        <motion.div 
          className="bg-black/30 rounded-xl p-6 border border-white/5"
          key={`desc-${phase.id}`}
          initial={{ opacity: 0, x: 20 }}
          animate={{ opacity: 1, x: 0 }}
          transition={{ duration: 0.5, delay: 0.1 }}
        >
          <div className="flex items-center gap-3 mb-4">
            <BookOpen className="text-green-400" size={24} />
            <h3 className="text-xl font-bold text-white">物理過程</h3>
          </div>
          
          <motion.p 
            className="text-gray-300 leading-relaxed"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ duration: 0.8, delay: 0.3 }}
          >
            {description}
          </motion.p>
          
          {/* 重要な物理量 */}
          <div className="mt-4 space-y-2">
            <h4 className="text-sm font-semibold text-yellow-400">重要な物理量:</h4>
            <div className="grid grid-cols-2 gap-2 text-xs">
              <div className="bg-black/40 rounded p-2">
                <div className="text-cyan-300 font-mono">ρ_I</div>
                <div className="text-gray-400">情報密度</div>
              </div>
              <div className="bg-black/40 rounded p-2">
                <div className="text-purple-300 font-mono">C(t)</div>
                <div className="text-gray-400">計算複雑性</div>
              </div>
              <div className="bg-black/40 rounded p-2">
                <div className="text-green-300 font-mono">E</div>
                <div className="text-gray-400">量子もつれ度</div>
              </div>
              <div className="bg-black/40 rounded p-2">
                <div className="text-orange-300 font-mono">Φ</div>
                <div className="text-gray-400">意識指数</div>
              </div>
            </div>
          </div>
        </motion.div>
      </div>
      
      {/* 段階固有の洞察 */}
      <motion.div 
        className="mt-6 bg-gradient-to-r from-indigo-500/10 to-purple-500/10 rounded-xl p-4 border border-indigo-500/20"
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5, delay: 0.6 }}
      >
        <h4 className="text-lg font-semibold text-indigo-300 mb-2">
          🔬 {phase.name}の核心洞察
        </h4>
        <p className="text-gray-300 text-sm">
          {getPhaseInsight(phase.name)}
        </p>
      </motion.div>
    </motion.div>
  )
}

function getEquationMeaning(phaseName: string): string {
  const meanings: Record<string, string> = {
    "BigBang": "ハッブルパラメータが発散し、時空の曲率が無限大に達する特異点",
    "インフレーション": "スケール因子が指数関数的に増大する急速膨張期",
    "クォーク閉じ込め": "QCD結合定数が小さくなり、クォークが束縛状態を形成",
    "原始核合成": "ヘリウムと重水素の核融合反応による軽元素の合成",
    "再結合": "陽子と電子が結合して中性水素原子を形成する過程",
    "最初の星": "ジーンズ質量による重力不安定性から星形成が始まる条件",
    "銀河形成": "自由落下時間スケールでの大規模構造の重力収縮",
    "現在": "情報統合によって定義される意識の統合情報理論"
  }
  return meanings[phaseName] || "宇宙進化における基本的な物理法則"
}

function getPhaseInsight(phaseName: string): string {
  const insights: Record<string, string> = {
    "BigBang": "情報密度が無限大となる特異点では、全ての物理法則が統一された状態にあります。これは情報処理の観点から見ると、宇宙の「初期プログラム」が凝縮された状態と解釈できます。",
    "インフレーション": "量子揺らぎが古典的密度揺らぎに転換される瞬間です。情報理論的には、量子情報が古典情報に「デコヒーレンス」する過程として理解できます。",
    "クォーク閉じ込め": "強い相互作用による相転移により、クォークレベルの情報処理から核子レベルの情報処理へと階層が変化します。",
    "原始核合成": "核子の結合により、より複雑な情報構造（原子核）が形成されます。これは宇宙の「化学的記憶」の基盤となります。",
    "再結合": "電子と原子核の結合により、電磁相互作用が遮蔽され、光子が自由に伝播できるようになります。これにより宇宙は「透明」になり、情報の遠距離伝達が可能になります。",
    "最初の星": "重力収縮により、散逸構造として初めての「情報処理装置」である星が誕生します。核融合は宇宙初の持続的エネルギー変換プロセスです。",
    "銀河形成": "多数の星系が重力的に結合し、より大規模で複雑な情報処理システムが出現します。銀河は宇宙の「都市」のような役割を果たします。",
    "現在": "生物学的進化を通じて意識が出現し、宇宙が自分自身を認識し理解する能力を獲得します。これは宇宙の「自己覚醒」と解釈できます。"
  }
  return insights[phaseName] || "宇宙進化における重要な転換点です。"
} 