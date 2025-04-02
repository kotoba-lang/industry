


# Jung Integrated Assessment

ユングの言語連想テストの総合的な評価コンポーネントです。テキスト入力と音声応答の両方のモードをサポートし、複合的な可視化を提供します。

## 機能

- 単語連想テスト（テキスト入力）
- 音声連想テスト（音声応答）
- 両テストの統合分析
- 3D物理シミュレーションによる視覚化
- 結果の比較分析
- 内部状態管理または外部状態管理のいずれにも対応

## 使用方法

### 基本的な使用方法（内部状態管理）

```tsx
import { IntegratedJungAssessment } from '@/components/jung-integrated';

export default function AssessmentPage() {
  return (
    <div>
      <h1>ユングの統合的言語連想テスト</h1>
      <IntegratedJungAssessment 
        numberOfWords={30}
        apiKey={process.env.NEXT_PUBLIC_HUME_API_KEY}
        speechRecognitionLang="en-US"
      />
    </div>
  );
}
```

### 外部状態管理による使用方法

```tsx
import { useState } from 'react';
import { IntegratedJungAssessment } from '@/components/jung-integrated';
import { type TestResults as WordTestResults } from '@/components/jung-word-assessment/types';
import { type TestResults as VoiceTestResults } from '@/components/jung-voice-assessment/types';

export default function AssessmentPage() {
  const [wordTestResults, setWordTestResults] = useState<WordTestResults | null>(null);
  const [voiceTestResults, setVoiceTestResults] = useState<VoiceTestResults | null>(null);
  
  return (
    <div>
      <h1>ユングの統合的言語連想テスト</h1>
      <IntegratedJungAssessment 
        numberOfWords={30}
        apiKey={process.env.NEXT_PUBLIC_HUME_API_KEY}
        speechRecognitionLang="en-US"
        wordTestResults={wordTestResults}
        voiceTestResults={voiceTestResults}
        setWordTestResults={setWordTestResults}
        setVoiceTestResults={setVoiceTestResults}
      />
    </div>
  );
}
```

## 前提条件

- 音声テストを使用する場合は Hume AI APIキーが必要です（[Hume AI](https://hume.ai/)で取得できます）
- ブラウザが Web Speech API をサポートしている必要があります（音声テスト用）
- 3Dビジュアライゼーションのためにモダンブラウザが必要です

## プロパティ

| プロパティ名 | 型 | デフォルト値 | 説明 |
|------------|------|-------------|------|
| numberOfWords | number | 30 | テストする単語の数 |
| apiKey | string | process.env.NEXT_PUBLIC_HUME_API_KEY | Hume AI APIキー（音声テスト用） |
| voiceName | string | 'David Hume' | 使用する音声の名前（音声テスト用） |
| speechRecognitionLang | string | 'en-US' | 音声認識の言語（音声テスト用） |
| wordTestResults | WordTestResults \| null | null | 単語テストの結果（オプション） |
| voiceTestResults | VoiceTestResults \| null | null | 音声テストの結果（オプション） |
| setWordTestResults | function | - | 単語テスト結果を設定するコールバック（オプション） |
| setVoiceTestResults | function | - | 音声テスト結果を設定するコールバック（オプション） |

## 状態管理について

このコンポーネントは2つの状態管理モードをサポートしています：

1. **内部状態管理** - 状態関連のプロップスを渡さない場合、コンポーネントは内部でテスト結果を管理します
2. **外部状態管理** - 結果と状態セッターを渡すと、コンポーネントは親コンポーネントの状態を使用します

外部状態管理は、結果を永続化したり、複数のコンポーネント間で状態を共有したりする場合に有用です。

## 統合可視化について

このコンポーネントは単語テストと音声テストの結果を3D空間内に統合して可視化します。異なるテストモードによる応答パターンを比較することで、より包括的な心理分析が可能になります。

特徴：
- 異なる色とグループで単語テストと音声テストの結果を区別
- 両テストで同じ単語が現れた場合に接続を作成
- 応答時間に基づいた接続の強さの変化
- 遅延した応答（潜在的な複合体を示す）の強調表示

## セキュリティと注意点

- APIキーは環境変数として安全に管理してください
- 音声テストではマイク使用許可がブラウザで必要です
- 音声認識データはローカルで処理され、APIに送信されるのは音声合成用のテキストのみです
- 個人の心理データを扱うため、プライバシーに配慮してください 