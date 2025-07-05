'use client'

import { useState } from 'react'
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Textarea } from "@/components/ui/textarea"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { AlertCircle, Zap, Shield, Target, FileText, Brain } from 'lucide-react'
import { Alert, AlertDescription } from "@/components/ui/alert"

interface HumanizationMethod {
  id: string
  name: string
  description: string
  paper: string
  effectiveness: string
  icon: React.ReactNode
  color: string
}

interface HumanizationResult {
  original_text: string
  processed_text: string
  applied_methods: string[]
  analysis: {
    character_change: number
    character_change_ratio: number
    estimated_detection_evasion: number
    processing_time: number
  }
  method_details: Record<string, any>
}

const humanizationMethods: HumanizationMethod[] = [
  // 基本人間化手法
  {
    id: 'adversarial_paraphrasing',
    name: 'Natural Paraphrasing',
    description: '文構造を変更して自然な表現に変換する言い換え手法',
    paper: 'Advanced Paraphrasing (2025) - 87.88%改善',
    effectiveness: '87.88%',
    icon: <FileText className="h-4 w-4" />,
    color: 'bg-blue-500'
  },
  {
    id: 'grad_escape',
    name: 'Gradient Enhancement',
    description: '勾配情報を利用した微細な文章品質向上手法',
    paper: 'Gradient-based Enhancement (2025, USENIX Security)',
    effectiveness: '勾配ベース',
    icon: <Zap className="h-4 w-4" />,
    color: 'bg-purple-500'
  },
  {
    id: 'silver_speak',
    name: 'Character Variation',
    description: '視覚的に類似した文字での自然な表現最適化',
    paper: 'Character Enhancement (2024)',
    effectiveness: '文字最適化',
    icon: <Shield className="h-4 w-4" />,
    color: 'bg-green-500'
  },
  {
    id: 'syntactic_perturbation',
    name: 'Syntax Enhancement',
    description: '12種類の統語技術による文章品質向上',
    paper: 'Syntactic Improvement (2024, ACL)',
    effectiveness: '12種類技術',
    icon: <Target className="h-4 w-4" />,
    color: 'bg-orange-500'
  },
  {
    id: 'linguistic_complexity',
    name: 'Linguistic Enrichment',
    description: '接続詞・修飾語・強調表現による文章豊富化',
    paper: 'Language Enhancement',
    effectiveness: '言語豊富化',
    icon: <Brain className="h-4 w-4" />,
    color: 'bg-red-500'
  },
  // 高度人間化手法
  {
    id: 'perturbation_attack',
    name: 'Text Refinement',
    description: '多段階処理による高度な文章品質改善手法',
    paper: 'Advanced Text Processing (2025)',
    effectiveness: '多段階処理',
    icon: <Zap className="h-4 w-4" />,
    color: 'bg-cyan-500'
  },
  {
    id: 'bert_attack',
    name: 'Semantic Enhancement',
    description: 'BERT類義語置換による意味保持型文章改善',
    paper: 'BERT-based Semantic Enhancement (2025)',
    effectiveness: '意味保持',
    icon: <Brain className="h-4 w-4" />,
    color: 'bg-indigo-500'
  },
  {
    id: 'copa_attack',
    name: 'Contrastive Humanization',
    description: '対比分析による人間らしい表現への変換',
    paper: 'Contrastive Text Improvement (2025)',
    effectiveness: '対比最適化',
    icon: <FileText className="h-4 w-4" />,
    color: 'bg-pink-500'
  },
  {
    id: 'watermark_evasion',
    name: 'Style Normalization',
    description: 'テキストスタイルの正規化と自然化処理',
    paper: 'Style Normalization Techniques (2025)',
    effectiveness: 'スタイル調整',
    icon: <Shield className="h-4 w-4" />,
    color: 'bg-teal-500'
  },
  {
    id: 'token_break',
    name: 'Token Enhancement',
    description: '文字・記号レベルでの自然な表現最適化',
    paper: 'Token-level Enhancement (2025)',
    effectiveness: '文字最適化',
    icon: <Target className="h-4 w-4" />,
    color: 'bg-emerald-500'
  },
  {
    id: 'llm_attack',
    name: 'AI Humanizer (Claude)',
    description: 'Claude APIによる高度な文脈理解ベース人間化',
    paper: 'LLM-based Text Humanization (2025)',
    effectiveness: 'AI駆動',
    icon: <Brain className="h-4 w-4" />,
    color: 'bg-violet-500'
  },
  {
    id: 'attack_tree',
    name: 'Composite Enhancement',
    description: '複数手法の最適化組み合わせによる総合的改善',
    paper: 'Multi-method Optimization (2025)',
    effectiveness: '複合最適化',
    icon: <Zap className="h-4 w-4" />,
    color: 'bg-amber-500'
  }
]

export default function HumanizePage() {
  const [selectedMethods, setSelectedMethods] = useState<string[]>([])
  const [inputText, setInputText] = useState('')
  const [result, setResult] = useState<HumanizationResult | null>(null)
  const [isProcessing, setIsProcessing] = useState(false)
  const [intensity, setIntensity] = useState(0.3)

  const handleMethodToggle = (methodId: string) => {
    setSelectedMethods(prev => 
      prev.includes(methodId) 
        ? prev.filter(id => id !== methodId)
        : [...prev, methodId]
    )
  }

  const handleProcessText = async () => {
    if (!inputText.trim() || selectedMethods.length === 0) {
      alert('テキストと少なくとも1つの攻撃手法を選択してください')
      return
    }

    setIsProcessing(true)
    try {
      const response = await fetch('/api/humanize', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          text: inputText,
          methods: selectedMethods,
          intensity: intensity
        }),
      })

      if (!response.ok) {
        throw new Error('処理に失敗しました')
      }

      const data = await response.json()
      setResult(data)
    } catch (error) {
      console.error('Error:', error)
      alert('処理中にエラーが発生しました')
    } finally {
      setIsProcessing(false)
    }
  }

  const loadSampleText = () => {
    setInputText(`test`)
  }

  return (
    <div className="container mx-auto p-6 max-w-7xl">
      <div className="mb-8">
        <h1 className="text-3xl font-bold mb-2">AI Text humanizer</h1>
        <p className="text-muted-foreground">
          最新研究論文に基づく12の手法を組み合わせてAI生成による文書を人間化します
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* 設定パネル */}
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>人間化手法の選択</CardTitle>
              <CardDescription>
                適用したい人間化手法を選択してください
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              {humanizationMethods.map((method) => (
                <div key={method.id} className="flex items-start space-x-3 p-3 border rounded-lg hover:bg-muted/50">
                  <Checkbox
                    id={method.id}
                    checked={selectedMethods.includes(method.id)}
                    onCheckedChange={() => handleMethodToggle(method.id)}
                  />
                  <div className="flex-1 space-y-2">
                    <div className="flex items-center space-x-2">
                      <div className={`p-1 rounded ${method.color} text-white`}>
                        {method.icon}
                      </div>
                      <label htmlFor={method.id} className="font-medium cursor-pointer">
                        {method.name}
                      </label>
                      <Badge variant="outline" className="text-xs">
                        {method.effectiveness}
                      </Badge>
                    </div>
                    <p className="text-sm text-muted-foreground">
                      {method.description}
                    </p>
                    <p className="text-xs text-muted-foreground font-mono">
                      {method.paper}
                    </p>
                  </div>
                </div>
              ))}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>強度設定</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="space-y-2">
                <label className="text-sm font-medium">
                  攻撃強度: {intensity}
                </label>
                <input
                  type="range"
                  min="0.1"
                  max="1.0"
                  step="0.1"
                  value={intensity}
                  onChange={(e) => setIntensity(parseFloat(e.target.value))}
                  className="w-full"
                />
                <div className="flex justify-between text-xs text-muted-foreground">
                  <span>控えめ (0.1)</span>
                  <span>最大 (1.0)</span>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>

        {/* テキスト入力・処理 */}
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>テキスト入力</CardTitle>
              <CardDescription>
                処理したいテキストを入力してください
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex gap-2 mb-2">
                <Button 
                  variant="outline" 
                  size="sm"
                  onClick={loadSampleText}
                >
                  サンプルテキスト読み込み
                </Button>
                <Button 
                  variant="outline" 
                  size="sm"
                  onClick={() => setInputText('')}
                >
                  クリア
                </Button>
              </div>
              <Textarea
                value={inputText}
                onChange={(e) => setInputText(e.target.value)}
                placeholder="ここにテキストを入力してください..."
                className="min-h-[300px] resize-none"
              />
              <div className="flex justify-between items-center">
                <span className="text-sm text-muted-foreground">
                  文字数: {inputText.length}
                </span>
                <Button 
                  onClick={handleProcessText}
                  disabled={isProcessing || !inputText.trim() || selectedMethods.length === 0}
                  className="min-w-[120px]"
                >
                  {isProcessing ? '処理中...' : '人間化実行'}
                </Button>
              </div>
            </CardContent>
          </Card>
        </div>
      </div>

      {/* 結果表示 */}
      {result && (
        <Card className="mt-6">
          <CardHeader>
            <CardTitle>処理結果</CardTitle>
            <CardDescription>
              適用された攻撃手法: {result.applied_methods.join(', ')}
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Tabs defaultValue="result" className="w-full">
              <TabsList className="grid w-full grid-cols-3">
                <TabsTrigger value="result">処理結果</TabsTrigger>
                <TabsTrigger value="analysis">分析</TabsTrigger>
                <TabsTrigger value="comparison">比較</TabsTrigger>
              </TabsList>
              
              <TabsContent value="result" className="space-y-4">
                <div>
                  <h4 className="font-medium mb-2">処理済みテキスト</h4>
                  <Textarea
                    value={result.processed_text}
                    readOnly
                    className="min-h-[300px] font-mono text-sm"
                  />
                </div>
              </TabsContent>
              
              <TabsContent value="analysis" className="space-y-4">
                <div className="grid grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <h4 className="font-medium">処理統計</h4>
                    <div className="space-y-1 text-sm">
                      <div>文字変更: {result.analysis.character_change}</div>
                      <div>変更率: {(result.analysis.character_change_ratio * 100).toFixed(2)}%</div>
                      <div>処理時間: {result.analysis.processing_time.toFixed(2)}秒</div>
                    </div>
                  </div>
                  <div className="space-y-2">
                    <h4 className="font-medium">効果予測</h4>
                    <div className="space-y-1 text-sm">
                      <div>
                        検出回避率: {(result.analysis.estimated_detection_evasion * 100).toFixed(1)}%
                      </div>
                      <Badge 
                        variant={result.analysis.estimated_detection_evasion > 0.8 ? "default" : "secondary"}
                        className="mt-1"
                      >
                        {result.analysis.estimated_detection_evasion > 0.8 ? "高効果" : "中効果"}
                      </Badge>
                    </div>
                  </div>
                </div>

                {Object.keys(result.method_details).length > 0 && (
                  <div>
                    <h4 className="font-medium mb-2">手法別詳細</h4>
                    <div className="space-y-2">
                      {Object.entries(result.method_details).map(([method, details]) => (
                        <div key={method} className="p-3 border rounded-lg">
                          <h5 className="font-medium text-sm">{method}</h5>
                          <pre className="text-xs text-muted-foreground mt-1 overflow-x-auto">
                            {JSON.stringify(details, null, 2)}
                          </pre>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </TabsContent>
              
              <TabsContent value="comparison" className="space-y-4">
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div>
                    <h4 className="font-medium mb-2">元テキスト</h4>
                    <Textarea
                      value={result.original_text}
                      readOnly
                      className="min-h-[300px] text-sm"
                    />
                  </div>
                  <div>
                    <h4 className="font-medium mb-2">処理済みテキスト</h4>
                    <Textarea
                      value={result.processed_text}
                      readOnly
                      className="min-h-[300px] text-sm"
                    />
                  </div>
                </div>
              </TabsContent>
            </Tabs>
          </CardContent>
        </Card>
      )}
    </div>
  )
} 