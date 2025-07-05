/**
 * @jest-environment jsdom
 */
import { 
  getAudioFromCombinedCache, 
  saveAudioToCombinedCache, 
  clearCombinedCache, 
  getCombinedCacheStats,
  CacheType,
  CacheSettings
} from './combinedAudioCache';

// Mock dependencies
jest.mock('./audioCache', () => ({
  getAudioFromCache: jest.fn(),
  saveAudioToCache: jest.fn(),
  clearAudioCache: jest.fn(),
  getAudioCacheSize: jest.fn()
}));

jest.mock('./serverAudioCache', () => ({
  getAudioFromServerCache: jest.fn(),
  saveAudioToServerCache: jest.fn(),
  clearServerAudioCache: jest.fn(),
  getServerAudioCacheSize: jest.fn()
}));

// Import mocked dependencies
import { 
  getAudioFromCache, 
  saveAudioToCache, 
  clearAudioCache, 
  getAudioCacheSize 
} from './audioCache';

import {
  getAudioFromServerCache,
  saveAudioToServerCache,
  clearServerAudioCache,
  getServerAudioCacheSize
} from './serverAudioCache';

describe('combinedAudioCache.ts', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('getAudioFromCombinedCache', () => {
    test('サーバー優先設定時にサーバーキャッシュから音声を取得する 重要度:5', async () => {
      const mockBlob = new Blob(['test audio data'], { type: 'audio/mp3' });
      const settings: CacheSettings = {
        clientEnabled: true,
        serverEnabled: true,
        preferServer: true
      };
      
      (getAudioFromServerCache as jest.Mock).mockResolvedValue(mockBlob);
      
      const result = await getAudioFromCombinedCache('hello', 'test-voice', settings);
      
      expect(getAudioFromServerCache).toHaveBeenCalledWith('hello', 'test-voice');
      expect(getAudioFromCache).not.toHaveBeenCalled();
      expect(result).toEqual({ blob: mockBlob, source: CacheType.SERVER });
    });
    
    test('サーバー優先だがサーバーキャッシュミス時にクライアントキャッシュから取得する 重要度:4', async () => {
      const mockBlob = new Blob(['test audio data'], { type: 'audio/mp3' });
      const settings: CacheSettings = {
        clientEnabled: true,
        serverEnabled: true,
        preferServer: true
      };
      
      (getAudioFromServerCache as jest.Mock).mockResolvedValue(null);
      (getAudioFromCache as jest.Mock).mockResolvedValue(mockBlob);
      (saveAudioToServerCache as jest.Mock).mockResolvedValue(true);
      
      const result = await getAudioFromCombinedCache('hello', 'test-voice', settings);
      
      expect(getAudioFromServerCache).toHaveBeenCalledWith('hello', 'test-voice');
      expect(getAudioFromCache).toHaveBeenCalledWith('hello', 'test-voice');
      expect(saveAudioToServerCache).toHaveBeenCalledWith('hello', 'test-voice', mockBlob);
      expect(result).toEqual({ blob: mockBlob, source: CacheType.CLIENT });
    });
    
    test('クライアント優先設定時にクライアントキャッシュから音声を取得する 重要度:4', async () => {
      const mockBlob = new Blob(['test audio data'], { type: 'audio/mp3' });
      const settings: CacheSettings = {
        clientEnabled: true,
        serverEnabled: true,
        preferServer: false  // クライアント優先
      };
      
      (getAudioFromCache as jest.Mock).mockResolvedValue(mockBlob);
      
      const result = await getAudioFromCombinedCache('hello', 'test-voice', settings);
      
      expect(getAudioFromCache).toHaveBeenCalledWith('hello', 'test-voice');
      expect(getAudioFromServerCache).not.toHaveBeenCalled();
      expect(result).toEqual({ blob: mockBlob, source: CacheType.CLIENT });
    });
    
    test('クライアント優先だがクライアントキャッシュミス時にサーバーキャッシュから取得する 重要度:4', async () => {
      const mockBlob = new Blob(['test audio data'], { type: 'audio/mp3' });
      const settings: CacheSettings = {
        clientEnabled: true,
        serverEnabled: true,
        preferServer: false  // クライアント優先
      };
      
      (getAudioFromCache as jest.Mock).mockResolvedValue(null);
      (getAudioFromServerCache as jest.Mock).mockResolvedValue(mockBlob);
      (saveAudioToCache as jest.Mock).mockResolvedValue(true);
      
      const result = await getAudioFromCombinedCache('hello', 'test-voice', settings);
      
      expect(getAudioFromCache).toHaveBeenCalledWith('hello', 'test-voice');
      expect(getAudioFromServerCache).toHaveBeenCalledWith('hello', 'test-voice');
      expect(saveAudioToCache).toHaveBeenCalledWith('hello', 'test-voice', mockBlob);
      expect(result).toEqual({ blob: mockBlob, source: CacheType.SERVER });
    });
    
    test('両方のキャッシュが無効の場合nullを返す 重要度:3', async () => {
      const settings: CacheSettings = {
        clientEnabled: false,
        serverEnabled: false,
        preferServer: true
      };
      
      const result = await getAudioFromCombinedCache('hello', 'test-voice', settings);
      
      expect(getAudioFromCache).not.toHaveBeenCalled();
      expect(getAudioFromServerCache).not.toHaveBeenCalled();
      expect(result).toEqual({ blob: null, source: null });
    });
    
    test('どちらのキャッシュにも存在しない場合nullを返す 重要度:3', async () => {
      const settings: CacheSettings = {
        clientEnabled: true,
        serverEnabled: true,
        preferServer: true
      };
      
      (getAudioFromServerCache as jest.Mock).mockResolvedValue(null);
      (getAudioFromCache as jest.Mock).mockResolvedValue(null);
      
      const result = await getAudioFromCombinedCache('hello', 'test-voice', settings);
      
      expect(result).toEqual({ blob: null, source: null });
    });
  });

  describe('saveAudioToCombinedCache', () => {
    test('両方のキャッシュが有効な場合両方に保存する 重要度:5', async () => {
      const mockBlob = new Blob(['test audio data'], { type: 'audio/mp3' });
      const settings: CacheSettings = {
        clientEnabled: true,
        serverEnabled: true,
        preferServer: true
      };
      
      (saveAudioToCache as jest.Mock).mockResolvedValue(true);
      (saveAudioToServerCache as jest.Mock).mockResolvedValue(true);
      
      const result = await saveAudioToCombinedCache('hello', 'test-voice', mockBlob, settings);
      
      expect(saveAudioToCache).toHaveBeenCalledWith('hello', 'test-voice', mockBlob);
      expect(saveAudioToServerCache).toHaveBeenCalledWith('hello', 'test-voice', mockBlob);
      expect(result).toEqual({ success: true, savedTo: CacheType.BOTH });
    });
    
    test('クライアントキャッシュのみが有効な場合クライアントのみに保存する 重要度:4', async () => {
      const mockBlob = new Blob(['test audio data'], { type: 'audio/mp3' });
      const settings: CacheSettings = {
        clientEnabled: true,
        serverEnabled: false,
        preferServer: true
      };
      
      (saveAudioToCache as jest.Mock).mockResolvedValue(true);
      
      const result = await saveAudioToCombinedCache('hello', 'test-voice', mockBlob, settings);
      
      expect(saveAudioToCache).toHaveBeenCalledWith('hello', 'test-voice', mockBlob);
      expect(saveAudioToServerCache).not.toHaveBeenCalled();
      expect(result).toEqual({ success: true, savedTo: CacheType.CLIENT });
    });
    
    test('サーバーキャッシュのみが有効な場合サーバーのみに保存する 重要度:4', async () => {
      const mockBlob = new Blob(['test audio data'], { type: 'audio/mp3' });
      const settings: CacheSettings = {
        clientEnabled: false,
        serverEnabled: true,
        preferServer: true
      };
      
      (saveAudioToServerCache as jest.Mock).mockResolvedValue(true);
      
      const result = await saveAudioToCombinedCache('hello', 'test-voice', mockBlob, settings);
      
      expect(saveAudioToCache).not.toHaveBeenCalled();
      expect(saveAudioToServerCache).toHaveBeenCalledWith('hello', 'test-voice', mockBlob);
      expect(result).toEqual({ success: true, savedTo: CacheType.SERVER });
    });
    
    test('クライアント保存失敗、サーバー保存成功の場合サーバーのみに保存を報告する 重要度:3', async () => {
      const mockBlob = new Blob(['test audio data'], { type: 'audio/mp3' });
      const settings: CacheSettings = {
        clientEnabled: true,
        serverEnabled: true,
        preferServer: true
      };
      
      (saveAudioToCache as jest.Mock).mockResolvedValue(false);
      (saveAudioToServerCache as jest.Mock).mockResolvedValue(true);
      
      const result = await saveAudioToCombinedCache('hello', 'test-voice', mockBlob, settings);
      
      expect(result).toEqual({ success: true, savedTo: CacheType.SERVER });
    });
    
    test('両方のキャッシュ保存に失敗した場合失敗を報告する 重要度:3', async () => {
      const mockBlob = new Blob(['test audio data'], { type: 'audio/mp3' });
      const settings: CacheSettings = {
        clientEnabled: true,
        serverEnabled: true,
        preferServer: true
      };
      
      (saveAudioToCache as jest.Mock).mockResolvedValue(false);
      (saveAudioToServerCache as jest.Mock).mockResolvedValue(false);
      
      const result = await saveAudioToCombinedCache('hello', 'test-voice', mockBlob, settings);
      
      expect(result).toEqual({ success: false, savedTo: CacheType.BOTH });
    });
  });

  describe('clearCombinedCache', () => {
    test('BOTH指定時に両方のキャッシュをクリアする 重要度:4', async () => {
      (clearAudioCache as jest.Mock).mockResolvedValue(true);
      (clearServerAudioCache as jest.Mock).mockResolvedValue(true);
      
      const result = await clearCombinedCache(CacheType.BOTH);
      
      expect(clearAudioCache).toHaveBeenCalled();
      expect(clearServerAudioCache).toHaveBeenCalled();
      expect(result).toBe(true);
    });
    
    test('CLIENT指定時にクライアントキャッシュのみクリアする 重要度:3', async () => {
      (clearAudioCache as jest.Mock).mockResolvedValue(true);
      
      const result = await clearCombinedCache(CacheType.CLIENT);
      
      expect(clearAudioCache).toHaveBeenCalled();
      expect(clearServerAudioCache).not.toHaveBeenCalled();
      expect(result).toBe(true);
    });
    
    test('SERVER指定時にサーバーキャッシュのみクリアする 重要度:3', async () => {
      (clearServerAudioCache as jest.Mock).mockResolvedValue(true);
      
      const result = await clearCombinedCache(CacheType.SERVER);
      
      expect(clearAudioCache).not.toHaveBeenCalled();
      expect(clearServerAudioCache).toHaveBeenCalled();
      expect(result).toBe(true);
    });
    
    test('古いエントリのみクリアする指定を両方のキャッシュに適用する 重要度:3', async () => {
      const olderThanDays = 7;
      
      (clearAudioCache as jest.Mock).mockResolvedValue(true);
      (clearServerAudioCache as jest.Mock).mockResolvedValue(true);
      
      const result = await clearCombinedCache(CacheType.BOTH, olderThanDays);
      
      expect(clearAudioCache).toHaveBeenCalledWith(olderThanDays);
      expect(clearServerAudioCache).toHaveBeenCalledWith(olderThanDays);
      expect(result).toBe(true);
    });
    
    test('一方のキャッシュクリアが失敗した場合全体として失敗を報告する 重要度:3', async () => {
      (clearAudioCache as jest.Mock).mockResolvedValue(true);
      (clearServerAudioCache as jest.Mock).mockResolvedValue(false);
      
      const result = await clearCombinedCache(CacheType.BOTH);
      
      expect(result).toBe(false);
    });
  });

  describe('getCombinedCacheStats', () => {
    test('両方のキャッシュの統計を正しく集計する 重要度:3', async () => {
      (getAudioCacheSize as jest.Mock).mockResolvedValue({ count: 5, sizeBytes: 10240 });
      (getServerAudioCacheSize as jest.Mock).mockResolvedValue({ count: 10, sizeBytes: 20480 });
      
      const result = await getCombinedCacheStats();
      
      expect(getAudioCacheSize).toHaveBeenCalled();
      expect(getServerAudioCacheSize).toHaveBeenCalled();
      expect(result).toEqual({
        client: { count: 5, sizeBytes: 10240 },
        server: { count: 10, sizeBytes: 20480 },
        total: { count: 15, sizeBytes: 30720 }
      });
    });
    
    test('統計取得中にエラーが発生した場合ゼロ値を返す 重要度:2', async () => {
      (getAudioCacheSize as jest.Mock).mockRejectedValue(new Error('Get stats error'));
      (getServerAudioCacheSize as jest.Mock).mockResolvedValue({ count: 10, sizeBytes: 20480 });
      
      const result = await getCombinedCacheStats();
      
      expect(result).toEqual({
        client: { count: 0, sizeBytes: 0 },
        server: { count: 0, sizeBytes: 0 },
        total: { count: 0, sizeBytes: 0 }
      });
    });
  });
}); 