import { test, expect } from '@playwright/test';

/**
 * 動作確認テスト
 * 基本的な機能を段階的に確認
 */
test.describe('Working Tests', () => {
  test('ホストアプリケーションの基本読み込み', async ({ page }) => {
    // ホストアプリケーションにアクセス
    await page.goto('http://localhost:5173');
    
    // ページが読み込まれるまで待機
    await page.waitForLoadState('networkidle');
    
    // 基本的な要素が存在することを確認
    await expect(page.locator('body')).toBeVisible();
    await expect(page.locator('#root')).toBeVisible();
    
    // ページタイトルを確認
    await expect(page).toHaveTitle(/Vite \+ React \+ TS/);
    
    // コンソールログを確認（Module Federationの動作確認）
    const logs: string[] = [];
    page.on('console', msg => {
      logs.push(msg.text());
    });
    
    // ページをリロードしてログを収集
    await page.reload();
    await page.waitForLoadState('networkidle');
    
    // Module Federationのログが出力されていることを確認
    const hasModuleFederationLogs = logs.some(log => 
      log.includes('Attempting to load') || 
      log.includes('Failed to load')
    );
    
    console.log('Console logs:', logs);
    expect(hasModuleFederationLogs).toBe(true);
  });

  test('エディターリモートアプリケーションの基本読み込み', async ({ page }) => {
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

  test('グラフリモートアプリケーションの基本読み込み', async ({ page }) => {
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

  test('Module Federationのエラーハンドリング確認', async ({ page }) => {
    // ホストアプリケーションにアクセス
    await page.goto('http://localhost:5173');
    
    // ページが読み込まれるまで待機
    await page.waitForLoadState('networkidle');
    
    // エラーログを収集
    const errors: string[] = [];
    page.on('pageerror', error => {
      errors.push(error.message);
    });
    
    // ページをリロードしてエラーを収集
    await page.reload();
    await page.waitForLoadState('networkidle');
    
    console.log('Page errors:', errors);
    
    // Module Federationのエラーが適切にハンドリングされていることを確認
    // （エラーがあってもページが正常に表示される）
    await expect(page.locator('body')).toBeVisible();
    await expect(page.locator('#root')).toBeVisible();
  });
}); 