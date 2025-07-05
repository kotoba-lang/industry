/**
 * @jest-environment jsdom
 */

import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import JungVoiceAssessment from './JungVoiceAssessment';
import { saveEmotionData, getEmotionData } from '@/lib/actions/emotion-actions';

// EmotionDataServiceのモック
jest.mock('@/lib/actions/emotion-actions', () => ({
  EmotionDataActions: {
    saveEmotionData: jest.fn().mockResolvedValue({ success: true }),
    saveFacialEmotionData: jest.fn().mockResolvedValue({ success: true }),
    getEmotionDataByAssessment: jest.fn().mockResolvedValue({ 
      success: true, 
      data: [] 
    })
  }
}));

// Supabaseクライアントのモック
jest.mock('@/lib/supabase/server', () => ({
  createSupabaseServerClient: jest.fn().mockResolvedValue({
    from: jest.fn().mockReturnValue({
      insert: jest.fn().mockReturnValue({
        error: null
      }),
      select: jest.fn().mockReturnValue({
        eq: jest.fn().mockReturnValue({
          eq: jest.fn().mockReturnValue({
            data: [],
            error: null
          })
        })
      })
    })
  })
}));

// Web Speech API のモック
interface MockSpeechRecognition {
  start: jest.Mock;
  stop: jest.Mock;
  abort: jest.Mock;
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  onresult?: (event: any) => void;
  onend?: () => void;
}

const mockSpeechRecognition: MockSpeechRecognition = {
  start: jest.fn(),
  stop: jest.fn(),
  abort: jest.fn(),
  continuous: false,
  interimResults: true,
  lang: 'en-US',
};

Object.defineProperty(global, 'SpeechRecognition', {
  value: jest.fn().mockImplementation(() => mockSpeechRecognition),
  writable: true
});

// ウィンドウオブジェクトのlocalStorageをモック
Object.defineProperty(window, 'localStorage', {
  value: {
    getItem: jest.fn(),
    setItem: jest.fn(),
    removeItem: jest.fn(),
    clear: jest.fn(),
  },
  writable: true
});

// Fetch APIのモック
global.fetch = jest.fn().mockImplementation(() =>
  Promise.resolve({
    ok: true,
    json: () => Promise.resolve({
      generations: [{ audio: 'base64encodedaudio' }]
    }),
    blob: () => Promise.resolve(new Blob(['test'], { type: 'audio/mp3' }))
  })
);

// URL.createObjectURL のモック
URL.createObjectURL = jest.fn().mockReturnValue('blob:test');

// HTMLMediaElement のモック
HTMLMediaElement.prototype.play = jest.fn();
HTMLMediaElement.prototype.pause = jest.fn();
HTMLMediaElement.prototype.load = jest.fn();

// HumeEmotionProviderのmock
jest.mock('@/providers/HumeEmotionProvider', () => ({
  useHumeEmotion: jest.fn().mockReturnValue({
    enableTracking: jest.fn(),
    disableTracking: jest.fn(),
    isTracking: false,
    currentEmotion: {
      emotions: {
        joy: 0.5,
        sadness: 0.1,
        anger: 0.02,
        surprise: 0.3
      }
    },
    emotionHistory: []
  }),
  HumeEmotionProvider: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

// Add type to global namespace
declare global {
  interface Window {
    localStorage: {
      getItem: jest.Mock;
      setItem: jest.Mock;
      removeItem: jest.Mock;
      clear: jest.Mock;
    };
  }
  
  namespace NodeJS {
    interface Global {
      SpeechRecognition: jest.Mock;
      fetch: jest.Mock;
    }
  }
}

describe('重要度: 5 - JungVoiceAssessment コンポーネントのデータベース永続化', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (window.localStorage.getItem as jest.Mock).mockReturnValue(null);
  });

  test('重要度: 5 - ユーザーIDが生成されてlocalStorageに保存されること', async () => {
    render(<JungVoiceAssessment apiKey="test-key" />);
    
    await waitFor(() => {
      expect(window.localStorage.setItem).toHaveBeenCalledWith(
        'jung_test_user_id',
        expect.any(String)
      );
    });
  });

  test('重要度: 5 - 既存のユーザーIDがlocalStorageから取得されること', async () => {
    // ユーザーIDが既に存在する場合
    const mockUserId = '12345678-1234-1234-1234-123456789012';
    (window.localStorage.getItem as jest.Mock).mockReturnValue(mockUserId);
    
    render(<JungVoiceAssessment apiKey="test-key" />);
    
    await waitFor(() => {
      // 新しいIDが生成されないこと
      expect(window.localStorage.setItem).not.toHaveBeenCalledWith(
        'jung_test_user_id',
        expect.any(String)
      );
    });
  });

  test('重要度: 4 - 同意後にJungVoiceTestコンポーネントが表示されること', async () => {
    const { getByText, queryByText } = render(
      <JungVoiceAssessment apiKey="test-key" />
    );
    
    // 初期状態では同意フォームが表示されていること
    expect(queryByText(/Spirit in Physics/i)).not.toBeInTheDocument();
    
    // 同意ボタンをクリック
    const consentButton = getByText(/同意する/i);
    fireEvent.click(consentButton);
    
    // 同意後にJungVoiceTestが表示されること
    await waitFor(() => {
      expect(queryByText(/Spirit in Physics/i)).toBeInTheDocument();
    });
  });

  test('重要度: 5 - テスト応答時にEmotionDataServiceが呼び出されること', async () => {
    // モック値を準備
    const mockResponseHandler = jest.fn();
    const mockUserId = '12345678-1234-1234-1234-123456789012';
    (window.localStorage.getItem as jest.Mock).mockReturnValue(mockUserId);
    
    // SpeechRecognitionのモックが応答を返すよう設定
    mockSpeechRecognition.onresult = undefined;
    mockSpeechRecognition.onend = undefined;
    
    // コンポーネントをレンダリング
    const { getByText } = render(
      <JungVoiceAssessment 
        apiKey="test-key" 
        numberOfWords={5} 
        onTestComplete={mockResponseHandler}
      />
    );
    
    // 同意フォームを完了
    const consentButton = getByText(/同意する/i);
    fireEvent.click(consentButton);
    
    // テスト開始ボタンを見つけてクリック（これは表示されるまで待機する必要がある）
    await waitFor(() => {
      const startButton = screen.getByText(/Start Test/i);
      fireEvent.click(startButton);
    });
    
    // WebSpeechAPIの応答をシミュレート
    const speechRecognitionInstance = (global as any).SpeechRecognition.mock.instances[0];
    if (speechRecognitionInstance.onresult) {
      speechRecognitionInstance.onresult({
        results: [[{ transcript: 'テスト応答' }]]
      });
      
      if (speechRecognitionInstance.onend) {
        speechRecognitionInstance.onend();
      }
    }
    
    // EmotionDataServiceが呼び出されたことを確認
    await waitFor(() => {
      expect(saveEmotionData).toHaveBeenCalledWith(
        expect.objectContaining({
          userId: mockUserId,
          assessmentId: expect.any(String),
          responseWord: expect.any(String),
        })
      );
    });
  });

  test('重要度: 3 - テスト完了時にonTestCompleteコールバックが呼び出されること', async () => {
    // テスト完了コールバックをモック
    const mockTestComplete = jest.fn();
    const mockUserId = '12345678-1234-1234-1234-123456789012';
    (window.localStorage.getItem as jest.Mock).mockReturnValue(mockUserId);
    
    // コンポーネントをレンダリング
    render(
      <JungVoiceAssessment 
        apiKey="test-key" 
        numberOfWords={1} 
        onTestComplete={mockTestComplete}
      />
    );
    
    // 同意する
    const consentButton = screen.getByText(/同意する/i);
    fireEvent.click(consentButton);
    
    // テスト開始
    await waitFor(() => {
      const startButton = screen.getByText(/Start Test/i);
      fireEvent.click(startButton);
    });
    
    // 単語応答をシミュレート
    const speechRecognitionInstance = (global as any).SpeechRecognition.mock.instances[0];
    if (speechRecognitionInstance.onresult) {
      speechRecognitionInstance.onresult({
        results: [[{ transcript: 'テスト応答' }]]
      });
      
      if (speechRecognitionInstance.onend) {
        speechRecognitionInstance.onend();
      }
    }
    
    // テスト完了のシミュレーション
    // 注: 実際のフローでは複数のイベントがありますが、テストでは簡略化
    
    // onTestCompleteが呼び出されたことを確認
    await waitFor(() => {
      expect(mockTestComplete).toHaveBeenCalledWith(
        expect.objectContaining({
          totalWords: expect.any(Number),
          averageReactionTimeMs: expect.any(Number),
          delayedResponsesCount: expect.any(Number),
          responses: expect.any(Array)
        })
      );
    }, { timeout: 5000 });
  });

  test('重要度: 4 - データベースエラー時にも処理が継続すること', async () => {
    // データベースエラーをシミュレート
    (saveEmotionData as jest.Mock).mockRejectedValueOnce(new Error('データベースエラー'));
    
    const mockUserId = '12345678-1234-1234-1234-123456789012';
    (window.localStorage.getItem as jest.Mock).mockReturnValue(mockUserId);
    
    // コンポーネントをレンダリング
    render(<JungVoiceAssessment apiKey="test-key" />);
    
    // 同意する
    const consentButton = screen.getByText(/同意する/i);
    fireEvent.click(consentButton);
    
    // テスト開始
    await waitFor(() => {
      const startButton = screen.getByText(/Start Test/i);
      fireEvent.click(startButton);
    });
    
    // 単語応答をシミュレート
    const speechRecognitionInstance = (global as any).SpeechRecognition.mock.instances[0];
    if (speechRecognitionInstance.onresult) {
      speechRecognitionInstance.onresult({
        results: [[{ transcript: 'テスト応答' }]]
      });
      
      if (speechRecognitionInstance.onend) {
        speechRecognitionInstance.onend();
      }
    }
    
    // エラーがコンソールに記録されるが、処理は継続すること
    await waitFor(() => {
      expect(saveEmotionData).toHaveBeenCalled();
      // コンポーネントがクラッシュせずに表示され続けていることを確認
      expect(screen.getByText(/Spirit in Physics/i)).toBeInTheDocument();
    });
  });
}); 