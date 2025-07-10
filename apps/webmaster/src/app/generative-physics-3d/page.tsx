import SimpleGenerativePhysicsRenderer from '@/components/physics/SimpleGenerativePhysicsRenderer'
import { Metadata } from 'next'

export const metadata: Metadata = {
  title: '生成物理学 3Dレンダリング | Jun Kawasaki',
  description: 'physics_restructuredプロジェクトの生成物理学数式を使った3Dレンダリングシステム。情報密度進化、量子効果、意識と宇宙の相互作用を可視化。',
  keywords: ['生成物理学', '3Dレンダリング', '情報密度', '量子情報', '意識', '宇宙論', '物理学', 'physics_restructured'],
}

export default function GenerativePhysics3DPage() {
  return <SimpleGenerativePhysicsRenderer />
} 