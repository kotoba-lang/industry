import { test, expect } from '@playwright/test';

/**
 * 本番環境テスト
 * Module Federationの完全動作確認
 */
test.describe('Production Tests', () => {
  test('本番環境でのModule Federation動作確認', async ({ page }) => {
    // コンソールログとエラーを収集
    const logs: string[] = [];
    const errors: string[] = [];
    
    page.on('console', msg => {
      logs.push(`[${msg.type()}] ${msg.text()}`);
    });
    
    page.on('pageerror', error => {
      errors.push(error.message);
    });
    
    // ホストアプリケーションにアクセス
    await page.goto('http://localhost:5173');
    
    // ページが読み込まれるまで待機
    await page.waitForLoadState('networkidle');
    
    // 少し待機してログを収集
    await page.waitForTimeout(3000);
    
    console.log('=== Production Test Logs ===');
    logs.forEach(log => console.log(log));
    
    console.log('=== Production Test Errors ===');
    errors.forEach(error => console.log(error));
    
    // 基本的な要素が存在することを確認
    await expect(page.locator('body')).toBeVisible();
    await expect(page.locator('#root')).toBeAttached();
    
    // Module Federationエラーがないことを確認
    const moduleFederationErrors = errors.filter(error => 
      error.includes('remoteEntry.js') || 
      error.includes('Failed to fetch') ||
      error.includes('Importing a module script failed')
    );
    
    console.log('Module Federation errors found:', moduleFederationErrors.length);
    
    // 本番環境ではModule Federationエラーが発生しないことを確認
    expect(moduleFederationErrors.length).toBe(0);
    
    // エディターコンポーネントが正常に読み込まれていることを確認
    const editorContent = await page.locator('body').textContent();
    expect(editorContent).toContain('Kotoba Editor');
    
    // グラフコンポーネントが正常に読み込まれていることを確認
    expect(editorContent).toContain('Kotoba Graph Viewer');
    
    console.log('✅ Production Module Federation test passed!');
  });

  test('リモートアプリケーションの独立動作確認', async ({ page }) => {
    // エディターリモートアプリケーションの確認
    await page.goto('http://localhost:5001');
    await page.waitForLoadState('networkidle');
    
    await expect(page.locator('body')).toBeVisible();
    await expect(page.locator('#root')).toBeAttached();
    
    const editorText = await page.locator('body').textContent();
    expect(editorText).toContain('Kotoba Editor');
    
    // グラフリモートアプリケーションの確認
    await page.goto('http://localhost:5002');
    await page.waitForLoadState('networkidle');
    
    await expect(page.locator('body')).toBeVisible();
    await expect(page.locator('#root')).toBeAttached();
    
    const graphText = await page.locator('body').textContent();
    expect(graphText).toContain('Kotoba Graph Viewer');
    
    console.log('✅ Remote applications working independently!');
  });

  test('Module Federationコンポーネントの機能確認', async ({ page }) => {
    await page.goto('http://localhost:5173');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);
    
    // エディターコンポーネントの機能確認
    const fileNameInput = page.locator('.file-name-input');
    await expect(fileNameInput).toBeVisible();
    await expect(fileNameInput).toHaveValue('untitled.txt');
    
    // ファイル名を変更
    await fileNameInput.fill('test-file.txt');
    await expect(fileNameInput).toHaveValue('test-file.txt');
    
    // テキストエリアの確認
    const textarea = page.locator('.editor-textarea');
    await expect(textarea).toBeVisible();
    
    // グラフコンポーネントの機能確認
    const graphTypeSelect = page.locator('.graph-type-select');
    await expect(graphTypeSelect).toBeVisible();
    
    // グラフタイプを変更
    await graphTypeSelect.selectOption('line');
    await expect(graphTypeSelect).toHaveValue('line');
    
    // Add Dataボタンの確認
    const addDataButton = page.locator('button:has-text("Add Data")');
    await expect(addDataButton).toBeVisible();
    await addDataButton.click();
    
    console.log('✅ Module Federation components are fully functional!');
  });
}); 