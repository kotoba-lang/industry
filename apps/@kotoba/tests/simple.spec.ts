import { test, expect } from '@playwright/test';

/**
 * シンプルなテスト
 * 基本的なページ読み込みを確認
 */
test.describe('Simple Tests', () => {
  test('ホストアプリケーションのページ読み込み', async ({ page }) => {
    // ホストアプリケーションにアクセス
    await page.goto('http://localhost:5173');
    
    // ページが読み込まれるまで待機
    await page.waitForLoadState('networkidle');
    
    // 基本的な要素が存在することを確認
    await expect(page.locator('body')).toBeVisible();
    await expect(page.locator('#root')).toBeVisible();
    
    // ページタイトルを確認
    await expect(page).toHaveTitle(/Vite \+ React \+ TS/);
  });

  test('エディターリモートアプリケーションのページ読み込み', async ({ page }) => {
    // エディターリモートアプリケーションに直接アクセス
    await page.goto('http://localhost:5001');
    
    // ページが読み込まれるまで待機
    await page.waitForLoadState('networkidle');
    
    // 基本的な要素が存在することを確認
    await expect(page.locator('body')).toBeVisible();
    await expect(page.locator('#root')).toBeVisible();
    
    // ページタイトルを確認
    await expect(page).toHaveTitle(/Vite \+ React \+ TS/);
  });

  test('グラフリモートアプリケーションのページ読み込み', async ({ page }) => {
    // グラフリモートアプリケーションに直接アクセス
    await page.goto('http://localhost:5002');
    
    // ページが読み込まれるまで待機
    await page.waitForLoadState('networkidle');
    
    // 基本的な要素が存在することを確認
    await expect(page.locator('body')).toBeVisible();
    await expect(page.locator('#root')).toBeVisible();
    
    // ページタイトルを確認
    await expect(page).toHaveTitle(/Vite \+ React \+ TS/);
  });
}); 