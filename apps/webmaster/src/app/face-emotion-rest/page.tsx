import { Metadata } from 'next';
import FaceEmotionAnalysisRest from '@/components/spirit-in-physics/FaceEmotionAnalysisRest';

export const metadata: Metadata = {
  title: 'Face Emotion Analysis (REST) - Jun Kawasaki',
  description: 'Analyze facial emotions using Hume AI REST API',
};

export default function FaceEmotionRestPage() {
  return (
    <main className="container mx-auto py-8">
      <h1 className="text-3xl font-bold mb-8 text-center">リアルタイム感情分析 (REST API版)</h1>
      <p className="mb-6 text-gray-600 text-center">
        Hume AI の REST API を使用して、表情から感情を分析します。<br />
        カメラを開始すると定期的に画像が撮影され、感情分析が実行されます。
      </p>
      <FaceEmotionAnalysisRest />
    </main>
  );
} 