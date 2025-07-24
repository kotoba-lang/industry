import { test, expect } from '@playwright/test';

/**
 * テーマ切り替え機能のテスト
 * ダークモードとライトモードの切り替えが正常に動作することを確認
 */
test.describe('Theme Tests', () => {
  test('テーマ切り替えボタンが表示される', async ({ page }) => {
    await page.goto('/');
    
    // テーマ切り替えボタンが存在することを確認
    const themeToggle = page.locator('button[aria-label*="Switch to"]');
    await expect(themeToggle).toBeVisible();
  });

  test('ライトモードからダークモードへの切り替え', async ({ page }) => {
    await page.goto('/');
    
    // 初期状態はライトモード
    await expect(page.locator('html')).toHaveClass(/light/);
    
    // テーマ切り替えボタンをクリック
    const themeToggle = page.locator('button[aria-label*="Switch to"]');
    await themeToggle.click();
    
    // ダークモードに切り替わることを確認
    await expect(page.locator('html')).toHaveClass(/dark/);
  });

  test('ダークモードからライトモードへの切り替え', async ({ page }) => {
    await page.goto('/');
    
    // まずダークモードに切り替え
    const themeToggle = page.locator('button[aria-label*="Switch to"]');
    await themeToggle.click();
    await expect(page.locator('html')).toHaveClass(/dark/);
    
    // 再度クリックしてライトモードに戻す
    await themeToggle.click();
    
    // ライトモードに戻ることを確認
    await expect(page.locator('html')).toHaveClass(/light/);
  });

  test('テーマ設定がローカルストレージに保存される', async ({ page }) => {
    await page.goto('/');
    
    // ダークモードに切り替え
    const themeToggle = page.locator('button[aria-label*="Switch to"]');
    await themeToggle.click();
    
    // ローカルストレージにテーマ設定が保存されることを確認
    const themeValue = await page.evaluate(() => localStorage.getItem('kotoba-theme'));
    expect(themeValue).toBe('dark');
    
    // ページをリロードして設定が保持されることを確認
    await page.reload();
    await expect(page.locator('html')).toHaveClass(/dark/);
  });

  test('ヘッダーのテーマ切り替えボタンのスタイル', async ({ page }) => {
    await page.goto('/');
    
    const themeToggle = page.locator('button[aria-label*="Switch to"]');
    
    // ボタンが適切なスタイルを持っていることを確認
    await expect(themeToggle).toHaveClass(/bg-gray-200/);
    await expect(themeToggle).toHaveClass(/rounded-full/);
    
    // ダークモードに切り替え
    await themeToggle.click();
    
    // ダークモード時のボタンスタイルを確認
    await expect(themeToggle).toHaveClass(/bg-gray-700/);
  });

  test('システムテーマの自動検出', async ({ page }) => {
    // システムがダークモードの場合のテスト
    await page.emulateMedia({ colorScheme: 'dark' });
    await page.goto('/');
    
    // システムテーマが自動検出されることを確認（初回アクセス時）
    // 注意: ローカルストレージに既に設定がある場合は自動検出されない
    const hasStoredTheme = await page.evaluate(() => localStorage.getItem('kotoba-theme'));
    
    if (!hasStoredTheme) {
      await expect(page.locator('html')).toHaveClass(/dark/);
    }
  });

  test('テーマ切り替え時のアニメーション', async ({ page }) => {
    await page.goto('/');
    
    const themeToggle = page.locator('button[aria-label*="Switch to"]');
    
    // テーマ切り替えボタンにアニメーションクラスがあることを確認
    await expect(themeToggle).toHaveClass(/transition-colors/);
    await expect(themeToggle).toHaveClass(/duration-200/);
  });
}); 