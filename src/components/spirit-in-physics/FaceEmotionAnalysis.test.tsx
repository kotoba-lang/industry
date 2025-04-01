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

HTMLCanvasElement.prototype.getContext = vi.fn(() => mockCanvasContext as any);
HTMLCanvasElement.prototype.toBlob = vi.fn((callback) => callback(new Blob(['mock data'], { type: 'image/jpeg' })));

describe('FaceEmotionAnalysis コンポーネント (優先度: 5)', () => {
  let mockHumeService: {
    initWebSocket: vi.Mock;
    sendImageData: vi.Mock;
    closeConnection: vi.Mock;
  };
  
  beforeEach(() => {
    vi.resetAllMocks();
    
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
      getTracks: () => [{ stop: vi.fn() }],
    } as any);
    
    // Mock window.requestAnimationFrame
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => {
      cb(0);
      return 0;
    });
    
    // Mock setTimeout
    vi.useFakeTimers();
  });
  
  afterEach(() => {
    vi.useRealTimers();
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
    
    // getUserMedia, initWebSocket が呼ばれたことを確認
    expect(navigator.mediaDevices.getUserMedia).toHaveBeenCalledWith({
      video: { width: 640, height: 480 },
      audio: false
    });
    
    // Resolve all promises
    await vi.runAllTimersAsync();
    
    // Check if initWebSocket was called
    expect(mockHumeService.initWebSocket).toHaveBeenCalledWith(['face']);
    
    // ボタンが「カメラを停止」に変わることを確認
    expect(screen.getByText('カメラを停止')).toBeInTheDocument();
  });
  
  it('カメラ停止ボタンがクリックされたときにカメラを停止すること', async () => {
    render(<FaceEmotionAnalysis />);
    
    // カメラを開始
    fireEvent.click(screen.getByText('カメラを開始'));
    
    // Resolve all promises
    await vi.runAllTimersAsync();
    
    // カメラを停止
    fireEvent.click(screen.getByText('カメラを停止'));
    
    // closeConnection が呼ばれたことを確認
    expect(mockHumeService.closeConnection).toHaveBeenCalled();
    
    // ボタンが「カメラを開始」に戻ることを確認
    expect(screen.getByText('カメラを開始')).toBeInTheDocument();
  });
  
  it('感情データを受信すると表示を更新すること', async () => {
    render(<FaceEmotionAnalysis />);
    
    // カメラを開始
    fireEvent.click(screen.getByText('カメラを開始'));
    
    // Resolve all promises
    await vi.runAllTimersAsync();
    
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
    vi.mocked(navigator.mediaDevices.getUserMedia).mockRejectedValue(
      new Error('カメラへのアクセスが拒否されました')
    );
    
    render(<FaceEmotionAnalysis />);
    
    // カメラ開始ボタンをクリック
    fireEvent.click(screen.getByText('カメラを開始'));
    
    // Resolve all promises
    await vi.runAllTimersAsync();
    
    // エラーメッセージが表示されることを確認
    expect(screen.getByText('カメラへのアクセスが許可されていません')).toBeInTheDocument();
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