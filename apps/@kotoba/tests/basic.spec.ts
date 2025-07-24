import { test, expect } from '@playwright/test';

/**
 * 基本的なテスト
 * Module Federationの動作を段階的に確認
 */
test.describe('Basic Tests', () => {
  test('ホストアプリケーションの基本動作', async ({ page }) => {
    // ホストアプリケーションにアクセス
    await page.goto('http://localhost:5173');
    
    // ページが読み込まれるまで待機
    await page.waitForLoadState('networkidle');
    
    // 基本的な要素が存在することを確認
    await expect(page.locator('body')).toBeVisible();
    
    // エディターとグラフのセクションが存在することを確認
    await expect(page.locator('text=Editor')).toBeVisible();
    await expect(page.locator('text=Graph')).toBeVisible();
  });

  test('エディターリモートアプリケーションの基本動作', async ({ page }) => {
    // エディターリモートアプリケーションに直接アクセス
    await page.goto('http://localhost:5001');
    
    // ページが読み込まれるまで待機
    await page.waitForLoadState('networkidle');
    
    // 基本的な要素が存在することを確認
    await expect(page.locator('body')).toBeVisible();
    await expect(page.locator('h1')).toBeVisible();
  });

  test('グラフリモートアプリケーションの基本動作', async ({ page }) => {
    // グラフリモートアプリケーションに直接アクセス
    await page.goto('http://localhost:5002');
    
    // ページが読み込まれるまで待機
    await page.waitForLoadState('networkidle');
    
    // 基本的な要素が存在することを確認
    await expect(page.locator('body')).toBeVisible();
    await expect(page.locator('h1')).toBeVisible();
  });
}); 