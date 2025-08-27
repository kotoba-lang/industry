'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Switch } from '@/components/ui/switch';
import { Label } from '@/components/ui/label';
import { 
  getCombinedCacheStats, 
  clearCombinedCache, 
  CacheType,
  CacheSettings,
  CacheStats
} from './combinedAudioCache';

/**
 * 統合オーディオキャッシュ管理コンポーネント
 * 
 * クライアントサイド（IndexedDB）とサーバーサイドの両方のキャッシュを
 * 管理するためのUIを提供します。
 */
interface CombinedCacheManagerProps {
  onSettingsChange?: (settings: CacheSettings) => void;
  initialSettings?: CacheSettings;
}

export default function CombinedCacheManager({ 
  onSettingsChange,
  initialSettings = { 
    clientEnabled: true, 
    serverEnabled: true, 
    preferServer: true 
  }
}: CombinedCacheManagerProps) {
  const [cacheStats, setCacheStats] = useState<CacheStats | null>(null);
  const [isClearing, setIsClearing] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);
  const [activeTab, setActiveTab] = useState<string>('both');
  const [settings, setSettings] = useState<CacheSettings>(initialSettings);
  
  // キャッシュの統計情報を取得
  const loadCacheStats = useCallback(async () => {
    try {
      const stats = await getCombinedCacheStats();
      setCacheStats(stats);
    } catch (err) {
      console.error('Failed to load cache stats:', err);
    }
  }, []);

  // コンポーネント初期化時に統計情報を取得
  useEffect(() => {
    loadCacheStats();
  }, [loadCacheStats]);

  // 設定が変更されたときの処理
  useEffect(() => {
    if (onSettingsChange) {
      onSettingsChange(settings);
    }
  }, [settings, onSettingsChange]);

  // キャッシュのサイズを人間が読みやすい形式に変換
  const formatBytes = (bytes: number): string => {
    if (bytes === 0) return '0 Bytes';
    
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  // 設定を変更する関数
  const updateSettings = (newSettings: Partial<CacheSettings>) => {
    setSettings(prev => {
      const updated = { ...prev, ...newSettings };
      return updated;
    });
  };

  // 選択されたキャッシュタイプを取得
  const getSelectedCacheType = (): CacheType => {
    switch (activeTab) {
      case 'client': return CacheType.CLIENT;
      case 'server': return CacheType.SERVER;
      default: return CacheType.BOTH;
    }
  };

  // 古いキャッシュをクリア（7日以上前のもの）
  const clearOldCache = async () => {
    setIsClearing(true);
    try {
      await clearCombinedCache(getSelectedCacheType(), 7);
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
      await clearCombinedCache(getSelectedCacheType());
      await loadCacheStats();
    } catch (err) {
      console.error('Error clearing all cache:', err);
    } finally {
      setIsClearing(false);
      setShowConfirm(false);
    }
  };

  // キャッシュ統計が空かどうかを判定
  const isCacheEmpty = (stats: CacheStats | null, type: 'client' | 'server' | 'both'): boolean => {
    if (!stats) return true;
    
    if (type === 'client') {
      return stats.client.count === 0;
    } else if (type === 'server') {
      return stats.server.count === 0;
    } else {
      return stats.total.count === 0;
    }
  };

  return (
    <div className="p-4 bg-white rounded-lg shadow-sm border border-gray-200">
      <h3 className="text-lg font-medium mb-4">統合音声キャッシュ管理</h3>
      
      {/* キャッシュ設定 */}
      <div className="mb-4 p-3 bg-gray-50 rounded-md">
        <h4 className="text-sm font-medium mb-2">キャッシュ設定</h4>
        
        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <div className="space-y-0.5">
              <Label htmlFor="client-cache">ブラウザキャッシュ</Label>
              <p className="text-xs text-gray-500">ブラウザのローカルストレージにキャッシュ</p>
            </div>
            <Switch
              id="client-cache"
              checked={settings.clientEnabled}
              onCheckedChange={(checked) => updateSettings({ clientEnabled: checked })}
            />
          </div>
          
          <div className="flex items-center justify-between">
            <div className="space-y-0.5">
              <Label htmlFor="server-cache">サーバーキャッシュ</Label>
              <p className="text-xs text-gray-500">サーバー上にキャッシュを保存</p>
            </div>
            <Switch
              id="server-cache"
              checked={settings.serverEnabled}
              onCheckedChange={(checked) => updateSettings({ serverEnabled: checked })}
            />
          </div>
          
          <div className="flex items-center justify-between">
            <div className="space-y-0.5">
              <Label htmlFor="prefer-server">サーバー優先</Label>
              <p className="text-xs text-gray-500">可能な場合はサーバーキャッシュを使用</p>
            </div>
            <Switch
              id="prefer-server"
              checked={settings.preferServer}
              onCheckedChange={(checked) => updateSettings({ preferServer: checked })}
              disabled={!settings.serverEnabled}
            />
          </div>
        </div>
      </div>
      
      {/* タブによるキャッシュ統計表示 */}
      <Tabs 
        defaultValue="both" 
        value={activeTab} 
        onValueChange={setActiveTab}
        className="mt-4"
      >
        <TabsList className="grid grid-cols-3 mb-4">
          <TabsTrigger value="both">全キャッシュ</TabsTrigger>
          <TabsTrigger value="client">ブラウザ</TabsTrigger>
          <TabsTrigger value="server">サーバー</TabsTrigger>
        </TabsList>
        
        <TabsContent value="both">
          {cacheStats ? (
            <div className="mb-4">
              <p className="mb-1">保存されている音声: <span className="font-medium">{cacheStats.total.count} 件</span></p>
              <p>使用ストレージ: <span className="font-medium">{formatBytes(cacheStats.total.sizeBytes)}</span></p>
              <div className="mt-2 text-xs text-gray-500">
                <p>ブラウザ: {cacheStats.client.count} 件 ({formatBytes(cacheStats.client.sizeBytes)})</p>
                <p>サーバー: {cacheStats.server.count} 件 ({formatBytes(cacheStats.server.sizeBytes)})</p>
              </div>
            </div>
          ) : (
            <p className="mb-4 text-gray-500">キャッシュの統計情報を取得中...</p>
          )}
        </TabsContent>
        
        <TabsContent value="client">
          {cacheStats ? (
            <div className="mb-4">
              <p className="mb-1">ブラウザキャッシュ: <span className="font-medium">{cacheStats.client.count} 件</span></p>
              <p>使用ストレージ: <span className="font-medium">{formatBytes(cacheStats.client.sizeBytes)}</span></p>
            </div>
          ) : (
            <p className="mb-4 text-gray-500">キャッシュの統計情報を取得中...</p>
          )}
        </TabsContent>
        
        <TabsContent value="server">
          {cacheStats ? (
            <div className="mb-4">
              <p className="mb-1">サーバーキャッシュ: <span className="font-medium">{cacheStats.server.count} 件</span></p>
              <p>使用ストレージ: <span className="font-medium">{formatBytes(cacheStats.server.sizeBytes)}</span></p>
            </div>
          ) : (
            <p className="mb-4 text-gray-500">キャッシュの統計情報を取得中...</p>
          )}
        </TabsContent>
      </Tabs>
      
      {/* キャッシュ管理ボタン */}
      {!showConfirm ? (
        <div className="flex flex-col space-y-2 mt-4">
          <Button
            onClick={() => setShowConfirm(true)}
            variant="outline"
            size="sm"
            className="w-full"
            disabled={isClearing || (cacheStats ? isCacheEmpty(cacheStats, activeTab as any) : true)}
          >
            {activeTab === 'both' ? 'すべてのキャッシュ' : 
             activeTab === 'client' ? 'ブラウザキャッシュ' : 'サーバーキャッシュ'}を管理
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
        <div className="space-y-2 mt-4">
          <p className="text-sm text-gray-600 mb-2">
            キャッシュを削除すると、音声が再度必要になった場合にAPIから再取得する必要があります。
          </p>
          
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
        <p>※ キャッシュは自動的に再利用され、APIコールとデータ転送を節約します</p>
        <p>※ ブラウザキャッシュはこのデバイスのみ、サーバーキャッシュは全ユーザー間で共有されます</p>
      </div>
    </div>
  );
} 