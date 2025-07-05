import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import FaceEmotionAnalysis from './FaceEmotionAnalysis';
import { HumeRealtimeEmotionService } from '@/lib/client/hume-realtime';
import '@testing-library/jest-dom';

// Mock HumeRealtimeEmotionService
vi.mock('@/lib/client/hume-realtime', () => ({
  HumeRealtimeEmotionService: vi.fn()
}));

// Mock navigator.mediaDevices
Object.defineProperty(global.navigator, 'mediaDevices', {
  value: {
    getUserMedia: vi.fn()
  },
  writable: true
});

// Mock canvas context
const mockCanvasContext = {
  drawImage: vi.fn()
};

// Mock canvas methods
HTMLCanvasElement.prototype.getContext = vi.fn(() => mockCanvasContext as any);
HTMLCanvasElement.prototype.toBlob = vi.fn((callback) => {
  callback(new Blob(['mock data'], { type: 'image/jpeg' }));
});

describe('FaceEmotionAnalysis コンポーネント (優先度: 5)', () => {
  let mockHumeService: {
    initWebSocket: vi.Mock;
    sendImageData: vi.Mock;
    closeConnection: vi.Mock;
  };
  
  // Mock track stop function
  const mockTrackStop = vi.fn();
  
  // Mock requestAnimationFrame and setTimeout
  let requestAnimationFrameCallback: ((time: number) => void) | null = null;
  let originalRequestAnimationFrame: typeof window.requestAnimationFrame;
  let originalSetTimeout: typeof window.setTimeout;
  
  beforeEach(() => {
    vi.resetAllMocks();
    
    // Save original functions
    originalRequestAnimationFrame = window.requestAnimationFrame;
    originalSetTimeout = window.setTimeout;
    
    // Mock environment variables
    vi.stubEnv('NEXT_PUBLIC_HUME_API_KEY', 'test-api-key');
    
    // Setup mock HumeRealtimeEmotionService
    mockHumeService = {
      initWebSocket: vi.fn().mockResolvedValue(undefined),
      sendImageData: vi.fn().mockResolvedValue(undefined),
      closeConnection: vi.fn()
    };
    
    vi.mocked(HumeRealtimeEmotionService).mockImplementation(
      (apiKey, handleFaceData, handleVoiceData, handleError) => {
        // Store the callbacks so we can trigger them in tests
        (mockHumeService as any).handleFaceData = handleFaceData;
        (mockHumeService as any).handleVoiceData = handleVoiceData;
        (mockHumeService as any).handleError = handleError;
        
        return mockHumeService as any;
      }
    );
    
    // Mock getUserMedia
    vi.mocked(navigator.mediaDevices.getUserMedia).mockResolvedValue({
      getTracks: () => [{ stop: mockTrackStop }],
    } as any);
    
    // Mock requestAnimationFrame to capture the callback
    window.requestAnimationFrame = vi.fn((callback) => {
      requestAnimationFrameCallback = callback;
      return 1;
    });
    
    // Mock setTimeout to execute callback immediately
    window.setTimeout = vi.fn((callback: Function) => {
      if (typeof callback === 'function') {
        callback();
      }
      return 1 as any;
    });
  });
  
  afterEach(() => {
    // Restore original functions
    window.requestAnimationFrame = originalRequestAnimationFrame;
    window.setTimeout = originalSetTimeout;
    vi.restoreAllMocks();
  });
  
  it('正しく表示されること', () => {
    render(<FaceEmotionAnalysis />);
    
    expect(screen.getByText('カメラを開始')).toBeInTheDocument();
    expect(screen.getByText('カメラが停止しています')).toBeInTheDocument();
    expect(screen.getByText('リアルタイム感情分析')).toBeInTheDocument();
    expect(screen.getByText('カメラを開始すると感情分析が表示されます')).toBeInTheDocument();
  });
  
  it('カメラボタンがクリックされたときにカメラを開始すること', async () => {
    render(<FaceEmotionAnalysis />);
    
    // カメラ開始ボタンをクリック
    fireEvent.click(screen.getByText('カメラを開始'));
    
    // getUserMedia が呼ばれたことを確認
    expect(navigator.mediaDevices.getUserMedia).toHaveBeenCalledWith({
      video: { width: 640, height: 480 },
      audio: false
    });
    
    // Wait for async operations to complete
    await waitFor(() => {
      expect(mockHumeService.initWebSocket).toHaveBeenCalledWith(['face']);
    });
    
    // ボタンが「カメラを停止」に変わることを確認
    expect(screen.getByText('カメラを停止')).toBeInTheDocument();
    
    // Trigger the requestAnimationFrame callback to simulate frame processing
    if (requestAnimationFrameCallback) {
      act(() => {
        requestAnimationFrameCallback(0);
      });
    }
    
    // Verify sendImageData was called
    expect(mockHumeService.sendImageData).toHaveBeenCalled();
  });
  
  it('カメラ停止ボタンがクリックされたときにカメラを停止すること', async () => {
    render(<FaceEmotionAnalysis />);
    
    // カメラを開始
    fireEvent.click(screen.getByText('カメラを開始'));
    
    // Wait for async operations to complete
    await waitFor(() => {
      expect(screen.getByText('カメラを停止')).toBeInTheDocument();
    });
    
    // カメラを停止
    fireEvent.click(screen.getByText('カメラを停止'));
    
    // closeConnection が呼ばれたことを確認
    expect(mockHumeService.closeConnection).toHaveBeenCalled();
    
    // Track.stop が呼ばれたことを確認
    expect(mockTrackStop).toHaveBeenCalled();
    
    // ボタンが「カメラを開始」に戻ることを確認
    expect(screen.getByText('カメラを開始')).toBeInTheDocument();
  });
  
  it('感情データを受信すると表示を更新すること', async () => {
    render(<FaceEmotionAnalysis />);
    
    // カメラを開始
    fireEvent.click(screen.getByText('カメラを開始'));
    
    // Wait for async operations to complete
    await waitFor(() => {
      expect(screen.getByText('カメラを停止')).toBeInTheDocument();
    });
    
    // 感情データをシミュレート
    act(() => {
      (mockHumeService as any).handleFaceData({
        emotions: [
          { name: 'happiness', score: 0.8 },
          { name: 'sadness', score: 0.2 }
        ]
      });
    });
    
    // 表示が更新されることを確認
    expect(screen.getByText('happiness')).toBeInTheDocument();
    expect(screen.getByText('80%')).toBeInTheDocument();
    expect(screen.getByText('sadness')).toBeInTheDocument();
    expect(screen.getByText('20%')).toBeInTheDocument();
  });
  
  it('getUserMediaが失敗したらエラーを表示すること', async () => {
    // getUserMediaを失敗するようにモック
    vi.mocked(navigator.mediaDevices.getUserMedia).mockRejectedValueOnce(
      new Error('カメラへのアクセスが拒否されました')
    );
    
    render(<FaceEmotionAnalysis />);
    
    // カメラ開始ボタンをクリック
    fireEvent.click(screen.getByText('カメラを開始'));
    
    // Wait for error message to appear
    await waitFor(() => {
      expect(screen.getByText('カメラへのアクセスが許可されていません')).toBeInTheDocument();
    });
  });
  
  it('Hume APIキーが設定されていない場合はエラーを表示すること', async () => {
    // APIキーを未設定にする
    vi.unstubEnv('NEXT_PUBLIC_HUME_API_KEY');
    process.env.NEXT_PUBLIC_HUME_API_KEY = '';
    
    render(<FaceEmotionAnalysis />);
    
    // エラーメッセージが表示されることを確認
    expect(screen.getByText('Hume API キーが設定されていません')).toBeInTheDocument();
  });
}); 