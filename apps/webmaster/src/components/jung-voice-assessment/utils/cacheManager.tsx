'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { Button } from '@/components/ui/button';
import { getAudioCacheSize, clearAudioCache } from './audioCache';

/**
 * オーディオキャッシュ管理コンポーネント
 * キャッシュサイズの表示と、キャッシュクリア機能を提供します
 */
export default function AudioCacheManager() {
  const [cacheStats, setCacheStats] = useState<{ count: number, sizeBytes: number } | null>(null);
  const [isClearing, setIsClearing] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);

  // キャッシュの統計情報を取得
  const loadCacheStats = useCallback(async () => {
    const stats = await getAudioCacheSize();
    setCacheStats(stats);
  }, []);

  // コンポーネント初期化時に統計情報を取得
  useEffect(() => {
    loadCacheStats();
  }, [loadCacheStats]);

  // キャッシュのサイズを人間が読みやすい形式に変換
  const formatBytes = (bytes: number): string => {
    if (bytes === 0) return '0 Bytes';
    
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  // 古いキャッシュをクリア（7日以上前のもの）
  const clearOldCache = async () => {
    setIsClearing(true);
    try {
      await clearAudioCache(7); // 7日以上前のキャッシュをクリア
      await loadCacheStats();
    } catch (err) {
      console.error('Error clearing old cache:', err);
    } finally {
      setIsClearing(false);
      setShowConfirm(false);
    }
  };

  // すべてのキャッシュをクリア
  const clearAllCache = async () => {
    setIsClearing(true);
    try {
      await clearAudioCache(); // すべてのキャッシュをクリア
      await loadCacheStats();
    } catch (err) {
      console.error('Error clearing all cache:', err);
    } finally {
      setIsClearing(false);
      setShowConfirm(false);
    }
  };

  return (
    <div className="p-4 bg-white rounded-lg shadow-sm border border-gray-200">
      <h3 className="text-lg font-medium mb-4">音声キャッシュ管理</h3>
      
      {cacheStats ? (
        <div className="mb-4">
          <p className="mb-1">保存されている音声: <span className="font-medium">{cacheStats.count} 件</span></p>
          <p>使用ストレージ: <span className="font-medium">{formatBytes(cacheStats.sizeBytes)}</span></p>
        </div>
      ) : (
        <p className="mb-4 text-gray-500">キャッシュの統計情報を取得中...</p>
      )}
      
      {!showConfirm ? (
        <div className="flex flex-col space-y-2">
          <Button
            onClick={() => setShowConfirm(true)}
            variant="outline"
            size="sm"
            className="w-full"
            disabled={isClearing || (cacheStats?.count || 0) === 0}
          >
            キャッシュを管理
          </Button>
          
          <Button
            onClick={loadCacheStats}
            variant="ghost"
            size="sm"
            className="w-full text-xs"
          >
            更新
          </Button>
        </div>
      ) : (
        <div className="space-y-2">
          <p className="text-sm text-gray-600 mb-2">キャッシュを削除すると、音声が再度必要になった場合にAPIから再取得する必要があります。</p>
          
          <div className="flex space-x-2">
            <Button
              onClick={clearOldCache}
              variant="outline"
              size="sm"
              className="flex-1"
              disabled={isClearing}
            >
              {isClearing ? '削除中...' : '古いキャッシュを削除'}
            </Button>
            
            <Button
              onClick={clearAllCache}
              variant="destructive"
              size="sm"
              className="flex-1"
              disabled={isClearing}
            >
              {isClearing ? '削除中...' : 'すべて削除'}
            </Button>
          </div>
          
          <Button
            onClick={() => setShowConfirm(false)}
            variant="ghost"
            size="sm"
            className="w-full"
            disabled={isClearing}
          >
            キャンセル
          </Button>
        </div>
      )}
      
      <div className="mt-4 text-xs text-gray-500">
        <p>※ キャッシュは自動的に再利用され、APIコールとデータ転送を節約します。</p>
        <p>※ キャッシュはこのブラウザにのみ保存され、サーバーには送信されません。</p>
      </div>
    </div>
  );
} 