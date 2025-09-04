# Jung Voice Assessment

ユングの言語連想テストと音声対話を組み合わせたコンポーネントです。AIがガイドし、ユーザーは口頭で応答できます。

## 機能

- Hume AIのテキスト読み上げ機能によるAIガイダンス
- Web Speech APIを使用した音声認識
- ユングの言語連想テストの実施
- リアルタイムの反応時間測定
- 結果の分析と表示

## 使用方法

```tsx
import { JungVoiceAssessment } from '@/components/jung-voice-assessment';

export default function MyPage() {
  return (
    <div>
      <h1>ユングの言語連想テスト (音声対応版)</h1>
      <JungVoiceAssessment 
        apiKey={process.env.NEXT_PUBLIC_HUME_API_KEY}
        numberOfWords={50} // テストする単語数（最大100）
        speechRecognitionLang="en-US" // 日本語で音声認識
      />
    </div>
  );
}
```

## 前提条件

- Hume AI APIキーが必要です（[Hume AI](https://hume.ai/)で取得できます）
- ブラウザが Web Speech API をサポートしている必要があります

## プロパティ

| プロパティ名 | 型 | デフォルト値 | 説明 |
|------------|------|-------------|------|
| numberOfWords | number | 100 | テストする単語の数（最大100） |
| apiKey | string | process.env.NEXT_PUBLIC_HUME_API_KEY | Hume AI APIキー |
| generationId | string | '795c949a-1510-4a80-9646-7d0863b023ab' | 音声生成ID |
| voiceName | string | 'David Hume' | 使用する音声の名前 |
| speechRecognitionLang | string | 'en-US' | 音声認識の言語 |
| onTestComplete | function | - | テスト完了時のコールバック関数 |
| className | string | '' | 追加のCSSクラス名 |

## ユングの言語連想テストについて

カール・グスタフ・ユングが1910年に開発した言語連想テストは、無意識の複合体（コンプレックス）を明らかにするための心理分析ツールです。

このテストでは、被験者に一連の「刺激語」が提示され、各単語に対する最初の連想（反応語）と反応時間が記録されます。

ユングは、特に以下のような反応に注目しました：
- 異常に遅い反応時間（2秒以上）
- 同じ応答の繰り返し
- 応答できなかった単語
- 情緒的に関連の強い応答

これらのパターンは、無意識の複合体や抑圧された感情を示している可能性があるとされています。

## セキュリティと注意点

- APIキーは環境変数として安全に管理してください
- マイク使用許可がブラウザで必要です
- 音声認識データはローカルで処理され、APIに送信されるのは音声合成用のテキストのみです 