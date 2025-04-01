/**
 * @jest-environment jsdom
 */

import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import EmotionAnalysis from './EmotionAnalysis';

// Mock fetch API
global.fetch = jest.fn().mockImplementation(() =>
  Promise.resolve({
    ok: true,
    json: () => Promise.resolve({
      success: true,
      records: [
        {
          stimulusWord: 'head',
          responseWord: 'brain',
          reactionTimeMs: 1200,
          recordedAt: '2023-04-01T12:00:00Z',
          facialEmotions: [
            { emotionName: 'Joy', score: 0.8 },
            { emotionName: 'Surprise', score: 0.3 },
            { emotionName: 'Neutral', score: 0.1 }
          ],
          voiceEmotions: [
            { emotionName: 'Joy', score: 0.7 },
            { emotionName: 'Surprise', score: 0.4 },
            { emotionName: 'Neutral', score: 0.2 }
          ]
        },
        {
          stimulusWord: 'green',
          responseWord: 'grass',
          reactionTimeMs: 800,
          recordedAt: '2023-04-01T12:01:00Z',
          facialEmotions: [
            { emotionName: 'Joy', score: 0.6 },
            { emotionName: 'Surprise', score: 0.2 },
            { emotionName: 'Neutral', score: 0.3 }
          ],
          voiceEmotions: [
            { emotionName: 'Joy', score: 0.5 },
            { emotionName: 'Surprise', score: 0.3 },
            { emotionName: 'Neutral', score: 0.4 }
          ]
        },
        {
          stimulusWord: 'water',
          responseWord: 'ocean',
          reactionTimeMs: 1000,
          recordedAt: '2023-04-01T12:02:00Z',
          facialEmotions: [
            { emotionName: 'Joy', score: 0.4 },
            { emotionName: 'Surprise', score: 0.5 },
            { emotionName: 'Neutral', score: 0.2 }
          ],
          voiceEmotions: [
            { emotionName: 'Joy', score: 0.3 },
            { emotionName: 'Surprise', score: 0.6 },
            { emotionName: 'Neutral', score: 0.3 }
          ]
        }
      ]
    })
  })
);

// Mock window.matchMedia for recharts
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: jest.fn().mockImplementation(query => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })),
});

// Mock ResizeObserver
global.ResizeObserver = jest.fn().mockImplementation(() => ({
  observe: jest.fn(),
  unobserve: jest.fn(),
  disconnect: jest.fn(),
}));

describe('EmotionAnalysis コンポーネント', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  test('重要度: 5 - コンポーネントが正しくレンダリングされること', async () => {
    render(<EmotionAnalysis userId="user123" assessmentId="" />);
    
    // ローディング状態の確認
    expect(screen.getByRole('heading', { name: '感情分析結果' })).toBeInTheDocument();
    
    // データ読み込み後の表示確認
    await waitFor(() => {
      expect(screen.getByText('顔の感情分析')).toBeInTheDocument();
      expect(screen.getByText('声の感情分析')).toBeInTheDocument();
    });
  });

  test('重要度: 4 - 感情データが正しく表示されること', async () => {
    render(<EmotionAnalysis userId="user123" assessmentId="" />);
    
    await waitFor(() => {
      // 感情選択ドロップダウンが表示されていることを確認
      expect(screen.getByLabelText('顔の感情を選択')).toBeInTheDocument();
      
      // 最も強い感情セクションが表示されていることを確認
      expect(screen.getByText('最も強い感情')).toBeInTheDocument();
      
      // 相関係数セクションが表示されていることを確認
      expect(screen.getByText(/反応時間と感情の相関/)).toBeInTheDocument();
    });
  });

  test('重要度: 4 - タブ切り替えが正しく機能すること', async () => {
    render(<EmotionAnalysis userId="user123" assessmentId="" />);
    
    await waitFor(() => {
      // 初期状態では顔の感情分析タブが選択されていることを確認
      expect(screen.getByText('顔の感情分析')).toHaveAttribute('data-state', 'active');
      
      // 声の感情分析タブをクリック
      fireEvent.click(screen.getByText('声の感情分析'));
    });
    
    // 声の感情分析タブが選択されていることを確認
    await waitFor(() => {
      expect(screen.getByText('声の感情分析')).toHaveAttribute('data-state', 'active');
    });
  });

  test('重要度: 3 - 感情選択が正しく機能すること', async () => {
    render(<EmotionAnalysis userId="user123" assessmentId="" />);
    
    await waitFor(() => {
      const selectElement = screen.getByLabelText('顔の感情を選択');
      expect(selectElement).toBeInTheDocument();
      
      // 初期値が'Joy'であることを確認
      expect(selectElement).toHaveValue('Joy');
      
      // 'Surprise'を選択
      fireEvent.change(selectElement, { target: { value: 'Surprise' } });
    });
    
    // 選択が変更されたことを確認
    await waitFor(() => {
      expect(screen.getByLabelText('顔の感情を選択')).toHaveValue('Surprise');
    });
  });

  test('重要度: 3 - APIエラー時にエラーメッセージが表示されること', async () => {
    // エラーレスポンスをモック
    global.fetch = jest.fn().mockImplementationOnce(() =>
      Promise.resolve({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error'
      })
    );
    
    render(<EmotionAnalysis userId="user123" assessmentId="" />);
    
    // エラーメッセージが表示されることを確認
    await waitFor(() => {
      expect(screen.getByText('感情データの取得に失敗しました。')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '再試行' })).toBeInTheDocument();
    });
  });

  test('重要度: 2 - 再試行ボタンが機能すること', async () => {
    // エラーレスポンスをモック
    global.fetch = jest.fn().mockImplementationOnce(() =>
      Promise.resolve({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error'
      })
    );
    
    // window.location.reload をモック
    const mockReload = jest.fn();
    Object.defineProperty(window, 'location', {
      value: { reload: mockReload },
      writable: true
    });
    
    render(<EmotionAnalysis userId="user123" assessmentId="" />);
    
    // エラー表示を待つ
    await waitFor(() => {
      const retryButton = screen.getByRole('button', { name: '再試行' });
      expect(retryButton).toBeInTheDocument();
      
      // 再試行ボタンをクリック
      fireEvent.click(retryButton);
    });
    
    // reload が呼ばれたことを確認
    expect(mockReload).toHaveBeenCalledTimes(1);
  });

  test('重要度: 3 - データがない場合に適切なメッセージが表示されること', async () => {
    // 空のデータをモック
    global.fetch = jest.fn().mockImplementationOnce(() =>
      Promise.resolve({
        ok: true,
        json: () => Promise.resolve({
          success: true,
          records: []
        })
      })
    );
    
    render(<EmotionAnalysis userId="user123" assessmentId="" />);
    
    // データなしメッセージが表示されることを確認
    await waitFor(() => {
      expect(screen.getByText('顔の感情データがありません')).toBeInTheDocument();
    });
    
    // 声の感情分析タブに切り替え
    fireEvent.click(screen.getByText('声の感情分析'));
    
    // 声のデータなしメッセージが表示されることを確認
    await waitFor(() => {
      expect(screen.getByText('音声の感情データがありません')).toBeInTheDocument();
    });
  });
}); 