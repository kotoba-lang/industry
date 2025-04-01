/**
 * @jest-environment jsdom
 */
import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import { VoiceConversation } from './index';
import { HumeClient } from 'hume';
import { EmotionData } from './types';

// Mock the HumeClient
jest.mock('hume', () => {
  return {
    HumeClient: jest.fn().mockImplementation(() => {
      return {
        tts: {
          voices: {
            create: jest.fn().mockResolvedValue({ success: true }),
          },
          streamTts: jest.fn().mockResolvedValue(new Blob(['audio data'], { type: 'audio/mpeg' })),
        },
      };
    }),
  };
});

// Mock UUID generation
jest.mock('uuid', () => ({
  v4: jest.fn().mockReturnValue('mocked-uuid'),
}));

// Mock SpeechRecognition API
class MockSpeechRecognition {
  continuous = false;
  interimResults = false;
  lang = '';
  maxAlternatives = 1;
  onresult: ((event: any) => void) | null = null;
  onend: ((event: any) => void) | null = null;
  onerror: ((event: any) => void) | null = null;
  onstart: ((event: any) => void) | null = null;
  grammars = null;
  start = jest.fn();
  stop = jest.fn();
  abort = jest.fn();
}

// Mock emotion recognition API
const mockEmotionAPI = {
  analyzeEmotion: jest.fn().mockResolvedValue({
    primary: 'happy',
    confidence: 0.87,
    timestamp: new Date()
  }),
};

// Add to global window object
(window as any).SpeechRecognition = MockSpeechRecognition;
(window as any).webkitSpeechRecognition = MockSpeechRecognition;

// Mock IndexedDB storage
const mockIDBStore = {
  saveMessage: jest.fn().mockResolvedValue({ success: true, messageId: 'db-id-123' }),
  getConversation: jest.fn().mockResolvedValue([]),
};

jest.mock('../../utils/storage', () => ({
  createConversationStore: jest.fn().mockReturnValue(mockIDBStore),
}));

// Mock HTMLMediaElement
window.HTMLMediaElement.prototype.play = jest.fn();

describe('VoiceConversation コンポーネント', () => {
  // 重要度: 5
  test('初期メッセージが表示される', () => {
    render(<VoiceConversation initialMessage="こんにちは！" />);
    expect(screen.getByText('こんにちは！')).toBeInTheDocument();
  });

  // 重要度: 5
  test('メッセージを送信できる', async () => {
    const onMessageSent = jest.fn();
    render(<VoiceConversation onMessageSent={onMessageSent} />);
    
    const input = screen.getByPlaceholderText('Type your message here...');
    const sendButton = screen.getByText('Send');
    
    fireEvent.change(input, { target: { value: 'テストメッセージ' } });
    fireEvent.click(sendButton);
    
    await waitFor(() => {
      expect(onMessageSent).toHaveBeenCalledWith('テストメッセージ');
      expect(screen.getByText('テストメッセージ')).toBeInTheDocument();
    });
  });

  // 重要度: 4
  test('エラーメッセージが表示される', async () => {
    // エラーを引き起こすようにモックを上書き
    const mockConsoleError = jest.spyOn(console, 'error').mockImplementation(() => {});
    (HumeClient as jest.Mock).mockImplementationOnce(() => {
      return {
        tts: {
          voices: {
            create: jest.fn().mockRejectedValue(new Error('API エラー')),
          },
          streamTts: jest.fn().mockRejectedValue(new Error('API エラー')),
        },
      };
    });
    
    render(<VoiceConversation />);
    
    const input = screen.getByPlaceholderText('Type your message here...');
    const sendButton = screen.getByText('Send');
    
    fireEvent.change(input, { target: { value: 'エラーテスト' } });
    fireEvent.click(sendButton);
    
    await waitFor(() => {
      expect(screen.getByText(/Error:/)).toBeInTheDocument();
    });
    
    mockConsoleError.mockRestore();
  });

  // 重要度: 3
  test('プレースホルダーテキストがカスタマイズできる', () => {
    const customPlaceholder = 'カスタムプレースホルダー';
    render(<VoiceConversation placeholder={customPlaceholder} />);
    expect(screen.getByPlaceholderText(customPlaceholder)).toBeInTheDocument();
  });

  // 重要度: 5
  test('DBに会話を保存できる', async () => {
    const { getByPlaceholderText, getByText } = render(
      <VoiceConversation storageEnabled={true} />
    );
    
    const input = getByPlaceholderText('Type your message here...');
    const sendButton = getByText('Send');
    
    fireEvent.change(input, { target: { value: '保存されるメッセージ' } });
    fireEvent.click(sendButton);
    
    await waitFor(() => {
      expect(mockIDBStore.saveMessage).toHaveBeenCalledTimes(1);
      const savedMessage = mockIDBStore.saveMessage.mock.calls[0][0];
      expect(savedMessage.content).toBe('保存されるメッセージ');
      expect(savedMessage.sender).toBe('user');
    });
  });

  // 重要度: 5
  test('カスタムストレージハンドラを使用できる', async () => {
    const mockStorageHandler = jest.fn().mockResolvedValue(true);
    const { getByPlaceholderText, getByText } = render(
      <VoiceConversation 
        storageEnabled={true}
        storageHandler={mockStorageHandler}
      />
    );
    
    const input = getByPlaceholderText('Type your message here...');
    const sendButton = getByText('Send');
    
    fireEvent.change(input, { target: { value: 'カスタム保存テスト' } });
    fireEvent.click(sendButton);
    
    await waitFor(() => {
      expect(mockStorageHandler).toHaveBeenCalledTimes(1);
      const savedMessage = mockStorageHandler.mock.calls[0][0];
      expect(savedMessage.content).toBe('カスタム保存テスト');
    });
  });

  // 重要度: 4
  test('音声認識を使用してメッセージを送信できる', async () => {
    const { getByLabelText } = render(<VoiceConversation />);
    
    // 音声認識ボタンをクリック
    const micButton = getByLabelText('音声入力');
    fireEvent.click(micButton);
    
    // SpeechRecognitionがスタートしたことを確認
    const speechInstance = new MockSpeechRecognition();
    expect(speechInstance.start).toHaveBeenCalled();
    
    // 音声認識の結果をシミュレート
    const resultEvent = {
      results: [
        [
          {
            transcript: '音声で入力されたメッセージ',
            confidence: 0.9,
          },
        ],
      ],
      resultIndex: 0,
    };
    
    // 音声認識のコールバックが設定されていれば実行
    if (typeof speechInstance.onresult === 'function') {
      speechInstance.onresult(resultEvent);
    }
    
    // 音声認識の終了をシミュレート
    if (typeof speechInstance.onend === 'function') {
      speechInstance.onend({});
    }
    
    await waitFor(() => {
      expect(screen.getByText('音声で入力されたメッセージ')).toBeInTheDocument();
    });
  });

  // 重要度: 4
  test('感情認識が有効な場合は感情を分析する', async () => {
    const onEmotionDetected = jest.fn();
    render(
      <VoiceConversation 
        emotionRecognition={true}
        onEmotionDetected={onEmotionDetected}
      />
    );
    
    // メッセージを送信
    const input = screen.getByPlaceholderText('Type your message here...');
    const sendButton = screen.getByText('Send');
    
    fireEvent.change(input, { target: { value: '感情分析テスト' } });
    fireEvent.click(sendButton);
    
    // 感情分析API呼び出しのモック
    jest.spyOn(global, 'fetch').mockImplementationOnce(() =>
      Promise.resolve({
        ok: true,
        json: () => Promise.resolve({
          emotion: {
            primary: 'happy',
            confidence: 0.87,
            timestamp: new Date().toISOString()
          }
        }),
      } as Response)
    );
    
    await waitFor(() => {
      expect(onEmotionDetected).toHaveBeenCalledWith(expect.objectContaining({
        primary: 'happy',
        confidence: expect.any(Number)
      }));
    });
  });

  // 重要度: 3
  test('感情認識エラー時には適切に処理される', async () => {
    const mockConsoleError = jest.spyOn(console, 'error').mockImplementation(() => {});
    
    render(<VoiceConversation emotionRecognition={true} />);
    
    // メッセージを送信
    const input = screen.getByPlaceholderText('Type your message here...');
    const sendButton = screen.getByText('Send');
    
    fireEvent.change(input, { target: { value: '感情分析エラーテスト' } });
    fireEvent.click(sendButton);
    
    // 感情分析API呼び出しのモック（エラー）
    jest.spyOn(global, 'fetch').mockImplementationOnce(() =>
      Promise.reject(new Error('感情分析APIエラー'))
    );
    
    await waitFor(() => {
      // エラーがコンソールに出力されることを確認
      expect(mockConsoleError).toHaveBeenCalled();
    });
    
    mockConsoleError.mockRestore();
  });
}); 