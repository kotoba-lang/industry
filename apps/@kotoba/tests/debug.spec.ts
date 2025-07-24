import { test, expect } from '@playwright/test';

/**
 * デバッグテスト
 * ホストアプリケーションの詳細な状況を確認
 */
test.describe('Debug Tests', () => {
  test('ホストアプリケーションの詳細デバッグ', async ({ page }) => {
    // コンソールログとエラーを収集
    const logs: string[] = [];
    const errors: string[] = [];
    const networkErrors: string[] = [];
    
    page.on('console', msg => {
      logs.push(`[${msg.type()}] ${msg.text()}`);
    });
    
    page.on('pageerror', error => {
      errors.push(error.message);
    });
    
    page.on('requestfailed', request => {
      networkErrors.push(`${request.method()} ${request.url()} - ${request.failure()?.errorText || 'Unknown error'}`);
    });
    
    // ホストアプリケーションにアクセス
    await page.goto('http://localhost:5173');
    
    // ページが読み込まれるまで待機
    await page.waitForLoadState('networkidle');
    
    // 少し待機してログを収集
    await page.waitForTimeout(3000);
    
    // デバッグ情報を出力
    console.log('\n=== デバッグ情報 ===');
    console.log('URL:', page.url());
    console.log('Title:', await page.title());
    
    console.log('\n=== コンソールログ ===');
    logs.forEach(log => console.log(log));
    
    console.log('\n=== エラー ===');
    errors.forEach(error => console.log(error));
    
    console.log('\n=== ネットワークエラー ===');
    networkErrors.forEach(error => console.log(error));
    
    console.log('\n=== ページ要素 ===');
    const bodyText = await page.locator('body').textContent();
    console.log('Body text:', bodyText?.substring(0, 200) + '...');
    
    const rootElement = page.locator('#root');
    const rootText = await rootElement.textContent();
    console.log('Root text:', rootText?.substring(0, 200) + '...');
    
    // 基本的な要素が存在することを確認
    await expect(page.locator('body')).toBeVisible();
    await expect(rootElement).toBeAttached();
    
    console.log('\n=== デバッグ完了 ===');
  });
}); 