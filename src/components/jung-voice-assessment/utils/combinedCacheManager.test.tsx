/**
 * @jest-environment jsdom
 */
import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import CombinedCacheManager from './combinedCacheManager';
import { 
  getCombinedCacheStats, 
  clearCombinedCache,
  CacheType,
  CacheSettings
} from './combinedAudioCache';

// Mock dependencies
jest.mock('./combinedAudioCache', () => ({
  getCombinedCacheStats: jest.fn(),
  clearCombinedCache: jest.fn(),
  CacheType: {
    CLIENT: 'client',
    SERVER: 'server',
    BOTH: 'both'
  }
}));

describe('CombinedCacheManager.tsx', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  test('コンポーネントが正しくレンダリングされる 重要度:5', async () => {
    // Mock implementation
    (getCombinedCacheStats as jest.Mock).mockResolvedValue({
      client: { count: 5, sizeBytes: 51200 },
      server: { count: 10, sizeBytes: 102400 },
      total: { count: 15, sizeBytes: 153600 }
    });
    
    render(<CombinedCacheManager />);
    
    // Check that the component renders with title
    expect(screen.getByText('統合音声キャッシュ管理')).toBeInTheDocument();
    
    // Check setting section exists
    expect(screen.getByText('キャッシュ設定')).toBeInTheDocument();
    
    // Verify that getCombinedCacheStats was called
    expect(getCombinedCacheStats).toHaveBeenCalled();
    
    // Wait for the cache stats to load
    await waitFor(() => {
      expect(screen.getByText(/保存されている音声: 15 件/)).toBeInTheDocument();
    });
    
    // Check that the total stats are displayed
    expect(screen.getByText(/150 KB/)).toBeInTheDocument();
    expect(screen.getByText(/ブラウザ: 5 件/)).toBeInTheDocument();
    expect(screen.getByText(/サーバー: 10 件/)).toBeInTheDocument();
  });

  test('設定切り替えが機能し、onSettingsChangeが呼ばれる 重要度:5', async () => {
    // Mock implementation
    (getCombinedCacheStats as jest.Mock).mockResolvedValue({
      client: { count: 5, sizeBytes: 51200 },
      server: { count: 10, sizeBytes: 102400 },
      total: { count: 15, sizeBytes: 153600 }
    });
    
    const onSettingsChangeMock = jest.fn();
    
    render(
      <CombinedCacheManager 
        onSettingsChange={onSettingsChangeMock}
        initialSettings={{ 
          clientEnabled: true, 
          serverEnabled: true, 
          preferServer: true 
        }}
      />
    );
    
    // Wait for the component to load
    await waitFor(() => {
      expect(screen.getByText(/保存されている音声: 15 件/)).toBeInTheDocument();
    });
    
    // Default settings should have all switches on
    expect(screen.getByLabelText('ブラウザキャッシュ')).toBeChecked();
    expect(screen.getByLabelText('サーバーキャッシュ')).toBeChecked();
    expect(screen.getByLabelText('サーバー優先')).toBeChecked();
    
    // Toggle client cache off
    fireEvent.click(screen.getByLabelText('ブラウザキャッシュ'));
    
    // Check that onSettingsChange was called with updated settings
    expect(onSettingsChangeMock).toHaveBeenCalledWith(
      expect.objectContaining({
        clientEnabled: false,
        serverEnabled: true,
        preferServer: true
      })
    );
    
    // Toggle server cache off
    fireEvent.click(screen.getByLabelText('サーバーキャッシュ'));
    
    // Check that onSettingsChange was called with updated settings
    expect(onSettingsChangeMock).toHaveBeenCalledWith(
      expect.objectContaining({
        clientEnabled: false,
        serverEnabled: false,
        preferServer: true
      })
    );
    
    // Server preference should be disabled when server cache is off
    expect(screen.getByLabelText('サーバー優先')).toBeDisabled();
  });

  test('タブを切り替えると対応するキャッシュ情報が表示される 重要度:4', async () => {
    // Mock implementation
    (getCombinedCacheStats as jest.Mock).mockResolvedValue({
      client: { count: 5, sizeBytes: 51200 },
      server: { count: 10, sizeBytes: 102400 },
      total: { count: 15, sizeBytes: 153600 }
    });
    
    render(<CombinedCacheManager />);
    
    // Wait for the component to load
    await waitFor(() => {
      expect(screen.getByText(/保存されている音声: 15 件/)).toBeInTheDocument();
    });
    
    // Default tab should be "both"
    expect(screen.getByText(/保存されている音声: 15 件/)).toBeInTheDocument();
    
    // Switch to client tab
    fireEvent.click(screen.getByRole('tab', { name: 'ブラウザ' }));
    
    // Client stats should now be visible
    expect(screen.getByText(/ブラウザキャッシュ: 5 件/)).toBeInTheDocument();
    expect(screen.getByText(/使用ストレージ: 50 KB/)).toBeInTheDocument();
    
    // Switch to server tab
    fireEvent.click(screen.getByRole('tab', { name: 'サーバー' }));
    
    // Server stats should now be visible
    expect(screen.getByText(/サーバーキャッシュ: 10 件/)).toBeInTheDocument();
    expect(screen.getByText(/使用ストレージ: 100 KB/)).toBeInTheDocument();
  });

  test('更新ボタンをクリックすると統計情報が再取得される 重要度:4', async () => {
    // Setup initial and updated stats
    (getCombinedCacheStats as jest.Mock)
      .mockResolvedValueOnce({
        client: { count: 5, sizeBytes: 51200 },
        server: { count: 10, sizeBytes: 102400 },
        total: { count: 15, sizeBytes: 153600 }
      })
      .mockResolvedValueOnce({
        client: { count: 6, sizeBytes: 61440 },
        server: { count: 11, sizeBytes: 112640 },
        total: { count: 17, sizeBytes: 174080 }
      });
    
    render(<CombinedCacheManager />);
    
    // Wait for the initial stats to load
    await waitFor(() => {
      expect(screen.getByText(/保存されている音声: 15 件/)).toBeInTheDocument();
    });
    
    // Clear mock calls count
    (getCombinedCacheStats as jest.Mock).mockClear();
    
    // Click update button
    fireEvent.click(screen.getByRole('button', { name: '更新' }));
    
    // Check that getCombinedCacheStats was called again
    expect(getCombinedCacheStats).toHaveBeenCalledTimes(1);
    
    // Wait for the updated stats to load
    await waitFor(() => {
      expect(screen.getByText(/保存されている音声: 17 件/)).toBeInTheDocument();
      expect(screen.getByText(/使用ストレージ: 170 KB/)).toBeInTheDocument();
      expect(screen.getByText(/ブラウザ: 6 件/)).toBeInTheDocument();
      expect(screen.getByText(/サーバー: 11 件/)).toBeInTheDocument();
    });
  });

  test('キャッシュ管理ボタンをクリックすると確認UIが表示される 重要度:4', async () => {
    // Mock implementation
    (getCombinedCacheStats as jest.Mock).mockResolvedValue({
      client: { count: 5, sizeBytes: 51200 },
      server: { count: 10, sizeBytes: 102400 },
      total: { count: 15, sizeBytes: 153600 }
    });
    
    render(<CombinedCacheManager />);
    
    // Wait for the component to load
    await waitFor(() => {
      expect(screen.getByText(/保存されている音声: 15 件/)).toBeInTheDocument();
    });
    
    // Click the manage button
    fireEvent.click(screen.getByText(/すべてのキャッシュを管理/));
    
    // Check that the confirmation UI is displayed
    expect(screen.getByText('古いキャッシュを削除')).toBeInTheDocument();
    expect(screen.getByText('すべて削除')).toBeInTheDocument();
    expect(screen.getByText('キャンセル')).toBeInTheDocument();
  });

  test('タブに応じたキャッシュタイプのクリアが実行される 重要度:5', async () => {
    // Mock implementation
    (getCombinedCacheStats as jest.Mock).mockResolvedValue({
      client: { count: 5, sizeBytes: 51200 },
      server: { count: 10, sizeBytes: 102400 },
      total: { count: 15, sizeBytes: 153600 }
    });
    
    (clearCombinedCache as jest.Mock).mockResolvedValue(true);
    
    render(<CombinedCacheManager />);
    
    // Wait for the component to load
    await waitFor(() => {
      expect(screen.getByText(/保存されている音声: 15 件/)).toBeInTheDocument();
    });
    
    // Switch to client tab
    fireEvent.click(screen.getByRole('tab', { name: 'ブラウザ' }));
    
    // Click the manage button (now should indicate client cache only)
    fireEvent.click(screen.getByText(/ブラウザキャッシュを管理/));
    
    // Click the clear all button
    fireEvent.click(screen.getByText('すべて削除'));
    
    // Check that clearCombinedCache was called with CLIENT type
    expect(clearCombinedCache).toHaveBeenCalledWith(CacheType.CLIENT);
    
    // Wait for the confirmation UI to disappear
    await waitFor(() => {
      expect(screen.queryByText('すべて削除')).not.toBeInTheDocument();
    });
    
    // Switch to server tab
    fireEvent.click(screen.getByRole('tab', { name: 'サーバー' }));
    
    // Click the manage button (now should indicate server cache only)
    fireEvent.click(screen.getByText(/サーバーキャッシュを管理/));
    
    // Click old cache button
    fireEvent.click(screen.getByText('古いキャッシュを削除'));
    
    // Check that clearCombinedCache was called with SERVER type and 7 days
    expect(clearCombinedCache).toHaveBeenCalledWith(CacheType.SERVER, 7);
  });

  test('キャッシュが空の場合管理ボタンが無効化される 重要度:3', async () => {
    // Mock empty cache
    (getCombinedCacheStats as jest.Mock).mockResolvedValue({
      client: { count: 0, sizeBytes: 0 },
      server: { count: 0, sizeBytes: 0 },
      total: { count: 0, sizeBytes: 0 }
    });
    
    render(<CombinedCacheManager />);
    
    // Wait for the stats to load
    await waitFor(() => {
      expect(screen.getByText(/保存されている音声: 0 件/)).toBeInTheDocument();
    });
    
    // Check that manage button is disabled
    const manageButton = screen.getByText(/すべてのキャッシュを管理/);
    expect(manageButton).toBeDisabled();
    
    // Switch to client tab and check that button is still disabled
    fireEvent.click(screen.getByRole('tab', { name: 'ブラウザ' }));
    
    const clientManageButton = screen.getByText(/ブラウザキャッシュを管理/);
    expect(clientManageButton).toBeDisabled();
  });

  test('キャンセルボタンをクリックすると確認UIが非表示になる 重要度:3', async () => {
    // Mock implementation
    (getCombinedCacheStats as jest.Mock).mockResolvedValue({
      client: { count: 5, sizeBytes: 51200 },
      server: { count: 10, sizeBytes: 102400 },
      total: { count: 15, sizeBytes: 153600 }
    });
    
    render(<CombinedCacheManager />);
    
    // Wait for the component to load
    await waitFor(() => {
      expect(screen.getByText(/保存されている音声: 15 件/)).toBeInTheDocument();
    });
    
    // Click the manage button
    fireEvent.click(screen.getByText(/すべてのキャッシュを管理/));
    
    // Check that the confirmation UI is displayed
    expect(screen.getByText('キャンセル')).toBeInTheDocument();
    
    // Click the cancel button
    fireEvent.click(screen.getByText('キャンセル'));
    
    // Confirmation UI should be hidden
    expect(screen.queryByText('古いキャッシュを削除')).not.toBeInTheDocument();
    expect(screen.queryByText('すべて削除')).not.toBeInTheDocument();
    expect(screen.queryByText('キャンセル')).not.toBeInTheDocument();
    
    // Manage button should be visible again
    expect(screen.getByText(/すべてのキャッシュを管理/)).toBeInTheDocument();
  });

  test('initialSettingsプロパティが正しく適用される 重要度:4', async () => {
    // Mock implementation
    (getCombinedCacheStats as jest.Mock).mockResolvedValue({
      client: { count: 5, sizeBytes: 51200 },
      server: { count: 10, sizeBytes: 102400 },
      total: { count: 15, sizeBytes: 153600 }
    });
    
    const customSettings: CacheSettings = {
      clientEnabled: true,
      serverEnabled: false,
      preferServer: false
    };
    
    render(<CombinedCacheManager initialSettings={customSettings} />);
    
    // Wait for the component to load
    await waitFor(() => {
      expect(screen.getByText(/保存されている音声: 15 件/)).toBeInTheDocument();
    });
    
    // Check that settings match the initialSettings
    expect(screen.getByLabelText('ブラウザキャッシュ')).toBeChecked();
    expect(screen.getByLabelText('サーバーキャッシュ')).not.toBeChecked();
    expect(screen.getByLabelText('サーバー優先')).not.toBeChecked();
    
    // Server preference should be disabled when server cache is off
    expect(screen.getByLabelText('サーバー優先')).toBeDisabled();
  });
}); 