import { test, expect } from '@playwright/test';

test.describe('Editor UI Tests', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('http://localhost:5173');
    // エディターが読み込まれるまで待機
    await page.waitForSelector('[data-testid="editor"]');
    // 初期コンテンツが表示されるまで待機
    await page.waitForSelector('.ProseMirror p');
  });

  test('エディターが正しく表示される', async ({ page }) => {
    const editor = page.locator('[data-testid="editor"]');
    await expect(editor).toBeVisible();
    
    // より具体的なセレクターを使用してProseMirrorエディターを特定
    const proseMirror = page.locator('.ProseMirror').filter({ hasText: 'Kotoba Editor' }).first();
    await expect(proseMirror).toBeVisible();
  });

  test('エディターのダークモード対応', async ({ page }) => {
    // ダークモードに切り替え - より具体的なセレクターを使用
    const themeToggle = page.locator('button').filter({ hasText: '🌙' }).or(page.locator('button').filter({ hasText: '☀️' }));
    await themeToggle.click();
    
    // ダークモードが適用されるまで待機
    await page.waitForTimeout(200);
    
    // より具体的なセレクターを使用してProseMirrorエディターを特定
    const editor = page.locator('.ProseMirror').filter({ hasText: 'Kotoba Editor' }).first();
    
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
    // より具体的なセレクターを使用してProseMirrorエディターを特定
    const editor = page.locator('.ProseMirror').filter({ hasText: 'Kotoba Editor' }).first();
    
    // エディターの背景色を確認
    const backgroundColor = await editor.evaluate(el => 
      window.getComputedStyle(el).backgroundColor
    );
    expect(backgroundColor).toBe('rgb(255, 255, 255)'); // bg-white
    
    // エディターのボーダーを確認
    const borderStyle = await editor.evaluate(el => 
      window.getComputedStyle(el).border
    );
    expect(borderStyle).toContain('1px solid');
    
    const borderRadius = await editor.evaluate(el => 
      window.getComputedStyle(el).borderRadius
    );
    expect(borderRadius).toBe('0.5rem'); // rounded-lg
    
    // エディターの最小高さを確認
    const minHeight = await editor.evaluate(el => 
      window.getComputedStyle(el).minHeight
    );
    expect(minHeight).toBe('600px'); // min-h-[600px]
  });

  test('エディターのフォーカス状態', async ({ page }) => {
    // より具体的なセレクターを使用してProseMirrorエディターを特定
    const editor = page.locator('.ProseMirror').filter({ hasText: 'Kotoba Editor' }).first();
    
    // エディターをクリックしてフォーカス
    await editor.click();
    
    // フォーカス時のボックスシャドウを確認
    const boxShadow = await editor.evaluate(el => 
      window.getComputedStyle(el).boxShadow
    );
    expect(boxShadow).toContain('rgba(59, 130, 246, 0.5)'); // ring-blue-500
  });

  test('エディターコンテナのレイアウト', async ({ page }) => {
    const editorContainer = page.locator('.prosemirror-editor-container');
    
    // コンテナの位置を確認
    const position = await editorContainer.evaluate(el => 
      window.getComputedStyle(el).position
    );
    expect(position).toBe('relative');
    
    // コンテナの幅を確認
    const width = await editorContainer.evaluate(el => 
      window.getComputedStyle(el).width
    );
    expect(width).toBe('100%');
  });

  test('Typographyプラグインの適用', async ({ page }) => {
    // より具体的なセレクターを使用してProseMirrorエディターを特定
    const editor = page.locator('.ProseMirror').filter({ hasText: 'Kotoba Editor' }).first();
    
    // proseクラスが適用されていることを確認
    const classList = await editor.evaluate(el => 
      Array.from(el.classList)
    );
    expect(classList).toContain('prose');
    expect(classList).toContain('prose-sm');
    expect(classList).toContain('sm:prose');
    expect(classList).toContain('lg:prose-lg');
  });

  test('エディターのトランジション効果', async ({ page }) => {
    // より具体的なセレクターを使用してProseMirrorエディターを特定
    const editor = page.locator('.ProseMirror').filter({ hasText: 'Kotoba Editor' }).first();
    
    // トランジションが適用されていることを確認
    const transition = await editor.evaluate(el => 
      window.getComputedStyle(el).transition
    );
    expect(transition).toContain('color');
    expect(transition).toContain('0.2s');
  });
}); 