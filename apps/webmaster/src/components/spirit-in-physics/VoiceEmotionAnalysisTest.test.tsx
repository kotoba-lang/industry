/**
 * @jest-environment jsdom
 */
import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import VoiceEmotionAnalysisTest from './VoiceEmotionAnalysisTest';
import { analyzeVoice } from '@/lib/actions/hume-service';

// Mock lucide-react icons
jest.mock('lucide-react', () => ({
  AlertCircle: () => <div data-testid="alert-icon" />,
  Clock: () => <div data-testid="clock-icon" />,
}));

// Mock the server action
jest.mock('@/lib/actions/hume-service', () => ({
  analyzeVoice: jest.fn(),
}));

// Mock MediaRecorder
class MockMediaRecorder {
  ondataavailable: ((event: any) => void) | null = null;
  onstop: (() => void) | null = null;
  
  constructor(public stream: MediaStream) {}
  
  start() {}
  
  stop() {
    // Simulate data available event
    if (this.ondataavailable) {
      this.ondataavailable({ data: new Blob(["test audio data"], { type: 'audio/wav' }) });
    }
    
    // Simulate stop event
    if (this.onstop) {
      this.onstop();
    }
  }
}

// Mock MediaStream
class MockMediaStream {
  getTracks() {
    return [{ stop: jest.fn() }];
  }
}

// Mock global navigator.mediaDevices
Object.defineProperty(global.navigator, 'mediaDevices', {
  value: {
    getUserMedia: jest.fn().mockResolvedValue(new MockMediaStream()),
  },
  writable: true,
});

// Mock global MediaRecorder
global.MediaRecorder = MockMediaRecorder as any;

describe('音声感情分析テストコンポーネント', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    
    // Setup mock for analyzeVoice
    (analyzeVoice as jest.Mock).mockResolvedValue({
      emotions: [
        { name: 'Joy', score: 0.8 },
        { name: 'Calmness', score: 0.6 },
        { name: 'Excitement', score: 0.4 },
        { name: 'Concentration', score: 0.3 },
        { name: 'Sadness', score: 0.1 },
      ],
      confidence: 0.9,
    });
  });
  
  test('重要度5: コンポーネントが正しくレンダリングされること', () => {
    render(<VoiceEmotionAnalysisTest />);
    
    // 初期状態の確認
    expect(screen.getByText('音声感情分析テスト')).toBeInTheDocument();
    expect(screen.getByText('録音ボタンを押して開始')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '録音開始' })).toBeInTheDocument();
  });
  
  test('重要度5: 録音開始と停止が機能すること', async () => {
    render(<VoiceEmotionAnalysisTest />);
    
    // 録音開始
    fireEvent.click(screen.getByRole('button', { name: '録音開始' }));
    
    // マイクへのアクセスが要求されること
    expect(navigator.mediaDevices.getUserMedia).toHaveBeenCalledWith({ audio: true });
    
    // 録音中の状態に変わること
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '録音停止' })).toBeInTheDocument();
    });
    
    // 録音停止
    fireEvent.click(screen.getByRole('button', { name: '録音停止' }));
    
    // 処理中の表示を確認
    await waitFor(() => {
      expect(screen.getByText('音声を分析中...')).toBeInTheDocument();
      expect(screen.getByTestId('clock-icon')).toBeInTheDocument();
    });
    
    // 録音処理が完了するまで待機
    await waitFor(() => {
      expect(analyzeVoice).toHaveBeenCalled();
    });
    
    // 結果が表示されること
    await waitFor(() => {
      expect(screen.getByText('検出された感情')).toBeInTheDocument();
      expect(screen.getByText('Joy')).toBeInTheDocument();
      expect(screen.getByText('80.0%')).toBeInTheDocument();
    });
  });
  
  test('重要度4: APIエラーのハンドリングが機能すること', async () => {
    // エラーを発生させるようにモックを変更
    (analyzeVoice as jest.Mock).mockRejectedValue(new Error('API error'));
    
    render(<VoiceEmotionAnalysisTest />);
    
    // 録音開始
    fireEvent.click(screen.getByRole('button', { name: '録音開始' }));
    
    // 録音停止
    await waitFor(() => {
      fireEvent.click(screen.getByRole('button', { name: '録音停止' }));
    });
    
    // エラーメッセージが表示されること
    await waitFor(() => {
      expect(screen.getByTestId('alert-icon')).toBeInTheDocument();
      expect(screen.getByText(/音声分析中にエラーが発生しました/)).toBeInTheDocument();
    });
  });
  
  test('重要度4: タイムアウトエラーが適切に処理されること', async () => {
    // タイムアウトエラーを発生させるようにモックを変更
    (analyzeVoice as jest.Mock).mockRejectedValue(
      new Error('Job abc123 did not complete within 120 seconds')
    );
    
    render(<VoiceEmotionAnalysisTest />);
    
    // 録音開始
    fireEvent.click(screen.getByRole('button', { name: '録音開始' }));
    
    // 録音停止
    await waitFor(() => {
      fireEvent.click(screen.getByRole('button', { name: '録音停止' }));
    });
    
    // 特定のタイムアウトエラーメッセージが表示されること
    await waitFor(() => {
      expect(screen.getByTestId('alert-icon')).toBeInTheDocument();
      expect(screen.getByText('音声分析がタイムアウトしました。もう一度試すか、より短い録音で試してください。')).toBeInTheDocument();
    });
  });
  
  test('重要度3: タイマー表示が機能すること', async () => {
    jest.useFakeTimers();
    
    render(<VoiceEmotionAnalysisTest />);
    
    // 録音開始
    fireEvent.click(screen.getByRole('button', { name: '録音開始' }));
    
    // 1秒経過
    act(() => {
      jest.advanceTimersByTime(1000);
    });
    
    // タイマーが表示されること
    expect(screen.getByText('00:01')).toBeInTheDocument();
    
    // さらに10秒経過
    act(() => {
      jest.advanceTimersByTime(10000);
    });
    
    // タイマーが更新されること
    expect(screen.getByText('00:11')).toBeInTheDocument();
    
    // 録音停止
    fireEvent.click(screen.getByRole('button', { name: '録音停止' }));
    
    // 処理時間のタイマーが表示される
    await waitFor(() => {
      expect(screen.getByText('処理時間: 00:00')).toBeInTheDocument();
    });
    
    // 処理時間が進む
    act(() => {
      jest.advanceTimersByTime(5000);
    });
    
    // 処理時間が表示される
    expect(screen.getByText('処理時間: 00:05')).toBeInTheDocument();
    
    jest.useRealTimers();
  });
  
  test('重要度3: 長時間処理中の警告メッセージが表示されること', async () => {
    jest.useFakeTimers();
    
    // 応答を遅らせるようにモックを設定
    (analyzeVoice as jest.Mock).mockImplementation(() => {
      return new Promise(resolve => {
        setTimeout(() => {
          resolve({
            emotions: [
              { name: 'Joy', score: 0.8 },
            ],
          });
        }, 40000); // 実際には実行されない（タイマーを手動で進める）
      });
    });
    
    render(<VoiceEmotionAnalysisTest />);
    
    // 録音開始と停止
    fireEvent.click(screen.getByRole('button', { name: '録音開始' }));
    fireEvent.click(screen.getByRole('button', { name: '録音停止' }));
    
    // 処理時間が30秒経過するまで待機
    act(() => {
      jest.advanceTimersByTime(31000);
    });
    
    // 警告メッセージが表示されること
    await waitFor(() => {
      expect(screen.getByText('音声の分析には時間がかかることがあります。')).toBeInTheDocument();
    });
    
    jest.useRealTimers();
  });
}); 