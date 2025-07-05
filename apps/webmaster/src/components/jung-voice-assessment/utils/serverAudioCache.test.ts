/**
 * Tests for serverAudioCache.ts
 */
import { vi, describe, test, expect, beforeEach } from 'vitest';
import { 
  getAudioFromServerCache, 
  saveAudioToServerCache, 
  clearServerAudioCache, 
  getServerAudioCacheSize 
} from './serverAudioCache';

// Setup mock for global fetch
const mockFetch = vi.fn();
vi.stubGlobal('fetch', mockFetch);

describe('serverAudioCache.ts', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  describe('getAudioFromServerCache', () => {
    test('正常にサーバーキャッシュからオーディオデータを取得する 重要度:5', async () => {
      const mockBlob = new Blob(['test audio data'], { type: 'audio/mp3' });
      const mockResponse = {
        ok: true,
        status: 200,
        blob: vi.fn().mockResolvedValue(mockBlob)
      };
      
      mockFetch.mockResolvedValue(mockResponse);
      
      const result = await getAudioFromServerCache('hello', 'test-voice');
      
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/audio-cache?text=hello&voice=test-voice',
        expect.objectContaining({
          method: 'GET',
          headers: expect.objectContaining({
            'Accept': 'audio/mp3'
          })
        })
      );
      
      expect(result).toEqual(mockBlob);
    });
    
    test('キャッシュアイテムが存在しない場合(404)nullを返す 重要度:4', async () => {
      const mockResponse = {
        ok: false,
        status: 404
      };
      
      mockFetch.mockResolvedValue(mockResponse);
      
      const result = await getAudioFromServerCache('nonexistent', 'test-voice');
      
      expect(result).toBeNull();
    });
    
    test('サーバーエラー発生時にnullを返す 重要度:3', async () => {
      const mockResponse = {
        ok: false,
        status: 500
      };
      
      mockFetch.mockResolvedValue(mockResponse);
      
      const result = await getAudioFromServerCache('hello', 'test-voice');
      
      expect(result).toBeNull();
    });
    
    test('ネットワークエラー発生時にnullを返す 重要度:3', async () => {
      mockFetch.mockRejectedValue(new Error('Network error'));
      
      const result = await getAudioFromServerCache('hello', 'test-voice');
      
      expect(result).toBeNull();
    });
  });

  describe('saveAudioToServerCache', () => {
    test('正常にオーディオデータをサーバーキャッシュに保存する 重要度:5', async () => {
      const mockBlob = new Blob(['test audio data'], { type: 'audio/mp3' });
      const mockResponse = {
        ok: true,
        status: 200,
        json: vi.fn().mockResolvedValue({ success: true })
      };
      
      mockFetch.mockResolvedValue(mockResponse);
      
      const result = await saveAudioToServerCache('hello', 'test-voice', mockBlob);
      
      // Check that fetch was called with FormData
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/audio-cache',
        expect.objectContaining({
          method: 'POST',
          body: expect.any(FormData)
        })
      );
      
      // Get the FormData that was passed
      const fetchCallArg = mockFetch.mock.calls[0][1];
      const formData = fetchCallArg.body;
      
      // Check formData contains the expected values
      expect(formData.get('text')).toBe('hello');
      expect(formData.get('voice')).toBe('test-voice');
      expect(formData.get('audio')).toEqual(mockBlob);
      
      expect(result).toBe(true);
    });
    
    test('サーバーからエラーレスポンスを受け取った場合falseを返す 重要度:3', async () => {
      const mockBlob = new Blob(['test audio data'], { type: 'audio/mp3' });
      const mockResponse = {
        ok: false,
        status: 500
      };
      
      mockFetch.mockResolvedValue(mockResponse);
      
      const result = await saveAudioToServerCache('hello', 'test-voice', mockBlob);
      
      expect(result).toBe(false);
    });
    
    test('サーバーから成功レスポンスだがsuccessがfalseの場合falseを返す 重要度:3', async () => {
      const mockBlob = new Blob(['test audio data'], { type: 'audio/mp3' });
      const mockResponse = {
        ok: true,
        status: 200,
        json: vi.fn().mockResolvedValue({ success: false })
      };
      
      mockFetch.mockResolvedValue(mockResponse);
      
      const result = await saveAudioToServerCache('hello', 'test-voice', mockBlob);
      
      expect(result).toBe(false);
    });
    
    test('ネットワークエラー発生時にfalseを返す 重要度:3', async () => {
      const mockBlob = new Blob(['test audio data'], { type: 'audio/mp3' });
      
      mockFetch.mockRejectedValue(new Error('Network error'));
      
      const result = await saveAudioToServerCache('hello', 'test-voice', mockBlob);
      
      expect(result).toBe(false);
    });
  });

  describe('clearServerAudioCache', () => {
    test('全てのサーバーキャッシュを正常にクリアする 重要度:4', async () => {
      const mockResponse = {
        ok: true,
        status: 200,
        json: vi.fn().mockResolvedValue({ success: true, deletedCount: 10 })
      };
      
      mockFetch.mockResolvedValue(mockResponse);
      
      const result = await clearServerAudioCache();
      
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/audio-cache',
        expect.objectContaining({
          method: 'DELETE'
        })
      );
      
      expect(result).toBe(true);
    });
    
    test('指定された日数より古いサーバーキャッシュのみクリアする 重要度:3', async () => {
      const olderThanDays = 7;
      const mockResponse = {
        ok: true,
        status: 200,
        json: vi.fn().mockResolvedValue({ success: true, deletedCount: 5 })
      };
      
      mockFetch.mockResolvedValue(mockResponse);
      
      const result = await clearServerAudioCache(olderThanDays);
      
      expect(mockFetch).toHaveBeenCalledWith(
        `/api/audio-cache?olderThanDays=${olderThanDays}`,
        expect.objectContaining({
          method: 'DELETE'
        })
      );
      
      expect(result).toBe(true);
    });
    
    test('サーバーからエラーレスポンスを受け取った場合falseを返す 重要度:3', async () => {
      const mockResponse = {
        ok: false,
        status: 500
      };
      
      mockFetch.mockResolvedValue(mockResponse);
      
      const result = await clearServerAudioCache();
      
      expect(result).toBe(false);
    });
    
    test('ネットワークエラー発生時にfalseを返す 重要度:3', async () => {
      mockFetch.mockRejectedValue(new Error('Network error'));
      
      const result = await clearServerAudioCache();
      
      expect(result).toBe(false);
    });
  });

  describe('getServerAudioCacheSize', () => {
    test('サーバーキャッシュのサイズを正確に取得する 重要度:3', async () => {
      const mockResponse = {
        ok: true,
        status: 200,
        json: vi.fn().mockResolvedValue({ count: 10, sizeBytes: 1024000 })
      };
      
      mockFetch.mockResolvedValue(mockResponse);
      
      const result = await getServerAudioCacheSize();
      
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/audio-cache',
        expect.objectContaining({
          method: 'PATCH'
        })
      );
      
      expect(result).toEqual({ count: 10, sizeBytes: 1024000 });
    });
    
    test('サーバーからエラーレスポンスを受け取った場合ゼロ値を返す 重要度:2', async () => {
      const mockResponse = {
        ok: false,
        status: 500
      };
      
      mockFetch.mockResolvedValue(mockResponse);
      
      const result = await getServerAudioCacheSize();
      
      expect(result).toEqual({ count: 0, sizeBytes: 0 });
    });
    
    test('ネットワークエラー発生時にゼロ値を返す 重要度:2', async () => {
      mockFetch.mockRejectedValue(new Error('Network error'));
      
      const result = await getServerAudioCacheSize();
      
      expect(result).toEqual({ count: 0, sizeBytes: 0 });
    });
  });
}); 