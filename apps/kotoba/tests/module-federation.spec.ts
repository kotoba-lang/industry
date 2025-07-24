import { test, expect } from '@playwright/test';

/**
 * Module Federation構成のテスト
 * ホストアプリケーションがリモートアプリケーションを正しく読み込むことを確認
 */
test.describe('Module Federation Tests', () => {
  test('ホストアプリケーションが正常に読み込まれる', async ({ page }) => {
    // ホストアプリケーションにアクセス
    await page.goto('/');
    
    // ページタイトルを確認
    await expect(page).toHaveTitle(/Kotoba Editor/);
    
    // ページが正常に読み込まれたことを確認
    await expect(page.locator('body')).toBeVisible();
  });

  test('エディターコンポーネントが表示される', async ({ page }) => {
    await page.goto('/');
    
    // エディターセクションが存在することを確認
    const editorSection = page.locator('text=Editor');
    await expect(editorSection).toBeVisible();
    
    // エディターのローディング状態を確認
    const editorLoading = page.locator('text=Loading Editor...');
    await expect(editorLoading).toBeVisible();
    
    // エディターコンポーネントが読み込まれるまで待機
    await page.waitForTimeout(3000);
    
    // エディターコンポーネントが表示されることを確認
    const editorContent = page.locator('h1:has-text("Vite + React")');
    await expect(editorContent).toBeVisible();
  });

  test('グラフコンポーネントが表示される', async ({ page }) => {
    await page.goto('/');
    
    // グラフセクションが存在することを確認
    const graphSection = page.locator('text=Graph');
    await expect(graphSection).toBeVisible();
    
    // グラフのローディング状態を確認
    const graphLoading = page.locator('text=Loading Graph...');
    await expect(graphLoading).toBeVisible();
    
    // グラフコンポーネントが読み込まれるまで待機
    await page.waitForTimeout(3000);
    
    // グラフコンポーネントが表示されることを確認
    const graphContent = page.locator('h1:has-text("Vite + React")');
    await expect(graphContent).toBeVisible();
  });

  test('エディターコンポーネントのインタラクション', async ({ page }) => {
    await page.goto('/');
    
    // エディターコンポーネントが読み込まれるまで待機
    await page.waitForTimeout(3000);
    
    // カウントボタンを見つけてクリック
    const countButton = page.locator('button:has-text("count is")').first();
    await expect(countButton).toBeVisible();
    
    // 初期カウント値を確認
    await expect(countButton).toContainText('count is 0');
    
    // ボタンをクリック
    await countButton.click();
    
    // カウント値が増加することを確認
    await expect(countButton).toContainText('count is 1');
  });

  test('グラフコンポーネントのインタラクション', async ({ page }) => {
    await page.goto('/');
    
    // グラフコンポーネントが読み込まれるまで待機
    await page.waitForTimeout(3000);
    
    // カウントボタンを見つけてクリック
    const countButton = page.locator('button:has-text("count is")').nth(1);
    await expect(countButton).toBeVisible();
    
    // 初期カウント値を確認
    await expect(countButton).toContainText('count is 0');
    
    // ボタンをクリック
    await countButton.click();
    
    // カウント値が増加することを確認
    await expect(countButton).toContainText('count is 1');
  });

  test('リモートアプリケーションの個別アクセス', async ({ page }) => {
    // エディターリモートアプリケーションに直接アクセス
    await page.goto('http://localhost:5001');
    
    // エディターアプリケーションが正常に読み込まれることを確認
    await expect(page.locator('h1:has-text("Vite + React")')).toBeVisible();
    
    // ページタイトルを確認
    await expect(page).toHaveTitle(/Vite \+ React/);
  });

  test('グラフリモートアプリケーションの個別アクセス', async ({ page }) => {
    // グラフリモートアプリケーションに直接アクセス
    await page.goto('http://localhost:5002');
    
    // グラフアプリケーションが正常に読み込まれることを確認
    await expect(page.locator('h1:has-text("Vite + React")')).toBeVisible();
    
    // ページタイトルを確認
    await expect(page).toHaveTitle(/Vite \+ React/);
  });

  test('Module Federationのエラーハンドリング', async ({ page }) => {
    await page.goto('/');
    
    // コンソールエラーを監視
    const consoleErrors: string[] = [];
    page.on('console', msg => {
      if (msg.type() === 'error') {
        consoleErrors.push(msg.text());
      }
    });
    
    // ページが読み込まれるまで待機
    await page.waitForTimeout(5000);
    
    // Module Federation関連のエラーがないことを確認
    const moduleFederationErrors = consoleErrors.filter(error => 
      error.includes('remoteEntry.js') || 
      error.includes('Module Federation') ||
      error.includes('Importing a module script failed')
    );
    
    expect(moduleFederationErrors.length).toBe(0);
  });
}); 