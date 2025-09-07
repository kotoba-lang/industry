import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { HumeRealtimeEmotionService } from "./hume-realtime";
import { HumeFaceResponse, HumeVoiceResponse } from "../actions/hume-service";

// Mock WebSocket
class MockWebSocket {
  onopen: (() => void) | null = null;
  onmessage: ((event: any) => void) | null = null;
  onerror: ((event: any) => void) | null = null;
  onclose: (() => void) | null = null;
  readyState = WebSocket.OPEN;
  send: (data: string) => void = vi.fn();

  constructor(public url: string) {}

  close(): void {
    this.readyState = WebSocket.CLOSED;
    if (this.onclose) this.onclose();
  }
}

// Mock FileReader
class MockFileReader {
  onloadend: (() => void) | null = null;
  result: string = "data:image/jpeg;base64,mockBase64Data";

  readAsDataURL(blob: Blob): void {
    // Immediately trigger onloadend in tests
    if (this.onloadend) this.onloadend();
  }
}

describe("HumeRealtimeEmotionService テスト (優先度: 5)", () => {
  let service: HumeRealtimeEmotionService;
  let mockFaceCallback: (data: HumeFaceResponse) => void;
  let mockVoiceCallback: (data: HumeVoiceResponse) => void;
  let mockErrorCallback: (error: Event) => void;
  let originalWebSocket: typeof WebSocket;
  let originalFileReader: typeof FileReader;
  let mockWs: MockWebSocket;

  beforeEach(() => {
    // Setup mock callbacks
    mockFaceCallback = vi.fn();
    mockVoiceCallback = vi.fn();
    mockErrorCallback = vi.fn();

    // Save original globals
    originalWebSocket = global.WebSocket;
    originalFileReader = global.FileReader;

    // Create a mock WebSocket instance
    mockWs = new MockWebSocket("wss://api.hume.ai/v0/stream/models");

    // Mock WebSocket constructor
    global.WebSocket = vi.fn().mockImplementation(() => mockWs) as any;

    // Mock FileReader
    global.FileReader = MockFileReader as any;

    // Create service instance
    service = new HumeRealtimeEmotionService(
      "test-api-key",
      mockFaceCallback,
      mockVoiceCallback,
      mockErrorCallback,
    );
  });

  afterEach(() => {
    // Restore original globals
    global.WebSocket = originalWebSocket;
    global.FileReader = originalFileReader;
    vi.restoreAllMocks();
  });

  describe("initWebSocket", () => {
    it("WebSocketを初期化して設定できること", async () => {
      // Call initWebSocket
      await service.initWebSocket(["face", "prosody"]);

      // Check WebSocket was created with correct URL
      expect(global.WebSocket).toHaveBeenCalledWith(
        "wss://api.hume.ai/v0/stream/models",
      );

      // Trigger onopen
      mockWs.onopen!();

      // Verify configuration message was sent
      expect(mockWs.send).toHaveBeenCalledWith(JSON.stringify({
        type: "configuration",
        apiKey: "test-api-key",
        models: {
          face: {},
          prosody: {},
        },
      }));
    });

    it("WebSocketメッセージを正しく処理できること", async () => {
      // Initialize WebSocket
      await service.initWebSocket();

      // Create mock face prediction data
      const faceData = {
        type: "prediction",
        models: {
          face: {
            emotions: [
              { name: "happiness", score: 0.85 },
              { name: "sadness", score: 0.1 },
            ],
            bbox: { x: 10, y: 20, w: 100, h: 100 },
          },
        },
      };

      // Trigger onmessage with face data
      mockWs.onmessage!({ data: JSON.stringify(faceData) });

      // Verify face callback was called with correct data
      expect(mockFaceCallback).toHaveBeenCalledWith(faceData.models.face);

      // Create mock voice prediction data
      const voiceData = {
        type: "prediction",
        models: {
          prosody: {
            emotions: [
              { name: "excitement", score: 0.75 },
              { name: "calmness", score: 0.2 },
            ],
            metadata: {
              duration_ms: 2500,
              speaking_rate: 1.2,
            },
          },
        },
      };

      // Trigger onmessage with voice data
      mockWs.onmessage!({ data: JSON.stringify(voiceData) });

      // Verify voice callback was called with correct data
      expect(mockVoiceCallback).toHaveBeenCalledWith(voiceData.models.prosody);
    });

    it("WebSocketエラーが発生した場合コールバックを呼び出すこと", async () => {
      // Initialize WebSocket
      await service.initWebSocket();

      // Create mock error
      const mockError = new Event("error");

      // Trigger onerror
      mockWs.onerror!(mockError);

      // Verify error callback was called
      expect(mockErrorCallback).toHaveBeenCalledWith(mockError);
    });
  });

  describe("sendImageData", () => {
    it("画像データを正しく送信できること", async () => {
      // Initialize WebSocket
      await service.initWebSocket();
      mockWs.onopen!(); // Ensure WebSocket is open

      // Create mock blob
      const imageBlob = new Blob(["mock image data"], { type: "image/jpeg" });

      // Call sendImageData
      await service.sendImageData(imageBlob);

      // Verify WebSocket.send was called with correct data
      expect(mockWs.send).toHaveBeenCalledWith(JSON.stringify({
        type: "frame",
        format: "image/jpeg;base64",
        data: "mockBase64Data",
      }));
    });

    it("WebSocket接続が確立されていない場合エラーをスローすること", async () => {
      // Create service without initializing WebSocket
      const newService = new HumeRealtimeEmotionService(
        "test-api-key",
        mockFaceCallback,
        mockVoiceCallback,
        mockErrorCallback,
      );

      // Create mock blob
      const imageBlob = new Blob(["mock image data"], { type: "image/jpeg" });

      // Expect error to be thrown
      await expect(newService.sendImageData(imageBlob))
        .rejects.toThrow("WebSocket connection not established");
    });
  });

  describe("sendAudioData", () => {
    it("音声データを正しく送信できること", async () => {
      // Initialize WebSocket
      await service.initWebSocket();
      mockWs.onopen!(); // Ensure WebSocket is open

      // Create mock blob
      const audioBlob = new Blob(["mock audio data"], { type: "audio/wav" });

      // Call sendAudioData
      await service.sendAudioData(audioBlob);

      // Verify WebSocket.send was called with correct data
      expect(mockWs.send).toHaveBeenCalledWith(JSON.stringify({
        type: "audio",
        format: "audio/wav;base64",
        data: "mockBase64Data",
      }));
    });
  });

  describe("closeConnection", () => {
    it("WebSocket接続を正しく閉じること", async () => {
      // Initialize WebSocket
      await service.initWebSocket();

      // Spy on close method
      const closeSpy = vi.spyOn(mockWs, "close");

      // Call closeConnection
      service.closeConnection();

      // Verify WebSocket.close was called
      expect(closeSpy).toHaveBeenCalled();
    });
  });
});
