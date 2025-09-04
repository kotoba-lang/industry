import { test, expect } from '@playwright/test';

/**
 * スクリーンショット撮影テスト
 * ホストアプリケーションのスクリーンショットを撮影
 */
test.describe('Screenshot Tests', () => {
  test('ホストアプリケーションのスクリーンショット', async ({ page }) => {
    // ホストアプリケーションにアクセス
    await page.goto('/');
    
    // ページが読み込まれるまで待機
    await page.waitForLoadState('networkidle');
    
    // Components Loadedバッジが表示されるまで待機
    await page.waitForSelector('span:has-text("Components Loaded")', { timeout: 10000 });
    
    // フルページのスクリーンショットを撮影
    await page.screenshot({ 
      path: 'test-results/host-app-fullpage.png',
      fullPage: true 
    });
    
    // ビューポートサイズのスクリーンショットを撮影
    await page.screenshot({ 
      path: 'test-results/host-app-viewport.png',
      fullPage: false 
    });
    
    console.log('✅ スクリーンショットが撮影されました:');
    console.log('   - test-results/host-app-fullpage.png (フルページ)');
    console.log('   - test-results/host-app-viewport.png (ビューポート)');
  });

  test('デスクトップビューのスクリーンショット', async ({ page }) => {
    // デスクトップサイズに設定
    await page.setViewportSize({ width: 1280, height: 720 });
    
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await page.waitForSelector('span:has-text("Components Loaded")', { timeout: 10000 });
    
    // デスクトップビューのスクリーンショット
    await page.screenshot({ 
      path: 'test-results/host-app-desktop.png',
      fullPage: false 
    });
    
    console.log('✅ デスクトップビューのスクリーンショットが撮影されました: test-results/host-app-desktop.png');
  });

  test('モバイルビューのスクリーンショット', async ({ page }) => {
    // モバイルサイズに設定
    await page.setViewportSize({ width: 375, height: 667 });
    
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await page.waitForSelector('span:has-text("Components Loaded")', { timeout: 10000 });
    
    // モバイルビューのスクリーンショット
    await page.screenshot({ 
      path: 'test-results/host-app-mobile.png',
      fullPage: false 
    });
    
    console.log('✅ モバイルビューのスクリーンショットが撮影されました: test-results/host-app-mobile.png');
  });

  test('Editorコンポーネントのスクリーンショット', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await page.waitForSelector('[data-testid="editor"]', { timeout: 10000 });
    
    // Editorコンポーネントのスクリーンショット
    const editorElement = page.locator('[data-testid="editor"]');
    await editorElement.screenshot({ 
      path: 'test-results/editor-component.png' 
    });
    
    console.log('✅ Editorコンポーネントのスクリーンショットが撮影されました: test-results/editor-component.png');
  });

  test('Graphコンポーネントのスクリーンショット', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await page.waitForSelector('[data-testid="graph"]', { timeout: 10000 });
    
    // Graphコンポーネントのスクリーンショット
    const graphElement = page.locator('[data-testid="graph"]');
    await graphElement.screenshot({ 
      path: 'test-results/graph-component.png' 
    });
    
    console.log('✅ Graphコンポーネントのスクリーンショットが撮影されました: test-results/graph-component.png');
  });
}); 