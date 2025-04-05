import { Metadata } from 'next';
import VoiceEmotionAnalysisTest from '@/components/spirit-in-physics/VoiceEmotionAnalysisTest';

export const metadata: Metadata = {
  title: 'Voice Emotion Analysis Test - Jun Kawasaki',
  description: 'Test voice emotion analysis using Hume AI SDK',
};

export default function VoiceEmotionTestPage() {
  return (
    <main className="container mx-auto py-8">
      <h1 className="text-3xl font-bold mb-8 text-center">音声感情分析テスト</h1>
      <p className="mb-6 text-gray-600 text-center">
        Hume AI の音声感情分析機能をテストします。<br />
        録音ボタンを押して話し始めると、音声から感情を分析できます。
      </p>
      <VoiceEmotionAnalysisTest />
    </main>
  );
} 