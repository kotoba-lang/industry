import { test, expect } from '@playwright/test';

/**
 * デバッグテスト
 * ホストアプリケーションの詳細な状況を確認
 */
test.describe('Debug Tests', () => {
  test('ホストアプリケーションの詳細デバッグ', async ({ page }) => {
    await page.goto('http://localhost:5174/');
    
    console.log('\n=== デバッグ情報 ===');
    console.log('URL:', page.url());
    console.log('Title:', await page.title());
    
    // コンソールログを収集
    const logs: string[] = [];
    page.on('console', msg => {
      logs.push(`[${msg.type()}] ${msg.text()}`);
    });
    
    // エラーを収集
    const errors: string[] = [];
    page.on('pageerror', error => {
      errors.push(error.message);
    });
    
    // ネットワークエラーを収集
    const networkErrors: string[] = [];
    page.on('requestfailed', request => {
      networkErrors.push(`${request.method()} ${request.url()}`);
    });
    
    // ページが完全に読み込まれるまで待機
    await page.waitForLoadState('networkidle');
    
    console.log('\n=== コンソールログ ===');
    logs.forEach(log => console.log(log));
    
    console.log('\n=== エラー ===');
    errors.forEach(error => console.log(error));
    
    console.log('\n=== ネットワークエラー ===');
    networkErrors.forEach(error => console.log(error));
    
    // ページのテキスト内容を取得
    const bodyText = await page.locator('body').textContent();
    const rootText = await page.locator('#root').textContent();
    
    console.log('\n=== ページ要素 ===');
    console.log('Body text:', bodyText?.substring(0, 100));
    console.log('Root text:', rootText?.substring(0, 100));
    
    console.log('\n=== デバッグ完了 ===');
  });

  test('エディターのダークモード色デバッグ', async ({ page }) => {
    await page.goto('http://localhost:5174/');
    
    // エディターが読み込まれるまで待機
    await page.waitForSelector('[data-testid="editor"]');
    await page.waitForSelector('.ProseMirror');
    
    console.log('\n=== エディター色デバッグ ===');
    
    // ライトモードでの色を確認
    const lightEditor = page.locator('.ProseMirror').first();
    const lightColor = await lightEditor.evaluate(el => 
      window.getComputedStyle(el).color
    );
    const lightBg = await lightEditor.evaluate(el => 
      window.getComputedStyle(el).backgroundColor
    );
    
    console.log('ライトモード - テキスト色:', lightColor);
    console.log('ライトモード - 背景色:', lightBg);
    
    // ダークモードに切り替え
    const themeToggle = page.locator('button[aria-label*="Switch to"]');
    await themeToggle.click();
    
    // ダークモードが適用されるまで待機
    await page.waitForTimeout(1000);
    
    // ダークモードでの色を確認
    const darkEditor = page.locator('.ProseMirror').first();
    const darkColor = await darkEditor.evaluate(el => 
      window.getComputedStyle(el).color
    );
    const darkBg = await darkEditor.evaluate(el => 
      window.getComputedStyle(el).backgroundColor
    );
    
    console.log('ダークモード - テキスト色:', darkColor);
    console.log('ダークモード - 背景色:', darkBg);
    
    // クラスリストも確認
    const classList = await darkEditor.evaluate(el => 
      Array.from(el.classList)
    );
    console.log('ダークモード - クラスリスト:', classList);
    
    console.log('\n=== 色デバッグ完了 ===');
  });
}); 