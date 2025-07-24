import { test, expect } from '@playwright/test';

/**
 * UI状態確認テスト
 * 基本的なページ読み込みとスタイル適用を確認
 */
test.describe('UI Check Tests', () => {
  test('基本的なページ読み込み', async ({ page }) => {
    // ホストアプリケーションにアクセス
    await page.goto('/');
    
    // ページが読み込まれるまで待機
    await page.waitForLoadState('networkidle');
    
    // 基本的な要素が存在することを確認
    await expect(page.locator('body')).toBeVisible();
    
    // ヘッダーが表示されることを確認
    await expect(page.locator('h1:has-text("Kotoba Platform")')).toBeVisible();
    
    // スクリーンショットを撮影
    await page.screenshot({ 
      path: 'test-results/ui-check-basic.png',
      fullPage: true 
    });
    
    console.log('✅ 基本的なページ読み込みテスト完了');
  });

  test('Tailwind CSSの適用確認', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    
    // Tailwind CSSクラスが適用されているか確認
    const header = page.locator('h1:has-text("Kotoba Platform")');
    await expect(header).toHaveClass(/text-2xl/);
    await expect(header).toHaveClass(/font-bold/);
    await expect(header).toHaveClass(/text-gray-900/);
    
    // 背景色が適用されているか確認
    const body = page.locator('body');
    const backgroundColor = await body.evaluate(el => 
      window.getComputedStyle(el).backgroundColor
    );
    
    console.log('背景色:', backgroundColor);
    
    // スクリーンショットを撮影
    await page.screenshot({ 
      path: 'test-results/ui-check-tailwind.png',
      fullPage: false 
    });
    
    console.log('✅ Tailwind CSS適用確認テスト完了');
  });
}); 