/**
 * @jest-environment jsdom
 */
import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import AudioCacheManager from './cacheManager';
import { getAudioCacheSize, clearAudioCache } from './audioCache';

// Mock dependencies
jest.mock('./audioCache', () => ({
  getAudioCacheSize: jest.fn(),
  clearAudioCache: jest.fn()
}));

describe('AudioCacheManager.tsx', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  test('コンポーネントが正しくレンダリングされる 重要度:5', async () => {
    // Mock implementation
    (getAudioCacheSize as jest.Mock).mockResolvedValue({ count: 5, sizeBytes: 51200 });
    
    render(<AudioCacheManager />);
    
    // Check that the component renders with title
    expect(screen.getByText('音声キャッシュ管理')).toBeInTheDocument();
    
    // Verify that getAudioCacheSize was called
    expect(getAudioCacheSize).toHaveBeenCalled();
    
    // Wait for the cache stats to load
    await waitFor(() => {
      expect(screen.getByText(/保存されている音声:/)).toBeInTheDocument();
    });
    
    // Check that the stats are displayed
    expect(screen.getByText(/5 件/)).toBeInTheDocument();
    expect(screen.getByText(/50 KB/)).toBeInTheDocument();
  });

  test('キャッシュが空の場合も正しく表示される 重要度:3', async () => {
    // Mock empty cache
    (getAudioCacheSize as jest.Mock).mockResolvedValue({ count: 0, sizeBytes: 0 });
    
    render(<AudioCacheManager />);
    
    // Wait for the stats to load
    await waitFor(() => {
      expect(screen.getByText(/保存されている音声:/)).toBeInTheDocument();
    });
    
    // Check that 0 stats are displayed
    expect(screen.getByText(/0 件/)).toBeInTheDocument();
    expect(screen.getByText(/0 Bytes/)).toBeInTheDocument();
    
    // Check that the manage button is disabled
    const manageButton = screen.getByRole('button', { name: 'キャッシュを管理' });
    expect(manageButton).toBeDisabled();
  });

  test('更新ボタンをクリックすると統計情報が再取得される 重要度:4', async () => {
    // Setup initial and updated stats
    (getAudioCacheSize as jest.Mock)
      .mockResolvedValueOnce({ count: 5, sizeBytes: 51200 })
      .mockResolvedValueOnce({ count: 6, sizeBytes: 61440 });
    
    render(<AudioCacheManager />);
    
    // Wait for the initial stats to load
    await waitFor(() => {
      expect(screen.getByText(/5 件/)).toBeInTheDocument();
    });
    
    // Clear mock calls count
    (getAudioCacheSize as jest.Mock).mockClear();
    
    // Click update button
    fireEvent.click(screen.getByRole('button', { name: '更新' }));
    
    // Check that getAudioCacheSize was called again
    expect(getAudioCacheSize).toHaveBeenCalledTimes(1);
    
    // Wait for the updated stats to load
    await waitFor(() => {
      expect(screen.getByText(/6 件/)).toBeInTheDocument();
      expect(screen.getByText(/60 KB/)).toBeInTheDocument();
    });
  });

  test('管理ボタンをクリックすると確認UIが表示される 重要度:4', async () => {
    // Setup cache stats
    (getAudioCacheSize as jest.Mock).mockResolvedValue({ count: 5, sizeBytes: 51200 });
    
    render(<AudioCacheManager />);
    
    // Wait for the stats to load
    await waitFor(() => {
      expect(screen.getByText(/5 件/)).toBeInTheDocument();
    });
    
    // Click the manage button
    fireEvent.click(screen.getByRole('button', { name: 'キャッシュを管理' }));
    
    // Check that the confirmation UI is displayed with specific buttons
    expect(screen.getByRole('button', { name: '古いキャッシュを削除' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'すべて削除' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'キャンセル' })).toBeInTheDocument();
    
    // Explanatory text should be visible
    expect(screen.getByText(/キャッシュを削除すると、音声が再度必要になった場合/)).toBeInTheDocument();
  });

  test('古いキャッシュを削除ボタンをクリックすると古いキャッシュのみクリアされる 重要度:5', async () => {
    // Setup cache stats
    (getAudioCacheSize as jest.Mock)
      .mockResolvedValueOnce({ count: 5, sizeBytes: 51200 })
      .mockResolvedValueOnce({ count: 2, sizeBytes: 20480 });
    
    // Setup clearAudioCache mock
    (clearAudioCache as jest.Mock).mockResolvedValue(true);
    
    render(<AudioCacheManager />);
    
    // Wait for the stats to load
    await waitFor(() => {
      expect(screen.getByText(/5 件/)).toBeInTheDocument();
    });
    
    // Click the manage button
    fireEvent.click(screen.getByRole('button', { name: 'キャッシュを管理' }));
    
    // Click the old cache clear button
    fireEvent.click(screen.getByRole('button', { name: '古いキャッシュを削除' }));
    
    // Check that clearAudioCache was called with 7 days
    expect(clearAudioCache).toHaveBeenCalledWith(7);
    
    // Wait for the operation to complete and check status changes
    await waitFor(() => {
      expect(screen.getByText(/2 件/)).toBeInTheDocument();
    });
    
    // Check that stats were updated
    expect(screen.getByText(/2 件/)).toBeInTheDocument();
    expect(screen.getByText(/20 KB/)).toBeInTheDocument();
    
    // Confirmation UI should be hidden and manage button should be visible again
    expect(screen.getByRole('button', { name: 'キャッシュを管理' })).toBeInTheDocument();
  });

  test('すべて削除ボタンをクリックするとすべてのキャッシュがクリアされる 重要度:5', async () => {
    // Setup cache stats
    (getAudioCacheSize as jest.Mock)
      .mockResolvedValueOnce({ count: 5, sizeBytes: 51200 })
      .mockResolvedValueOnce({ count: 0, sizeBytes: 0 });
    
    // Setup clearAudioCache mock
    (clearAudioCache as jest.Mock).mockResolvedValue(true);
    
    render(<AudioCacheManager />);
    
    // Wait for the stats to load
    await waitFor(() => {
      expect(screen.getByText(/5 件/)).toBeInTheDocument();
    });
    
    // Click the manage button
    fireEvent.click(screen.getByRole('button', { name: 'キャッシュを管理' }));
    
    // Click the clear all button
    fireEvent.click(screen.getByRole('button', { name: 'すべて削除' }));
    
    // Check that clearAudioCache was called with no arguments
    expect(clearAudioCache).toHaveBeenCalledWith();
    
    // Wait for the operation to complete
    await waitFor(() => {
      expect(screen.getByText(/0 件/)).toBeInTheDocument();
    });
    
    // Check that stats were updated
    expect(screen.getByText(/0 件/)).toBeInTheDocument();
    expect(screen.getByText(/0 Bytes/)).toBeInTheDocument();
    
    // Confirmation UI should be hidden and manage button should be visible again
    expect(screen.getByRole('button', { name: 'キャッシュを管理' })).toBeInTheDocument();
  });

  test('キャンセルボタンをクリックすると確認UIが非表示になる 重要度:3', async () => {
    // Setup cache stats
    (getAudioCacheSize as jest.Mock).mockResolvedValue({ count: 5, sizeBytes: 51200 });
    
    render(<AudioCacheManager />);
    
    // Wait for the stats to load
    await waitFor(() => {
      expect(screen.getByText(/5 件/)).toBeInTheDocument();
    });
    
    // Click the manage button
    fireEvent.click(screen.getByRole('button', { name: 'キャッシュを管理' }));
    
    // Check that the confirmation UI is displayed
    expect(screen.getByRole('button', { name: 'キャンセル' })).toBeInTheDocument();
    
    // Click the cancel button
    fireEvent.click(screen.getByRole('button', { name: 'キャンセル' }));
    
    // Confirmation UI should be hidden and manage button should be visible again
    expect(screen.getByRole('button', { name: 'キャッシュを管理' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '古いキャッシュを削除' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'すべて削除' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'キャンセル' })).not.toBeInTheDocument();
  });

  test('キャッシュクリア中はボタンが無効化される 重要度:3', async () => {
    // Setup cache stats
    (getAudioCacheSize as jest.Mock)
      .mockResolvedValueOnce({ count: 5, sizeBytes: 51200 })
      .mockResolvedValueOnce({ count: 0, sizeBytes: 0 });
    
    // Setup delayed clearAudioCache to test disabled state
    (clearAudioCache as jest.Mock).mockImplementation(() => {
      return new Promise(resolve => {
        setTimeout(() => resolve(true), 50);
      });
    });
    
    render(<AudioCacheManager />);
    
    // Wait for the stats to load
    await waitFor(() => {
      expect(screen.getByText(/5 件/)).toBeInTheDocument();
    });
    
    // Click the manage button
    fireEvent.click(screen.getByRole('button', { name: 'キャッシュを管理' }));
    
    // Take a reference to both buttons before clicking
    const clearOldButton = screen.getByRole('button', { name: '古いキャッシュを削除' });
    const clearAllButton = screen.getByRole('button', { name: 'すべて削除' });
    const cancelButton = screen.getByRole('button', { name: 'キャンセル' });
    
    // Click the clear all button
    fireEvent.click(clearAllButton);
    
    // Check both buttons and cancel button should be disabled during clearing
    await waitFor(() => {
      expect(cancelButton).toBeDisabled();
    }, { timeout: 30000 });
    
    // Wait for the operation to complete
    await waitFor(() => {
      expect(screen.getByText(/0 件/)).toBeInTheDocument();
    }, { timeout: 30000 });
  });
}); 