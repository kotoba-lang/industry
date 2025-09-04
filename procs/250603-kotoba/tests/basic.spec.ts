import { test, expect } from '@playwright/test';

/**
 * 基本的なテスト
 * Bitコンポーネント統合の動作を確認
 */
test.describe('Basic Tests', () => {
  test('ホストアプリケーションの基本動作', async ({ page }) => {
    // ホストアプリケーションにアクセス
    await page.goto('http://localhost:5174');
    
    // ページが読み込まれるまで待機
    await page.waitForLoadState('networkidle');
    
    // 基本的な要素が存在することを確認
    await expect(page.locator('body')).toBeVisible();
    
    // エディターとグラフのセクションが存在することを確認
    await expect(page.locator('h2:has-text("Editor")')).toBeVisible();
    await expect(page.locator('h2:has-text("Graph")')).toBeVisible();
  });

  test('Bitコンポーネントの統合確認', async ({ page }) => {
    // ホストアプリケーションにアクセス
    await page.goto('http://localhost:5174');
    
    // ページが読み込まれるまで待機
    await page.waitForLoadState('networkidle');
    
    // Bitコンポーネントが正しく統合されていることを確認
    await expect(page.locator('[data-testid="editor"]')).toBeVisible();
    await expect(page.locator('.ProseMirror').filter({ hasText: 'Kotoba Editor' }).first()).toBeVisible();
    
    // テーマ切り替えボタンが存在することを確認
    await expect(page.locator('button[aria-label*="Switch to"]')).toBeVisible();
  });
}); 