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
    
    // 初期コンテンツが表示されることを確認
    const initialContent = page.locator('text=Kotoba Editor');
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
    
    // エディターがダークモードで表示されることを確認
    const editor = page.locator('.ProseMirror');
    await expect(editor).toHaveClass(/bg-gray-800/);
    
    // エディターのテキストがダークモードで表示されることを確認
    await expect(editor).toHaveClass(/text-gray-100/);
  });

  test('エディターのスタイリングが適用される', async ({ page }) => {
    await page.goto('/');
    
    const editor = page.locator('.ProseMirror');
    
    // エディターに適切なスタイルが適用されていることを確認
    await expect(editor).toHaveClass(/border/);
    await expect(editor).toHaveClass(/rounded-md/);
    await expect(editor).toHaveClass(/min-h-\[400px\]/);
  });

  test('エディターのフォーカス状態', async ({ page }) => {
    await page.goto('/');
    
    const editor = page.locator('.ProseMirror');
    
    // エディターをクリックしてフォーカスを設定
    await editor.click();
    
    // フォーカス状態のスタイルが適用されることを確認
    await expect(editor).toHaveClass(/ring-2/);
    await expect(editor).toHaveClass(/ring-blue-500/);
  });

  test('エディターコンテナのレイアウト', async ({ page }) => {
    await page.goto('/');
    
    // エディターコンテナが正しく配置されていることを確認
    const editorContainer = page.locator('.prosemirror-editor-container');
    await expect(editorContainer).toBeVisible();
    
    // エディターコンテナに適切なスタイルが適用されていることを確認
    await expect(editorContainer).toHaveClass(/min-h-\[600px\]/);
    await expect(editorContainer).toHaveClass(/p-6/);
  });
}); 