import { Metadata } from 'next';
import VoiceEmotionAnalysisWebSocket from '@/components/spirit-in-physics/VoiceEmotionAnalysisWebSocket';

export const metadata: Metadata = {
  title: 'リアルタイム音声感情分析 (WebSocket) - Jun Kawasaki',
  description: 'リアルタイムで音声から感情を分析するHume AI WebSocketデモ',
};

export default function VoiceEmotionWebSocketPage() {
  return (
    <main className="container mx-auto py-8">
      <h1 className="text-3xl font-bold mb-8 text-center">リアルタイム音声感情分析</h1>
      <p className="mb-6 text-gray-600 text-center">
        Hume AI の WebSocket APIを使用して、音声からリアルタイムで感情を分析します。<br />
        録音ボタンを押して話し始めると、音声から感情をリアルタイムで検出します。
      </p>
      <div className="max-w-2xl mx-auto">
        <VoiceEmotionAnalysisWebSocket />
      </div>
    </main>
  );
} 