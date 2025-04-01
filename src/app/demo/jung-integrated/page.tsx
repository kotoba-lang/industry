"use client";

import { useState } from 'react';
import { IntegratedJungAssessment } from '@/components/jung-integrated';
import { type TestResults as WordTestResults } from '@/components/jung-word-assessment/types';
import { type TestResults as VoiceTestResults } from '@/components/jung-voice-assessment/types';

export default function JungIntegratedDemo() {
  const [wordTestResults, setWordTestResults] = useState<WordTestResults | null>(null);
  const [voiceTestResults, setVoiceTestResults] = useState<VoiceTestResults | null>(null);
  const [useExternalState, setUseExternalState] = useState<boolean>(false);
  
  return (
    <div className="max-w-7xl mx-auto px-4 py-8">
      <h1 className="text-3xl font-bold mb-2">ユングの統合的言語連想テスト</h1>
      <p className="text-gray-600 mb-6">
        言語的無意識と連想パターンを視覚化するテスト。テキスト入力と音声応答の両方に対応しています。
      </p>
      
      <div className="mb-8 p-4 bg-blue-50 rounded-lg">
        <div className="flex items-center mb-4">
          <input
            type="checkbox"
            id="stateToggle"
            checked={useExternalState}
            onChange={(e) => setUseExternalState(e.target.checked)}
            className="mr-2"
          />
          <label htmlFor="stateToggle" className="text-sm">
            外部状態管理を使用する（デモ目的）
          </label>
        </div>
        
        {useExternalState && (
          <div className="text-sm text-gray-600">
            <p>現在の状態：</p>
            <ul className="list-disc pl-5 mt-1">
              <li>単語テスト： {wordTestResults ? '完了' : '未完了'}</li>
              <li>音声テスト： {voiceTestResults ? '完了' : '未完了'}</li>
            </ul>
          </div>
        )}
      </div>
      
      {useExternalState ? (
        <IntegratedJungAssessment 
          numberOfWords={1} // デモ用に少なめの単語数
          apiKey={process.env.NEXT_PUBLIC_HUME_API_KEY}
          speechRecognitionLang="en-US"
          wordTestResults={wordTestResults}
          voiceTestResults={voiceTestResults}
          setWordTestResults={setWordTestResults}
          setVoiceTestResults={setVoiceTestResults}
        />
      ) : (
        <IntegratedJungAssessment 
          numberOfWords={10} // デモ用に少なめの単語数
          apiKey={process.env.NEXT_PUBLIC_HUME_API_KEY}
          speechRecognitionLang="en-US"
        />
      )}
      
      <div className="mt-12 border-t pt-6">
        <h2 className="text-xl font-bold mb-4">このデモについて</h2>
        <p className="mb-3">
          このデモでは、カール・グスタフ・ユングの言語連想テストをテキスト入力と音声対話の両方で実施できます。
          反応時間が2秒以上の「遅延応答」は、潜在的な心理的複合体（コンプレックス）を示している可能性があります。
        </p>
        <p className="mb-3">
          結果は3D空間内の物理シミュレーションで視覚化され、単語間の連想関係が表示されます。
          また、「外部状態管理」スイッチをオンにすると、コンポーネントの状態管理方法を切り替えることができます。
        </p>
      </div>
    </div>
  );
} 