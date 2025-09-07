import { test, expect } from '@playwright/test';

/**
 * BitDevコンポーネント統合テスト
 * ホストアプリケーションとBitDevコンポーネントの動作を確認
 */
test.describe('BitDev Integration Tests', () => {
  test('ホストアプリケーションの基本動作', async ({ page }) => {
    // ホストアプリケーションにアクセス
    await page.goto('/');
    
    // ページが読み込まれるまで待機
    await page.waitForLoadState('networkidle');
    
    // 基本的な要素が存在することを確認
    await expect(page.locator('body')).toBeVisible();
    
    // ヘッダーが表示されることを確認
    await expect(page.locator('h1:has-text("Kotoba Platform")')).toBeVisible();
    
    // BitDev Components Integrationのテキストが表示されることを確認
    await expect(page.locator('span:has-text("BitDev Components Integration")')).toBeVisible();
  });

  test('Editorコンポーネントの表示', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    
    // Editorセクションが存在することを確認
    await expect(page.locator('h2:has-text("Editor")')).toBeVisible();
    
    // Editorの説明文が表示されることを確認（より具体的なセレクター）
    await expect(page.locator('p.text-sm.text-gray-500:has-text("ProseMirror-based rich text editor with AST generation")')).toBeVisible();
    
    // Editorコンポーネントが読み込まれることを確認
    await expect(page.locator('[data-testid="editor"]')).toBeVisible({ timeout: 10000 });
  });

  test('Graphコンポーネントの表示', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    
    // Graphセクションが存在することを確認
    await expect(page.locator('h2:has-text("Graph")')).toBeVisible();
    
    // Graphの説明文が表示されることを確認
    await expect(page.locator('p.text-sm.text-gray-500:has-text("Cytoscape-based AST visualization with interactive features")')).toBeVisible();
    
    // Graphコンポーネントが読み込まれることを確認
    await expect(page.locator('[data-testid="graph"]')).toBeVisible({ timeout: 10000 });
  });

  test('統合ステータスの表示', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    
    // 統合ステータスセクションが存在することを確認
    await expect(page.locator('h3:has-text("Integration Status")')).toBeVisible();
    
    // 各ステータス項目が表示されることを確認（より具体的なセレクター）
    await expect(page.locator('div.text-center:has-text("BitDev Components"):has-text("Successfully integrated")')).toBeVisible();
    await expect(page.locator('div.text-center:has-text("Dependencies"):has-text("ProseMirror & Cytoscape")')).toBeVisible();
    await expect(page.locator('div.text-center:has-text("Type Safety"):has-text("TypeScript integration")')).toBeVisible();
    
    // 成功マークが表示されることを確認
    await expect(page.locator('text=✓')).toHaveCount(3);
  });

  test('Components Loadedバッジの表示', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    
    // Components Loadedバッジが表示されることを確認
    await expect(page.locator('span:has-text("Components Loaded")')).toBeVisible({ timeout: 5000 });
    
    // バッジのスタイルが適用されていることを確認
    const badge = page.locator('span:has-text("Components Loaded")');
    await expect(badge).toHaveClass(/badge/);
  });

  test('レスポンシブデザインの確認', async ({ page }) => {
    // デスクトップビュー
    await page.setViewportSize({ width: 1280, height: 720 });
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    
    // デスクトップでは2列レイアウトになることを確認（より具体的なセレクター）
    const mainGrid = page.locator('.grid').first();
    await expect(mainGrid).toHaveClass(/lg:grid-cols-2/);
    
    // モバイルビュー
    await page.setViewportSize({ width: 375, height: 667 });
    await page.reload();
    await page.waitForLoadState('networkidle');
    
    // モバイルでは1列レイアウトになることを確認
    await expect(mainGrid).toHaveClass(/grid-cols-1/);
  });

  test('ASTデータの更新シミュレーション', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    
    // 初期状態ではASTデータが0であることを確認
    await expect(page.locator('text=AST Nodes: 0')).toBeVisible();
    await expect(page.locator('text=Edges: 0')).toBeVisible();
    
    // モックASTデータを注入
    await page.evaluate(() => {
      const mockASTData = {
        nodes: [
          { id: '1', label: 'Root', type: 'root', group: 'main', level: 0 },
          { id: '2', label: 'Child', type: 'child', group: 'main', level: 1 }
        ],
        edges: [
          { source: '1', target: '2', weight: 1 }
        ]
      };
      
      if ((window as any).updateASTData) {
        (window as any).updateASTData(mockASTData);
      }
    });
    
    // ASTデータが更新されることを確認
    await expect(page.locator('text=AST Nodes: 2')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('text=Edges: 1')).toBeVisible();
    
    // AST Data Availableバッジが表示されることを確認
    await expect(page.locator('text=AST Data Available (2 nodes)')).toBeVisible();
  });

  test('エラーハンドリング', async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    
    // コンソールエラーがないことを確認
    const consoleErrors: string[] = [];
    page.on('console', msg => {
      if (msg.type() === 'error') {
        consoleErrors.push(msg.text());
      }
    });
    
    // ページが正常に読み込まれることを確認
    await expect(page.locator('h1:has-text("Kotoba Platform")')).toBeVisible();
    
    // エラーが発生していないことを確認
    expect(consoleErrors.length).toBe(0);
  });

  test('パフォーマンステスト', async ({ page }) => {
    const startTime = Date.now();
    
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    
    const loadTime = Date.now() - startTime;
    
    // ページの読み込み時間が5秒以内であることを確認
    expect(loadTime).toBeLessThan(5000);
    
    // メモリ使用量を確認（概算）
    const memoryInfo = await page.evaluate(() => {
      return (performance as any).memory ? {
        usedJSHeapSize: (performance as any).memory.usedJSHeapSize,
        totalJSHeapSize: (performance as any).memory.totalJSHeapSize
      } : null;
    });
    
    if (memoryInfo) {
      // メモリ使用量が妥当な範囲内であることを確認
      expect(memoryInfo.usedJSHeapSize).toBeLessThan(100 * 1024 * 1024); // 100MB以下
    }
  });
}); 