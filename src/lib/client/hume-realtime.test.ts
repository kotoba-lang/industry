import { describe, it, expect, vi, beforeEach } from 'vitest';
import { HumeRealtimeEmotionService } from './hume-realtime';
import { HumeFaceResponse, HumeVoiceResponse } from '../actions/hume-service';

// Mock WebSocket
class MockWebSocket {
  onopen: (() => void) | null = null;
  onmessage: ((event: any) => void) | null = null;
  onerror: ((event: any) => void) | null = null;
  onclose: (() => void) | null = null;
  readyState = WebSocket.OPEN;

  constructor(public url: string) {}

  send(data: string): void {
    // Mock implementation
  }

  close(): void {
    this.readyState = WebSocket.CLOSED;
    if (this.onclose) this.onclose();
  }
}

// Mock FileReader
class MockFileReader {
  onloadend: (() => void) | null = null;
  result: string = 'data:image/jpeg;base64,mockBase64Data';

  readAsDataURL(blob: Blob): void {
    setTimeout(() => {
      if (this.onloadend) this.onloadend();
    }, 0);
  }
}

describe('HumeRealtimeEmotionService テスト (優先度: 5)', () => {
  let service: HumeRealtimeEmotionService;
  let mockFaceCallback: (data: HumeFaceResponse) => void;
  let mockVoiceCallback: (data: HumeVoiceResponse) => void;
  let mockErrorCallback: (error: Event) => void;
  let originalWebSocket: typeof WebSocket;
  let originalFileReader: typeof FileReader;

  beforeEach(() => {
    // Setup mock callbacks
    mockFaceCallback = vi.fn();
    mockVoiceCallback = vi.fn();
    mockErrorCallback = vi.fn();

    // Save original globals
    originalWebSocket = global.WebSocket;
    originalFileReader = global.FileReader;

    // Mock WebSocket
    global.WebSocket = MockWebSocket as any;
    
    // Mock FileReader
    global.FileReader = MockFileReader as any;

    // Create service instance
    service = new HumeRealtimeEmotionService(
      'test-api-key',
      mockFaceCallback,
      mockVoiceCallback,
      mockErrorCallback
    );
  });

  afterEach(() => {
    // Restore original globals
    global.WebSocket = originalWebSocket;
    global.FileReader = originalFileReader;
  });

  describe('initWebSocket', () => {
    it('WebSocketを初期化して設定できること', async () => {
      // Spy on WebSocket constructor
      const webSocketSpy = vi.spyOn(global, 'WebSocket');
      
      // Spy on send method
      const sendSpy = vi.fn();
      webSocketSpy.mockImplementation(() => {
        const ws = new MockWebSocket('wss://api.hume.ai/v0/stream/models');
        ws.send = sendSpy;
        return ws as any;
      });

      // Call initWebSocket
      await service.initWebSocket(['face', 'prosody']);

      // Check WebSocket was created with correct URL
      expect(webSocketSpy).toHaveBeenCalledWith('wss://api.hume.ai/v0/stream/models');
      
      // Get the created websocket instance
      const wsInstance = webSocketSpy.mock.results[0].value;
      
      // Trigger onopen
      wsInstance.onopen!();
      
      // Verify configuration message was sent
      expect(sendSpy).toHaveBeenCalledWith(JSON.stringify({
        type: 'configuration',
        apiKey: 'test-api-key',
        models: {
          face: {},
          prosody: {}
        }
      }));
    });

    it('WebSocketメッセージを正しく処理できること', async () => {
      // Initialize WebSocket
      await service.initWebSocket();
      
      // Get WebSocket instance
      const ws = (global.WebSocket as any).mock.instances[0];
      
      // Create mock face prediction data
      const faceData = {
        type: 'prediction',
        models: {
          face: {
            emotions: [
              { name: 'happiness', score: 0.85 },
              { name: 'sadness', score: 0.1 }
            ],
            bbox: { x: 10, y: 20, w: 100, h: 100 }
          }
        }
      };
      
      // Trigger onmessage with face data
      if (ws.onmessage) {
        ws.onmessage({ data: JSON.stringify(faceData) });
      }
      
      // Verify face callback was called with correct data
      expect(mockFaceCallback).toHaveBeenCalledWith(faceData.models.face);
      
      // Create mock voice prediction data
      const voiceData = {
        type: 'prediction',
        models: {
          prosody: {
            emotions: [
              { name: 'excitement', score: 0.75 },
              { name: 'calmness', score: 0.2 }
            ],
            metadata: {
              duration_ms: 2500,
              speaking_rate: 1.2
            }
          }
        }
      };
      
      // Trigger onmessage with voice data
      if (ws.onmessage) {
        ws.onmessage({ data: JSON.stringify(voiceData) });
      }
      
      // Verify voice callback was called with correct data
      expect(mockVoiceCallback).toHaveBeenCalledWith(voiceData.models.prosody);
    });
    
    it('WebSocketエラーが発生した場合コールバックを呼び出すこと', async () => {
      // Initialize WebSocket
      await service.initWebSocket();
      
      // Get WebSocket instance
      const ws = (global.WebSocket as any).mock.instances[0];
      
      // Create mock error
      const mockError = new Event('error');
      
      // Trigger onerror
      if (ws.onerror) {
        ws.onerror(mockError);
      }
      
      // Verify error callback was called
      expect(mockErrorCallback).toHaveBeenCalledWith(mockError);
    });
  });

  describe('sendImageData', () => {
    it('画像データを正しく送信できること', async () => {
      // Initialize WebSocket
      await service.initWebSocket();
      
      // Get WebSocket instance and spy on send method
      const ws = (global.WebSocket as any).mock.instances[0];
      const sendSpy = vi.spyOn(ws, 'send');
      
      // Create mock blob
      const imageBlob = new Blob(['mock image data'], { type: 'image/jpeg' });
      
      // Call sendImageData
      await service.sendImageData(imageBlob);
      
      // Wait for FileReader onloadend to be called
      await new Promise(resolve => setTimeout(resolve, 10));
      
      // Verify WebSocket.send was called with correct data
      expect(sendSpy).toHaveBeenCalledWith(JSON.stringify({
        type: 'frame',
        format: 'image/jpeg;base64',
        data: 'mockBase64Data'
      }));
    });
    
    it('WebSocket接続が確立されていない場合エラーをスローすること', async () => {
      // Create service without initializing WebSocket
      const newService = new HumeRealtimeEmotionService(
        'test-api-key',
        mockFaceCallback,
        mockVoiceCallback,
        mockErrorCallback
      );
      
      // Create mock blob
      const imageBlob = new Blob(['mock image data'], { type: 'image/jpeg' });
      
      // Expect error to be thrown
      await expect(newService.sendImageData(imageBlob))
        .rejects.toThrow('WebSocket connection not established');
    });
  });

  describe('sendAudioData', () => {
    it('音声データを正しく送信できること', async () => {
      // Initialize WebSocket
      await service.initWebSocket();
      
      // Get WebSocket instance and spy on send method
      const ws = (global.WebSocket as any).mock.instances[0];
      const sendSpy = vi.spyOn(ws, 'send');
      
      // Create mock blob
      const audioBlob = new Blob(['mock audio data'], { type: 'audio/wav' });
      
      // Call sendAudioData
      await service.sendAudioData(audioBlob);
      
      // Wait for FileReader onloadend to be called
      await new Promise(resolve => setTimeout(resolve, 10));
      
      // Verify WebSocket.send was called with correct data
      expect(sendSpy).toHaveBeenCalledWith(JSON.stringify({
        type: 'audio',
        format: 'audio/wav;base64',
        data: 'mockBase64Data'
      }));
    });
  });

  describe('closeConnection', () => {
    it('WebSocket接続を正しく閉じること', async () => {
      // Initialize WebSocket
      await service.initWebSocket();
      
      // Get WebSocket instance and spy on close method
      const ws = (global.WebSocket as any).mock.instances[0];
      const closeSpy = vi.spyOn(ws, 'close');
      
      // Call closeConnection
      service.closeConnection();
      
      // Verify WebSocket.close was called
      expect(closeSpy).toHaveBeenCalled();
    });
  });
}); 