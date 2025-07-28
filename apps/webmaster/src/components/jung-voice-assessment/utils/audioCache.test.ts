/**
 * @jest-environment jsdom
 */
import {
  clearAudioCache,
  getAudioCacheSize,
  getAudioFromCache,
  saveAudioToCache,
} from "./audioCache";

// Mock IndexedDB
let mockGetRequest: any;
let mockPutRequest: any;
let mockCountRequest: any;
let mockCursorRequest: any;
let mockTransaction: any;
let mockObjectStore: any;
let mockDb: any;
let mockOpenRequest: any;

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

describe("audioCache.ts", () => {
  beforeEach(() => {
    // Setup test mock
    mockGetRequest = {
      onsuccess: null,
      onerror: null,
      result: null,
    };

    mockPutRequest = {
      onsuccess: null,
      onerror: null,
    };

    mockCountRequest = {
      onsuccess: null,
      result: 0,
    };

    mockCursorRequest = {
      onsuccess: null,
      result: {
        value: null,
        continue: vi.fn(),
      },
    };

    mockObjectStore = {
      get: vi.fn().mockReturnValue(mockGetRequest),
      put: vi.fn().mockReturnValue(mockPutRequest),
      clear: vi.fn(),
      count: vi.fn().mockReturnValue(mockCountRequest),
      openCursor: vi.fn().mockReturnValue(mockCursorRequest),
      index: vi.fn().mockReturnValue({
        openCursor: vi.fn().mockReturnValue(mockCursorRequest),
      }),
    };

    mockTransaction = {
      objectStore: vi.fn().mockReturnValue(mockObjectStore),
      oncomplete: null,
      onerror: null,
    };

    mockDb = {
      transaction: vi.fn().mockReturnValue(mockTransaction),
      close: vi.fn(),
    };

    mockOpenRequest = {
      onsuccess: null,
      onerror: null,
      onupgradeneeded: null,
      result: mockDb,
    };

    // Mock global.indexedDB.open
    global.indexedDB.open = vi.fn().mockReturnValue(mockOpenRequest);

    // Mock IDBKeyRange
    global.IDBKeyRange = {
      upperBound: vi.fn().mockImplementation((value) => ({
        upper: value,
        lowerOpen: false,
        upperOpen: false,
      })),
    } as any;
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe("getAudioFromCache", () => {
    test("キャッシュに存在する音声が正しく取得される 重要度:5", async () => {
      // Setup mock data
      const mockBlob = new Blob(["test audio data"], { type: "audio/mp3" });

      // Trigger onsuccess handler for DB open
      global.indexedDB.open = vi.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onsuccess &&
            mockOpenRequest.onsuccess({ target: mockOpenRequest });
        }, 0);
        return mockOpenRequest;
      });

      // Mock get request success
      mockGetRequest.onsuccess = null; // Will be set by the function
      mockGetRequest.result = {
        text: "hello",
        voice: "test-voice",
        audioData: mockBlob,
        timestamp: Date.now(),
      };

      const result = await getAudioFromCache("hello", "test-voice");

      // Manually trigger the success handler
      mockGetRequest.onsuccess && mockGetRequest.onsuccess();

      // Manually trigger transaction complete
      mockTransaction.oncomplete && mockTransaction.oncomplete();

      expect(mockObjectStore.get).toHaveBeenCalledWith(["hello", "test-voice"]);
      expect(result).toEqual(mockBlob);
    }, 30000); // Increase timeout to 30 seconds

    test("キャッシュに存在しない音声はnullを返す 重要度:4", async () => {
      // Trigger onsuccess handler for DB open
      global.indexedDB.open = vi.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onsuccess &&
            mockOpenRequest.onsuccess({ target: mockOpenRequest });
        }, 0);
        return mockOpenRequest;
      });

      // Mock get request success with no result
      mockGetRequest.onsuccess = null; // Will be set by the function
      mockGetRequest.result = undefined;

      const result = await getAudioFromCache("unknown", "test-voice");

      // Manually trigger the success handler
      mockGetRequest.onsuccess && mockGetRequest.onsuccess();

      // Manually trigger transaction complete
      mockTransaction.oncomplete && mockTransaction.oncomplete();

      expect(mockObjectStore.get).toHaveBeenCalledWith([
        "unknown",
        "test-voice",
      ]);
      expect(result).toBeNull();
    }, 30000); // Increase timeout to 30 seconds

    test("エラーが発生した場合はnullを返す 重要度:3", async () => {
      // Trigger onerror handler for DB open
      global.indexedDB.open = vi.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onerror &&
            mockOpenRequest.onerror(new Error("DB error"));
        }, 0);
        return mockOpenRequest;
      });

      const result = await getAudioFromCache("hello", "test-voice");

      expect(result).toBeNull();
    }, 30000); // Increase timeout to 30 seconds
  });

  describe("saveAudioToCache", () => {
    test("音声が正常にキャッシュに保存される 重要度:5", async () => {
      const mockBlob = new Blob(["test audio data"], { type: "audio/mp3" });

      // Trigger onsuccess handler for DB open
      global.indexedDB.open = vi.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onsuccess &&
            mockOpenRequest.onsuccess({ target: mockOpenRequest });
        }, 0);
        return mockOpenRequest;
      });

      // Mock put request success
      mockPutRequest.onsuccess = null; // Will be set by the function

      const result = await saveAudioToCache("hello", "test-voice", mockBlob);

      // Manually trigger success for put operation
      mockPutRequest.onsuccess && mockPutRequest.onsuccess();

      // Manually trigger transaction complete
      mockTransaction.oncomplete && mockTransaction.oncomplete();

      expect(mockObjectStore.put).toHaveBeenCalledWith(
        expect.objectContaining({
          text: "hello",
          voice: "test-voice",
          audioData: mockBlob,
          timestamp: expect.any(Number),
        }),
      );
      expect(result).toBe(true);
    }, 30000); // Increase timeout to 30 seconds

    test("保存時にエラーが発生した場合はfalseを返す 重要度:3", async () => {
      const mockBlob = new Blob(["test audio data"], { type: "audio/mp3" });

      // Trigger onsuccess handler for DB open
      global.indexedDB.open = vi.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onsuccess &&
            mockOpenRequest.onsuccess({ target: mockOpenRequest });
        }, 0);
        return mockOpenRequest;
      });

      // Mock put request error
      mockPutRequest.onerror = null; // Will be set by the function

      const result = await saveAudioToCache("hello", "test-voice", mockBlob);

      // Manually trigger error for put operation
      const errorEvent = new Error("Put error");
      mockPutRequest.onerror && mockPutRequest.onerror(errorEvent);

      expect(result).toBe(false);
    }, 30000); // Increase timeout to 30 seconds
  });

  describe("clearAudioCache", () => {
    test("すべてのキャッシュを正常にクリアする 重要度:4", async () => {
      // Trigger onsuccess handler for DB open
      global.indexedDB.open = vi.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onsuccess &&
            mockOpenRequest.onsuccess({ target: mockOpenRequest });
        }, 0);
        return mockOpenRequest;
      });

      const result = await clearAudioCache();

      // Manually trigger transaction complete
      mockTransaction.oncomplete && mockTransaction.oncomplete();

      expect(mockObjectStore.clear).toHaveBeenCalled();
      expect(result).toBe(true);
    }, 30000); // Increase timeout to 30 seconds

    test("古いキャッシュエントリのみをクリアする 重要度:3", async () => {
      const olderThanDays = 7;

      // Trigger onsuccess handler for DB open
      global.indexedDB.open = vi.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onsuccess &&
            mockOpenRequest.onsuccess({ target: mockOpenRequest });
        }, 0);
        return mockOpenRequest;
      });

      // Setup cursor behavior
      mockCursorRequest.onsuccess = null;
      const mockCursor = {
        delete: vi.fn(),
        continue: vi.fn(),
        value: {
          timestamp: Date.now() - ((olderThanDays + 1) * 24 * 60 * 60 * 1000),
        },
      };

      // Mock successful cursor retrieval then no more entries
      mockObjectStore.index = vi.fn().mockReturnValue({
        openCursor: vi.fn().mockImplementation(() => {
          setTimeout(() => {
            // First call returns a cursor
            mockCursorRequest.result = mockCursor;
            mockCursorRequest.onsuccess &&
              mockCursorRequest.onsuccess({ target: mockCursorRequest });

            // Setup for next call to return no more entries
            mockCursorRequest.result = null;
          }, 0);
          return mockCursorRequest;
        }),
      });

      // Mock successful transaction completion
      const completeFn = vi.fn();
      mockTransaction.oncomplete = completeFn;

      const result = await clearAudioCache(olderThanDays);

      // Simulate cursor movement
      mockCursor.continue();

      // Mock transaction complete
      completeFn();

      expect(mockObjectStore.index).toHaveBeenCalledWith("timestamp");
      expect(global.IDBKeyRange.upperBound).toHaveBeenCalled();
      expect(mockCursor.delete).toHaveBeenCalled();
      expect(result).toBe(true);
    }, 30000); // Increase timeout to 30 seconds
  });

  describe("getAudioCacheSize", () => {
    test("キャッシュサイズを正確に取得する 重要度:3", async () => {
      // Trigger onsuccess handler for DB open
      global.indexedDB.open = vi.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onsuccess &&
            mockOpenRequest.onsuccess({ target: mockOpenRequest });
        }, 0);
        return mockOpenRequest;
      });

      // Setup count request
      mockCountRequest.result = 2;

      // Setup cursor behavior
      mockCursorRequest.onsuccess = null;
      const mockItems = [
        {
          text: "hello",
          voice: "voice1",
          audioData: new Blob(["audio1"], { type: "audio/mp3" }),
          timestamp: Date.now(),
        },
        {
          text: "world",
          voice: "voice2",
          audioData: new Blob(["audio2"], { type: "audio/mp3" }),
          timestamp: Date.now(),
        },
      ];

      let itemIndex = 0;

      // Mock cursor for iterating through items
      mockObjectStore.openCursor = vi.fn().mockImplementation(() => {
        setTimeout(() => {
          if (itemIndex < mockItems.length) {
            mockCursorRequest.result = {
              value: mockItems[itemIndex],
              continue: () => {
                itemIndex++;
                if (itemIndex < mockItems.length) {
                  setTimeout(() => {
                    mockCursorRequest.onsuccess &&
                      mockCursorRequest.onsuccess({
                        target: mockCursorRequest,
                      });
                  }, 0);
                } else {
                  setTimeout(() => {
                    mockCursorRequest.result = null;
                    mockCursorRequest.onsuccess &&
                      mockCursorRequest.onsuccess({
                        target: mockCursorRequest,
                      });
                  }, 0);
                }
              },
            };
          } else {
            mockCursorRequest.result = null;
          }

          mockCursorRequest.onsuccess &&
            mockCursorRequest.onsuccess({ target: mockCursorRequest });
        }, 0);
        return mockCursorRequest;
      });

      const result = await getAudioCacheSize();

      // Manually trigger count success
      mockCountRequest.onsuccess && mockCountRequest.onsuccess();

      // Manually trigger transaction complete
      mockTransaction.oncomplete && mockTransaction.oncomplete();

      // Expected size: 'audio1'.length + 'audio2'.length = 6 + 6 = 12
      expect(result).toEqual({ count: 2, sizeBytes: 12 });
    }, 30000); // Increase timeout to 30 seconds

    test("キャッシュが空の場合ゼロを返す 重要度:2", async () => {
      // Trigger onsuccess handler for DB open
      global.indexedDB.open = vi.fn().mockImplementation(() => {
        setTimeout(() => {
          mockOpenRequest.onsuccess &&
            mockOpenRequest.onsuccess({ target: mockOpenRequest });
        }, 0);
        return mockOpenRequest;
      });

      // Setup count request with zero result
      mockCountRequest.result = 0;

      const result = await getAudioCacheSize();

      // Manually trigger count success
      mockCountRequest.onsuccess && mockCountRequest.onsuccess();

      // Manually trigger transaction complete
      mockTransaction.oncomplete && mockTransaction.oncomplete();

      expect(result).toEqual({ count: 0, sizeBytes: 0 });
    }, 30000); // Increase timeout to 30 seconds
  });
});
