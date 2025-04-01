/**
 * @jest-environment jsdom
 */
import { 
  getAudioFromCache, 
  saveAudioToCache, 
  clearAudioCache, 
  getAudioCacheSize 
} from './audioCache';

// Mock IndexedDB
let mockGetRequest: any;
let mockPutRequest: any;
let mockCountRequest: any;
let mockCursorRequest: any;
let mockTransaction: any;
let mockObjectStore: any;
let mockDb: any;
let mockOpenRequest: any;

describe('audioCache.ts', () => {
  beforeEach(() => {
    // Setup test mock
    mockGetRequest = {
      onsuccess: null,
      onerror: null,
      result: null
    };
    
    mockPutRequest = {
      onsuccess: null,
      onerror: null
    };
    
    mockCountRequest = {
      onsuccess: null,
      result: 0
    };
    
    mockCursorRequest = {
      onsuccess: null,
      result: {
        value: null,
        continue: jest.fn()
      }
    };
    
    mockObjectStore = {
      get: jest.fn().mockReturnValue(mockGetRequest),
      put: jest.fn().mockReturnValue(mockPutRequest),
      clear: jest.fn(),
      count: jest.fn().mockReturnValue(mockCountRequest),
      openCursor: jest.fn().mockReturnValue(mockCursorRequest),
      index: jest.fn().mockReturnValue({
        openCursor: jest.fn().mockReturnValue(mockCursorRequest)
      })
    };
    
    mockTransaction = {
      objectStore: jest.fn().mockReturnValue(mockObjectStore),
      oncomplete: null,
      onerror: null
    };
    
    mockDb = {
      transaction: jest.fn().mockReturnValue(mockTransaction),
      close: jest.fn()
    };
    
    mockOpenRequest = {
      onsuccess: null,
      onerror: null,
      onupgradeneeded: null,
      result: mockDb
    };
    
    // Mock global.indexedDB.open
    global.indexedDB.open = jest.fn().mockReturnValue(mockOpenRequest);
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  describe('getAudioFromCache', () => {
    test('正常にキャッシュからオーディオデータを取得する 重要度:5', async () => {
      const mockBlob = new Blob(['test audio data'], { type: 'audio/mp3' });
      
      // Trigger onsuccess handler with mock data
      global.indexedDB.open = jest.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onsuccess && mockOpenRequest.onsuccess({ target: mockOpenRequest });
        }, 0);
        return mockOpenRequest;
      });
      
      // Mock successful retrieval
      mockObjectStore.get = jest.fn().mockImplementation(() => {
        setTimeout(() => {
          mockGetRequest.result = {
            text: 'hello',
            voice: 'test-voice',
            audioData: mockBlob,
            timestamp: Date.now()
          };
          mockGetRequest.onsuccess && mockGetRequest.onsuccess();
        }, 0);
        return mockGetRequest;
      });
      
      const result = await getAudioFromCache('hello', 'test-voice');
      
      expect(mockDb.transaction).toHaveBeenCalledWith('audio-files', 'readonly');
      expect(mockObjectStore.get).toHaveBeenCalledWith(['hello', 'test-voice']);
      expect(result).toEqual(mockBlob);
    });
    
    test('キャッシュアイテムが存在しない場合nullを返す 重要度:4', async () => {
      // Trigger onsuccess handler
      global.indexedDB.open = jest.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onsuccess && mockOpenRequest.onsuccess({ target: mockOpenRequest });
        }, 0);
        return mockOpenRequest;
      });
      
      // Mock item not found
      mockObjectStore.get = jest.fn().mockImplementation(() => {
        setTimeout(() => {
          mockGetRequest.result = undefined;
          mockGetRequest.onsuccess && mockGetRequest.onsuccess();
        }, 0);
        return mockGetRequest;
      });
      
      const result = await getAudioFromCache('nonexistent', 'test-voice');
      
      expect(result).toBeNull();
    });
    
    test('IndexedDBエラー発生時にnullを返す 重要度:3', async () => {
      // Trigger onerror handler
      global.indexedDB.open = jest.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onerror && mockOpenRequest.onerror(new Error('DB error'));
        }, 0);
        return mockOpenRequest;
      });
      
      const result = await getAudioFromCache('hello', 'test-voice');
      
      expect(result).toBeNull();
    });
  });

  describe('saveAudioToCache', () => {
    test('正常にオーディオデータをキャッシュに保存する 重要度:5', async () => {
      const mockBlob = new Blob(['test audio data'], { type: 'audio/mp3' });
      
      // Trigger onsuccess handler for DB open
      global.indexedDB.open = jest.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onsuccess && mockOpenRequest.onsuccess({ target: mockOpenRequest });
        }, 0);
        return mockOpenRequest;
      });
      
      // Mock successful put operation
      mockObjectStore.put = jest.fn().mockImplementation(() => {
        setTimeout(() => {
          mockPutRequest.onsuccess && mockPutRequest.onsuccess();
        }, 0);
        return mockPutRequest;
      });
      
      const result = await saveAudioToCache('hello', 'test-voice', mockBlob);
      
      expect(mockDb.transaction).toHaveBeenCalledWith('audio-files', 'readwrite');
      expect(mockObjectStore.put).toHaveBeenCalled();
      expect(result).toBe(true);
    });
    
    test('保存エラー発生時にfalseを返す 重要度:3', async () => {
      const mockBlob = new Blob(['test audio data'], { type: 'audio/mp3' });
      
      // Trigger onsuccess handler for DB open
      global.indexedDB.open = jest.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onsuccess && mockOpenRequest.onsuccess({ target: mockOpenRequest });
        }, 0);
        return mockOpenRequest;
      });
      
      // Mock failed put operation
      mockObjectStore.put = jest.fn().mockImplementation(() => {
        setTimeout(() => {
          mockPutRequest.onerror && mockPutRequest.onerror(new Error('Put error'));
        }, 0);
        return mockPutRequest;
      });
      
      const result = await saveAudioToCache('hello', 'test-voice', mockBlob);
      
      expect(result).toBe(false);
    });
  });

  describe('clearAudioCache', () => {
    test('すべてのキャッシュを正常にクリアする 重要度:4', async () => {
      // Trigger onsuccess handler for DB open
      global.indexedDB.open = jest.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onsuccess && mockOpenRequest.onsuccess({ target: mockOpenRequest });
        }, 0);
        return mockOpenRequest;
      });
      
      // Mock successful transaction completion
      const completeFn = jest.fn();
      mockTransaction.oncomplete = completeFn;
      
      const result = await clearAudioCache();
      
      // Mock transaction complete
      completeFn();
      
      expect(mockObjectStore.clear).toHaveBeenCalled();
      expect(result).toBe(true);
    });
    
    test('古いキャッシュエントリのみをクリアする 重要度:3', async () => {
      const olderThanDays = 7;
      
      // Trigger onsuccess handler for DB open
      global.indexedDB.open = jest.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onsuccess && mockOpenRequest.onsuccess({ target: mockOpenRequest });
        }, 0);
        return mockOpenRequest;
      });
      
      // Setup cursor behavior
      mockCursorRequest.onsuccess = null;
      const mockCursor = {
        delete: jest.fn(),
        continue: jest.fn(),
        value: {
          timestamp: Date.now() - ((olderThanDays + 1) * 24 * 60 * 60 * 1000)
        }
      };
      
      // Mock successful cursor retrieval then no more entries
      mockObjectStore.index = jest.fn().mockReturnValue({
        openCursor: jest.fn().mockImplementation(() => {
          setTimeout(() => {
            // First call returns a cursor
            mockCursorRequest.result = mockCursor;
            mockCursorRequest.onsuccess && mockCursorRequest.onsuccess({ target: mockCursorRequest });
            
            // Setup for next call to return no more entries
            mockCursorRequest.result = null;
          }, 0);
          return mockCursorRequest;
        })
      });
      
      // Mock successful transaction completion
      const completeFn = jest.fn();
      mockTransaction.oncomplete = completeFn;
      
      const result = await clearAudioCache(olderThanDays);
      
      // Simulate cursor movement
      mockCursor.continue();
      
      // Mock transaction complete
      completeFn();
      
      expect(mockObjectStore.index).toHaveBeenCalledWith('timestamp');
      expect(mockCursor.delete).toHaveBeenCalled();
      expect(result).toBe(true);
    });
  });

  describe('getAudioCacheSize', () => {
    test('キャッシュサイズを正確に取得する 重要度:3', async () => {
      // Trigger onsuccess handler for DB open
      global.indexedDB.open = jest.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onsuccess && mockOpenRequest.onsuccess({ target: mockOpenRequest });
        }, 0);
        return mockOpenRequest;
      });
      
      // Mock count result
      mockCountRequest.result = 2;
      mockCountRequest.onsuccess = jest.fn().mockImplementation(function(this: { result: number }) {
        this.result = 2;
      });
      
      // Setup cursor behavior for size calculation
      const mockItems = [
        { 
          value: { 
            text: 'hello1', 
            voice: 'voice1', 
            audioData: new Blob(['data1'], { type: 'audio/mp3' }), 
            timestamp: Date.now() 
          },
          continue: jest.fn()
        },
        { 
          value: { 
            text: 'hello2', 
            voice: 'voice2', 
            audioData: new Blob(['data2'], { type: 'audio/mp3' }), 
            timestamp: Date.now() 
          },
          continue: jest.fn()
        }
      ];
      
      let cursorIndex = 0;
      
      // Mock cursor behavior
      mockObjectStore.openCursor = jest.fn().mockImplementation(() => {
        setTimeout(() => {
          if (cursorIndex < mockItems.length) {
            mockCursorRequest.result = mockItems[cursorIndex];
            cursorIndex++;
          } else {
            mockCursorRequest.result = null;
          }
          mockCursorRequest.onsuccess && mockCursorRequest.onsuccess({ target: mockCursorRequest });
        }, 0);
        return mockCursorRequest;
      });
      
      // Trigger count success
      setTimeout(() => {
        mockCountRequest.onsuccess();
      }, 0);
      
      const result = await getAudioCacheSize();
      
      expect(mockObjectStore.count).toHaveBeenCalled();
      expect(result.count).toBe(2);
      // Testing that sizeBytes is something reasonable, actual size depends on Blob implementation
      expect(result.sizeBytes).toBeGreaterThan(0);
    });
    
    test('キャッシュが空の場合ゼロを返す 重要度:2', async () => {
      // Trigger onsuccess handler for DB open
      global.indexedDB.open = jest.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onsuccess && mockOpenRequest.onsuccess({ target: mockOpenRequest });
        }, 0);
        return mockOpenRequest;
      });
      
      // Mock empty count result
      mockCountRequest.result = 0;
      mockCountRequest.onsuccess = jest.fn().mockImplementation(function(this: { result: number }) {
        this.result = 0;
      });
      
      // Trigger count success
      setTimeout(() => {
        mockCountRequest.onsuccess();
      }, 0);
      
      const result = await getAudioCacheSize();
      
      expect(result.count).toBe(0);
      expect(result.sizeBytes).toBe(0);
    });
  });
}); 