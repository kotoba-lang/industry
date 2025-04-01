import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { VoiceConversation } from './index';
import { HumeClient } from 'hume';

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
}); 