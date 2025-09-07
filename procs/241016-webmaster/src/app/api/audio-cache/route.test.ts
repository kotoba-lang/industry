import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { GET, POST, DELETE, PATCH } from './route';
import { NextRequest, NextResponse } from 'next/server';
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';

// Mock the fs module
vi.mock('fs', () => ({
  existsSync: vi.fn(),
  mkdirSync: vi.fn(),
  readFileSync: vi.fn(),
  writeFileSync: vi.fn(),
  unlinkSync: vi.fn()
}));

// Mock path
vi.mock('path', () => ({
  join: vi.fn().mockImplementation((...args) => args.join('/'))
}));

// Mock crypto
vi.mock('crypto', () => ({
  createHash: vi.fn(() => ({
    update: vi.fn().mockReturnThis(),
    digest: vi.fn().mockReturnValue('mock-hash')
  }))
}));

describe('オーディオキャッシュAPI (優先度: 4)', () => {
  // プロセス環境変数を元に戻すために保存
  const originalEnv = { ...process.env };
  
  beforeEach(() => {
    vi.resetAllMocks();
    // キャッシュディレクトリのモック
    process.env.AUDIO_CACHE_DIR = 'test-cache-dir';
    vi.mocked(fs.existsSync).mockReturnValue(true);
    vi.mocked(path.join).mockImplementation((...args) => args.join('/'));
  });
  
  afterEach(() => {
    // 環境変数を元に戻す
    process.env = { ...originalEnv };
  });
  
  describe('GET', () => {
    it('キャッシュからオーディオファイルを取得できること', async () => {
      // キャッシュされたファイルが存在する場合のモック
      vi.mocked(fs.existsSync).mockReturnValue(true);
      vi.mocked(fs.readFileSync).mockReturnValue(Buffer.from('mock audio data'));
      
      // キャッシュ情報のモック
      vi.mocked(fs.readFileSync).mockImplementationOnce(() => JSON.stringify({
        'mock-hash': {
          text: 'こんにちは',
          voice: 'ja-JP-Neural2-C',
          timestamp: 1617123456789,
          size: 1024
        }
      }));
      
      // リクエストオブジェクトの作成
      const url = new URL('http://localhost:3000/api/audio-cache?text=こんにちは&voice=ja-JP-Neural2-C');
      const request = new NextRequest(url);
      
      // ハンドラーの実行
      const response = await GET(request);
      
      // レスポンスの検証
      expect(response).toBeInstanceOf(NextResponse);
      expect(response.status).toBe(200);
      expect(response.headers.get('Content-Type')).toBe('audio/mp3');
      expect(response.headers.get('Cache-Control')).toBe('max-age=31536000');
      
      // キャッシュ情報の更新を検証
      expect(fs.writeFileSync).toHaveBeenCalled();
    });
    
    it('必須パラメータがない場合400エラーを返すこと', async () => {
      // リクエストオブジェクトの作成（textパラメータなし）
      const url = new URL('http://localhost:3000/api/audio-cache?voice=ja-JP-Neural2-C');
      const request = new NextRequest(url);
      
      // ハンドラーの実行
      const response = await GET(request);
      
      // レスポンスの検証
      expect(response).toBeInstanceOf(NextResponse);
      expect(response.status).toBe(400);
      const data = await response.json();
      expect(data).toEqual({ error: 'Missing text or voice parameter' });
    });
    
    it('キャッシュにファイルがない場合404エラーを返すこと', async () => {
      // キャッシュされたファイルが存在しない場合のモック
      vi.mocked(fs.existsSync).mockImplementation((path) => {
        // キャッシュディレクトリは存在するが、ファイルは存在しないケース
        return !path.toString().includes('mock-hash.mp3');
      });
      
      // リクエストオブジェクトの作成
      const url = new URL('http://localhost:3000/api/audio-cache?text=こんにちは&voice=ja-JP-Neural2-C');
      const request = new NextRequest(url);
      
      // ハンドラーの実行
      const response = await GET(request);
      
      // レスポンスの検証
      expect(response).toBeInstanceOf(NextResponse);
      expect(response.status).toBe(404);
      const data = await response.json();
      expect(data).toEqual({ error: 'Audio not found in cache' });
    });
  });
  
  describe('POST', () => {
    it('オーディオファイルをキャッシュに保存できること', async () => {
      // FormDataのモック
      const formData = new FormData();
      formData.append('text', 'こんにちは');
      formData.append('voice', 'ja-JP-Neural2-C');
      
      // Fileオブジェクトのモック
      const audioBlob = new Blob(['mock audio data'], { type: 'audio/mp3' });
      const audioFile = new File([audioBlob], 'audio.mp3', { type: 'audio/mp3' });
      formData.append('audio', audioFile);
      
      // リクエストオブジェクトの作成
      const request = new NextRequest('http://localhost:3000/api/audio-cache', {
        method: 'POST',
        body: formData
      });
      
      // キャッシュ情報のモック
      vi.mocked(fs.readFileSync).mockImplementationOnce(() => JSON.stringify({}));
      
      // ハンドラーの実行
      const response = await POST(request);
      
      // レスポンスの検証
      expect(response).toBeInstanceOf(NextResponse);
      expect(response.status).toBe(200);
      const data = await response.json();
      expect(data).toEqual({ success: true, key: 'mock-hash' });
      
      // ファイル保存と情報更新の検証
      expect(fs.writeFileSync).toHaveBeenCalledTimes(2);
    });
    
    it('必須パラメータがない場合400エラーを返すこと', async () => {
      // 不完全なFormDataのモック（audioなし）
      const formData = new FormData();
      formData.append('text', 'こんにちは');
      formData.append('voice', 'ja-JP-Neural2-C');
      
      // リクエストオブジェクトの作成
      const request = new NextRequest('http://localhost:3000/api/audio-cache', {
        method: 'POST',
        body: formData
      });
      
      // ハンドラーの実行
      const response = await POST(request);
      
      // レスポンスの検証
      expect(response).toBeInstanceOf(NextResponse);
      expect(response.status).toBe(400);
      const data = await response.json();
      expect(data).toEqual({ error: 'Missing text, voice, or audio file' });
    });
  });
  
  describe('DELETE', () => {
    it('すべてのキャッシュファイルを削除できること', async () => {
      // キャッシュ情報のモック
      vi.mocked(fs.readFileSync).mockImplementationOnce(() => JSON.stringify({
        'file1': { text: 'hello', voice: 'en-US', timestamp: 1617123456789, size: 1024 },
        'file2': { text: 'こんにちは', voice: 'ja-JP', timestamp: 1617123456789, size: 2048 }
      }));
      
      // リクエストオブジェクトの作成
      const url = new URL('http://localhost:3000/api/audio-cache');
      const request = new NextRequest(url, { method: 'DELETE' });
      
      // ハンドラーの実行
      const response = await DELETE(request);
      
      // レスポンスの検証
      expect(response).toBeInstanceOf(NextResponse);
      expect(response.status).toBe(200);
      const data = await response.json();
      expect(data).toEqual({ success: true, deletedCount: 2 });
      
      // ファイル削除の検証
      expect(fs.unlinkSync).toHaveBeenCalledTimes(2);
      expect(fs.writeFileSync).toHaveBeenCalledTimes(1);
    });
    
    it('特定の日数より古いファイルのみを削除できること', async () => {
      // 現在の時刻
      const now = Date.now();
      
      // キャッシュ情報のモック（1つは新しく、1つは古い）
      vi.mocked(fs.readFileSync).mockImplementationOnce(() => JSON.stringify({
        'file1': { text: 'hello', voice: 'en-US', timestamp: now, size: 1024 },
        'file2': { text: 'old', voice: 'en-US', timestamp: now - 8 * 24 * 60 * 60 * 1000, size: 2048 }
      }));
      
      // リクエストオブジェクトの作成（7日より古いファイルを削除）
      const url = new URL('http://localhost:3000/api/audio-cache?olderThanDays=7');
      const request = new NextRequest(url, { method: 'DELETE' });
      
      // ハンドラーの実行
      const response = await DELETE(request);
      
      // レスポンスの検証
      expect(response).toBeInstanceOf(NextResponse);
      expect(response.status).toBe(200);
      const data = await response.json();
      expect(data).toEqual({ success: true, deletedCount: 1 });
      
      // ファイル削除の検証（古いファイルのみ）
      expect(fs.unlinkSync).toHaveBeenCalledTimes(1);
      expect(fs.unlinkSync).toHaveBeenCalledWith('test-cache-dir/file2.mp3');
    });
  });
  
  describe('PATCH', () => {
    it('キャッシュの統計情報を取得できること', async () => {
      // キャッシュ情報のモック
      vi.mocked(fs.readFileSync).mockImplementationOnce(() => JSON.stringify({
        'file1': { text: 'hello', voice: 'en-US', timestamp: 1617123456789, size: 1024 },
        'file2': { text: 'こんにちは', voice: 'ja-JP', timestamp: 1617123456789, size: 2048 }
      }));
      
      // リクエストオブジェクトの作成
      const url = new URL('http://localhost:3000/api/audio-cache');
      const request = new NextRequest(url, { method: 'PATCH' });
      
      // ハンドラーの実行
      const response = await PATCH(request);
      
      // レスポンスの検証
      expect(response).toBeInstanceOf(NextResponse);
      expect(response.status).toBe(200);
      const data = await response.json();
      expect(data).toEqual({
        count: 2,
        sizeBytes: 3072 // 1024 + 2048
      });
    });
  });
}); 