import { test, expect } from '@playwright/test';

/**
 * エディターコンポーネントのUIテスト
 * ProseMirrorエディターが正しく表示され、機能することを確認
 */
test.describe('Editor UI Tests', () => {
  test('エディターコンポーネントが正しく表示される', async ({ page }) => {
    await page.goto('/');
    
    // エディターパネルが存在することを確認
    const editorPanel = page.locator('.panel').filter({ hasText: 'Editor' });
    await expect(editorPanel).toBeVisible();
    
    // エディターのタイトルが表示されることを確認
    const editorTitle = page.locator('h2').filter({ hasText: 'Editor' });
    await expect(editorTitle).toBeVisible();
  });

  test('ProseMirrorエディターが正しく初期化される', async ({ page }) => {
    await page.goto('/');
    
    // ProseMirrorエディターが存在することを確認
    const prosemirrorEditor = page.locator('.ProseMirror');
    await expect(prosemirrorEditor).toBeVisible();
    
    // 初期コンテンツが表示されることを確認（より具体的なセレクターを使用）
    const initialContent = page.locator('.ProseMirror p').filter({ hasText: 'Kotoba Editor' });
    await expect(initialContent).toBeVisible();
  });

  test('エディターでテキストを編集できる', async ({ page }) => {
    await page.goto('/');
    
    // エディターをクリックしてフォーカスを設定
    const editor = page.locator('.ProseMirror');
    await editor.click();
    
    // 新しいテキストを入力
    await page.keyboard.type('Hello, this is a test!');
    
    // 入力したテキストが表示されることを確認
    const typedText = page.locator('text=Hello, this is a test!');
    await expect(typedText).toBeVisible();
  });

  test('エディターのダークモード対応', async ({ page }) => {
    await page.goto('/');
    
    // ダークモードに切り替え
    const themeToggle = page.locator('button[aria-label*="Switch to"]');
    await themeToggle.click();
    
    // エディターがダークモードで表示されることを確認（実際のスタイルをチェック）
    const editor = page.locator('.ProseMirror');
    
    // エディターの背景色がダークモードになっていることを確認
    const backgroundColor = await editor.evaluate(el => 
      window.getComputedStyle(el).backgroundColor
    );
    expect(backgroundColor).toBe('rgb(31, 41, 55)'); // bg-gray-800
    
    // エディターのテキスト色がダークモードになっていることを確認
    const color = await editor.evaluate(el => 
      window.getComputedStyle(el).color
    );
    expect(color).toBe('rgb(243, 244, 246)'); // text-gray-100
  });

  test('エディターのスタイリングが適用される', async ({ page }) => {
    await page.goto('/');
    
    const editor = page.locator('.ProseMirror');
    
    // エディターに適切なスタイルが適用されていることを確認（実際のCSSプロパティをチェック）
    const borderStyle = await editor.evaluate(el => 
      window.getComputedStyle(el).border
    );
    expect(borderStyle).toContain('1px solid');
    
    const borderRadius = await editor.evaluate(el => 
      window.getComputedStyle(el).borderRadius
    );
    expect(borderRadius).toBe('0.375rem'); // rounded-md
    
    const minHeight = await editor.evaluate(el => 
      window.getComputedStyle(el).minHeight
    );
    expect(minHeight).toBe('400px'); // min-h-[400px]
  });

  test('エディターのフォーカス状態', async ({ page }) => {
    await page.goto('/');
    
    const editor = page.locator('.ProseMirror');
    
    // エディターをクリックしてフォーカスを設定
    await editor.click();
    
    // フォーカス状態のスタイルが適用されることを確認（実際のCSSプロパティをチェック）
    const boxShadow = await editor.evaluate(el => 
      window.getComputedStyle(el).boxShadow
    );
    expect(boxShadow).toContain('rgba(59, 130, 246, 0.5)'); // ring-blue-500
  });

  test('エディターコンテナのレイアウト', async ({ page }) => {
    await page.goto('/');
    
    // エディターコンテナが正しく配置されていることを確認
    const editorContainer = page.locator('.prosemirror-editor-container');
    await expect(editorContainer).toBeVisible();
    
    // エディターコンテナに適切なスタイルが適用されていることを確認（実際のCSSプロパティをチェック）
    const minHeight = await editorContainer.evaluate(el => 
      window.getComputedStyle(el).minHeight
    );
    expect(minHeight).toBe('600px'); // min-h-[600px]
    
    const padding = await editorContainer.evaluate(el => 
      window.getComputedStyle(el).padding
    );
    expect(padding).toBe('1.5rem'); // p-6
  });
}); 