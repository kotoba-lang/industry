/**
 * @jest-environment jsdom
 */
import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import VoiceEmotionAnalysisWebSocket from './VoiceEmotionAnalysisWebSocket';
import { HumeRealtimeEmotionService, ConnectionState } from '@/lib/client/hume-realtime';

// Mock the HumeRealtimeEmotionService class
jest.mock('@/lib/client/hume-realtime', () => {
  const mockConnectionState = {
    CLOSED: 'CLOSED',
    CONNECTING: 'CONNECTING',
    OPEN: 'OPEN',
    AUTHENTICATED: 'AUTHENTICATED',
    ERROR: 'ERROR',
  };

  const MockHumeService = jest.fn().mockImplementation(() => {
    return {
      initWebSocket: jest.fn().mockResolvedValue(undefined),
      sendAudioData: jest.fn().mockResolvedValue(undefined),
      closeConnection: jest.fn(),
      getConnectionState: jest.fn().mockReturnValue(mockConnectionState.AUTHENTICATED),
      isAuthenticated: jest.fn().mockReturnValue(true),
      isConnected: jest.fn().mockReturnValue(true),
    };
  });

  return {
    HumeRealtimeEmotionService: MockHumeService,
    ConnectionState: mockConnectionState,
  };
});

// Mock lucide-react icons
jest.mock('lucide-react', () => ({
  AlertCircle: () => <div data-testid="alert-icon" />,
  Clock: () => <div data-testid="clock-icon" />,
  Mic: () => <div data-testid="mic-icon" />,
  MicOff: () => <div data-testid="mic-off-icon" />,
}));

// Mock MediaRecorder
class MockMediaRecorder {
  ondataavailable: ((event: any) => void) | null = null;
  
  constructor(public stream: MediaStream) {}
  
  start() {
    // Simulate data available event
    if (this.ondataavailable) {
      setTimeout(() => {
        this.ondataavailable?.({
          data: new Blob(['test audio data'], { type: 'audio/webm' })
        });
      }, 500);
    }
  }
  
  stop() {}
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

describe('音声感情分析WebSocketコンポーネント', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });
  
  test('重要度5: コンポーネントが正しくレンダリングされること', async () => {
    render(<VoiceEmotionAnalysisWebSocket apiKey="test-api-key" />);
    
    // 初期状態の確認
    expect(screen.getByText('リアルタイム音声感情分析')).toBeInTheDocument();
    expect(screen.getByText('録音ボタンを押して開始')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /録音開始/ })).toBeInTheDocument();
  });
  
  test('重要度5: 録音開始と停止が機能すること', async () => {
    render(<VoiceEmotionAnalysisWebSocket apiKey="test-api-key" />);
    
    // HumeRealtimeEmotionServiceが初期化されるのを待つ
    await waitFor(() => {
      expect(HumeRealtimeEmotionService).toHaveBeenCalled();
    });
    
    // 録音開始ボタンをクリック
    fireEvent.click(screen.getByRole('button', { name: /録音開始/ }));
    
    // マイクへのアクセスが要求されること
    expect(navigator.mediaDevices.getUserMedia).toHaveBeenCalledWith({ audio: true });
    
    // WebSocketの初期化が行われること
    await waitFor(() => {
      const mockHumeService = (HumeRealtimeEmotionService as jest.Mock).mock.results[0].value;
      expect(mockHumeService.initWebSocket).toHaveBeenCalledWith(['prosody']);
    });
    
    // 録音停止ボタンが表示されること
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /録音停止/ })).toBeInTheDocument();
    });
    
    // 録音停止ボタンをクリック
    fireEvent.click(screen.getByRole('button', { name: /録音停止/ }));
    
    // 録音開始ボタンが再表示されること
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /録音開始/ })).toBeInTheDocument();
    });
  });
  
  test('重要度4: 接続状態が表示されること', async () => {
    const mockGetState = jest.fn().mockReturnValue(ConnectionState.AUTHENTICATED);
    const mockIsAuth = jest.fn().mockReturnValue(true);
    
    (HumeRealtimeEmotionService as jest.Mock).mockImplementation(() => ({
      initWebSocket: jest.fn().mockResolvedValue(undefined),
      sendAudioData: jest.fn().mockResolvedValue(undefined),
      closeConnection: jest.fn(),
      getConnectionState: mockGetState,
      isAuthenticated: mockIsAuth,
      isConnected: jest.fn().mockReturnValue(true),
    }));
    
    render(<VoiceEmotionAnalysisWebSocket apiKey="test-api-key" />);
    
    // 接続状態が更新されるのを待つ
    jest.useFakeTimers();
    act(() => {
      jest.advanceTimersByTime(1000);
    });
    
    // 接続状態のテキストが表示されていること
    await waitFor(() => {
      expect(screen.getByText('接続済み')).toBeInTheDocument();
    });
    
    jest.useRealTimers();
  });
  
  test('重要度3: 感情データがレンダリングされること', async () => {
    // モックの感情データを設定
    let voiceDataCallback: Function;
    (HumeRealtimeEmotionService as jest.Mock).mockImplementation(
      (_apiKey: string, _faceCallback: Function, voiceCallback: Function, _errorCallback: Function) => {
        voiceDataCallback = voiceCallback;
        return {
          initWebSocket: jest.fn().mockResolvedValue(undefined),
          sendAudioData: jest.fn().mockResolvedValue(undefined),
          closeConnection: jest.fn(),
          getConnectionState: jest.fn().mockReturnValue(ConnectionState.AUTHENTICATED),
          isAuthenticated: jest.fn().mockReturnValue(true),
          isConnected: jest.fn().mockReturnValue(true),
        };
      }
    );
    
    render(<VoiceEmotionAnalysisWebSocket apiKey="test-api-key" />);
    
    // HumeRealtimeEmotionServiceが初期化されるのを待つ
    await waitFor(() => {
      expect(HumeRealtimeEmotionService).toHaveBeenCalled();
    });
    
    // 感情データを送信
    act(() => {
      voiceDataCallback({
        emotions: [
          { name: 'Joy', score: 0.8 },
          { name: 'Calmness', score: 0.6 },
          { name: 'Excitement', score: 0.4 },
        ]
      });
    });
    
    // 感情データが表示されることを確認
    await waitFor(() => {
      expect(screen.getByText('リアルタイム検出感情')).toBeInTheDocument();
      expect(screen.getByText('Joy')).toBeInTheDocument();
      expect(screen.getByText('80.0%')).toBeInTheDocument();
    });
  });
}); 