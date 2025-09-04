import AdvancedGenerativePhysicsRenderer from '@/components/physics/AdvancedGenerativePhysicsRenderer'
import { Metadata } from 'next'

export const metadata: Metadata = {
  title: '高度な生成物理学 3Dレンダリング | Jun Kawasaki',
  description: 'σ₈問題の解決、パワースペクトル進化、熱力学的整合性を含む高度な生成物理学3Dレンダリングシステム。physics_restructuredプロジェクトの完全な数式実装。',
  keywords: ['σ₈問題', 'パワースペクトル', '熱力学的整合性', '生成物理学', '3Dレンダリング', '情報密度', '量子情報', '宇宙論', 'physics_restructured'],
}

export default function AdvancedGenerativePhysicsPage() {
  return <AdvancedGenerativePhysicsRenderer />
} 