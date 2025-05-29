import { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'Voice Emotion Analysis Test - Jun Kawasaki',
  description: 'Test voice emotion analysis using Hume AI SDK',
};

/**
 * 音声感情テストページ（一時的に簡略化）
 */
export default function VoiceEmotionTestPage() {
  return (
    <div className="container mx-auto py-8 px-4">
      <h1 className="text-3xl font-bold mb-6">Voice Emotion Test</h1>
      <div className="bg-yellow-50 border border-yellow-200 rounded-lg p-6">
        <h2 className="text-xl font-semibold mb-4">Under Maintenance</h2>
        <p className="text-gray-700">
          This page is temporarily under maintenance for optimization. 
          Please check back later.
        </p>
      </div>
    </div>
  );
} 