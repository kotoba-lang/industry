import { describe, it, expect, vi, beforeEach } from 'vitest';
import { 
  analyzeFace, 
  analyzeVoice, 
  saveEmotionAnalysis, 
  getEmotionAnalysis,
  HumeRealtimeEmotionService
} from './hume-service';
import { createSupabaseServerClient } from '@/lib/supabase/server';

// Mock fetch and createSupabaseServerClient
vi.mock('@/lib/supabase/server', () => ({
  createSupabaseServerClient: vi.fn()
}));

// Mock the global fetch function
global.fetch = vi.fn();

describe('Hume感情分析サービス機能 (優先度: 5)', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  describe('analyzeFace', () => {
    it('顔画像から感情を分析できること', async () => {
      // Mock job creation response
      vi.mocked(fetch).mockResolvedValueOnce({
        ok: true,
        json: vi.fn().mockResolvedValue({ job_id: 'job123' })
      } as any);
      
      // Mock job status polling response
      vi.mocked(fetch).mockResolvedValueOnce({
        ok: true,
        json: vi.fn().mockResolvedValue({ status: 'COMPLETED' })
      } as any);
      
      // Mock prediction results
      const mockPredictions = {
        results: [
          {
            models: {
              face: {
                predictions: [
                  {
                    emotions: [
                      { name: 'happiness', score: 0.85 },
                      { name: 'sadness', score: 0.1 }
                    ],
                    bbox: { x: 10, y: 20, w: 100, h: 100 }
                  }
                ]
              }
            }
          }
        ]
      };
      
      vi.mocked(fetch).mockResolvedValueOnce({
        ok: true,
        json: vi.fn().mockResolvedValue(mockPredictions)
      } as any);
      
      // Create a mock blob
      const imageBlob = new Blob(['mock image data'], { type: 'image/jpeg' });
      
      // Call the function
      const result = await analyzeFace(imageBlob, 'api-key-123');
      
      // Verify fetch was called correctly
      expect(fetch).toHaveBeenCalledTimes(3);
      expect(fetch).toHaveBeenNthCalledWith(1, 'https://api.hume.ai/v0/batch/jobs', {
        method: 'POST',
        headers: {
          'X-Hume-Api-Key': 'api-key-123'
        },
        body: expect.any(FormData)
      });
      
      // Verify result
      expect(result).toEqual(mockPredictions);
    });
    
    it('APIエラーが発生した場合エラーをスローすること', async () => {
      // Mock fetch to return an error
      vi.mocked(fetch).mockResolvedValueOnce({
        ok: false,
        status: 400,
        statusText: 'Bad Request'
      } as any);
      
      // Create a mock blob
      const imageBlob = new Blob(['mock image data'], { type: 'image/jpeg' });
      
      // Expect the function to throw
      await expect(analyzeFace(imageBlob, 'api-key-123')).rejects.toThrow('API error: 400 Bad Request');
    });
  });
  
  describe('analyzeVoice', () => {
    it('音声から感情を分析できること', async () => {
      // Mock job creation response
      vi.mocked(fetch).mockResolvedValueOnce({
        ok: true,
        json: vi.fn().mockResolvedValue({ job_id: 'job123' })
      } as any);
      
      // Mock job status polling response
      vi.mocked(fetch).mockResolvedValueOnce({
        ok: true,
        json: vi.fn().mockResolvedValue({ status: 'COMPLETED' })
      } as any);
      
      // Mock prediction results
      const mockPredictions = {
        results: [
          {
            models: {
              prosody: {
                predictions: [
                  {
                    emotions: [
                      { name: 'excitement', score: 0.75 },
                      { name: 'calmness', score: 0.2 }
                    ],
                    metadata: {
                      duration_ms: 2500,
                      speaking_rate: 1.2
                    }
                  }
                ]
              }
            }
          }
        ]
      };
      
      vi.mocked(fetch).mockResolvedValueOnce({
        ok: true,
        json: vi.fn().mockResolvedValue(mockPredictions)
      } as any);
      
      // Create a mock blob
      const audioBlob = new Blob(['mock audio data'], { type: 'audio/wav' });
      
      // Call the function
      const result = await analyzeVoice(audioBlob, 'api-key-123');
      
      // Verify fetch was called correctly
      expect(fetch).toHaveBeenCalledTimes(3);
      expect(fetch).toHaveBeenNthCalledWith(1, 'https://api.hume.ai/v0/batch/jobs', {
        method: 'POST',
        headers: {
          'X-Hume-Api-Key': 'api-key-123'
        },
        body: expect.any(FormData)
      });
      
      // Verify result
      expect(result).toEqual(mockPredictions);
    });
  });
  
  describe('saveEmotionAnalysis', () => {
    it('感情分析結果をデータベースに保存できること', async () => {
      // Mock Supabase client
      const mockSupabaseClient = {
        from: vi.fn().mockReturnThis(),
        insert: vi.fn().mockResolvedValue({ error: null })
      };
      
      vi.mocked(createSupabaseServerClient).mockResolvedValue(mockSupabaseClient as any);
      
      // Test data
      const testData = {
        userId: 'user123',
        assessmentId: '123e4567-e89b-12d3-a456-426614174000',
        faceEmotions: { happiness: 0.85, sadness: 0.1 },
        voiceEmotions: { excitement: 0.75, calmness: 0.2 },
        timestamp: 1617123456789
      };
      
      // Call the function
      const result = await saveEmotionAnalysis(testData);
      
      // Verify Supabase client was called correctly
      expect(createSupabaseServerClient).toHaveBeenCalled();
      expect(mockSupabaseClient.from).toHaveBeenCalledWith('emotion_analysis');
      expect(mockSupabaseClient.insert).toHaveBeenCalledWith({
        user_id: 'user123',
        assessment_id: '123e4567-e89b-12d3-a456-426614174000',
        face_emotions: { happiness: 0.85, sadness: 0.1 },
        voice_emotions: { excitement: 0.75, calmness: 0.2 },
        timestamp: 1617123456789
      });
      
      // Verify result
      expect(result).toEqual({ success: true });
    });
    
    it('データベースエラーが発生した場合エラーを返すこと', async () => {
      // Mock Supabase client with error
      const mockSupabaseClient = {
        from: vi.fn().mockReturnThis(),
        insert: vi.fn().mockResolvedValue({ error: { message: 'Database error' } })
      };
      
      vi.mocked(createSupabaseServerClient).mockResolvedValue(mockSupabaseClient as any);
      
      // Test data
      const testData = {
        userId: 'user123',
        assessmentId: '123e4567-e89b-12d3-a456-426614174000',
        faceEmotions: { happiness: 0.85, sadness: 0.1 },
        timestamp: 1617123456789
      };
      
      // Call the function
      const result = await saveEmotionAnalysis(testData);
      
      // Verify result
      expect(result).toEqual({
        success: false,
        error: 'Database error'
      });
    });
  });
  
  describe('getEmotionAnalysis', () => {
    it('ユーザーIDと評価IDに基づいて感情分析結果を取得できること', async () => {
      // Mock data
      const mockEmotionData = [
        {
          id: 1,
          user_id: 'user123',
          assessment_id: '123e4567-e89b-12d3-a456-426614174000',
          face_emotions: { happiness: 0.85 },
          voice_emotions: { excitement: 0.75 },
          timestamp: '2023-01-01T00:00:00Z'
        }
      ];
      
      // Mock Supabase client
      const mockSupabaseClient = {
        from: vi.fn().mockReturnThis(),
        select: vi.fn().mockReturnThis(),
        eq: vi.fn().mockReturnThis()
      };
      
      // Mock the last eq call to return data
      mockSupabaseClient.eq.mockResolvedValue({ 
        data: mockEmotionData, 
        error: null 
      });
      
      vi.mocked(createSupabaseServerClient).mockResolvedValue(mockSupabaseClient as any);
      
      // Call the function
      const result = await getEmotionAnalysis('user123', '123e4567-e89b-12d3-a456-426614174000');
      
      // Verify Supabase client was called correctly
      expect(createSupabaseServerClient).toHaveBeenCalled();
      expect(mockSupabaseClient.from).toHaveBeenCalledWith('emotion_analysis');
      expect(mockSupabaseClient.select).toHaveBeenCalledWith('*');
      expect(mockSupabaseClient.eq).toHaveBeenCalledWith('user_id', 'user123');
      expect(mockSupabaseClient.eq).toHaveBeenCalledWith('assessment_id', '123e4567-e89b-12d3-a456-426614174000');
      
      // Verify result
      expect(result).toEqual({
        success: true,
        data: mockEmotionData
      });
    });
  });
  
  describe('HumeRealtimeEmotionService', () => {
    // Mock WebSocket
    class MockWebSocket {
      onopen: (() => void) | null = null;
      onmessage: ((event: any) => void) | null = null;
      onerror: ((event: any) => void) | null = null;
      onclose: (() => void) | null = null;
      readyState = 0;
      
      constructor(public url: string, public protocols?: string | string[]) {}
      
      send(data: string | ArrayBuffer | Blob | ArrayBufferView): void {}
      close(): void {}
    }
    
    // Replace global WebSocket with mock
    const originalWebSocket = global.WebSocket;
    beforeEach(() => {
      global.WebSocket = MockWebSocket as any;
    });
    
    // Restore original WebSocket after tests
    afterAll(() => {
      global.WebSocket = originalWebSocket;
    });
    
    it('リアルタイム感情認識サービスが正しく初期化されること', () => {
      const onFaceData = vi.fn();
      const onVoiceData = vi.fn();
      const onError = vi.fn();
      
      const service = new HumeRealtimeEmotionService(
        'api-key-123',
        onFaceData,
        onVoiceData,
        onError
      );
      
      expect(service).toBeDefined();
    });
  });
}); 