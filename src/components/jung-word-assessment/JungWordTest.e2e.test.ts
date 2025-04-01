import { test, expect } from '@playwright/test';

test.describe('Jung Word Association Test', () => {
  test('should allow completing the test and show results', async ({ page }) => {
    // Navigate to the test page
    await page.goto('/jung-word-assessment');
    
    // Verify the page title
    await expect(page.locator('h1')).toContainText("Jung's Word Association Test");
    
    // Start the test
    await page.click('text=Begin Test');
    
    // Verify the test has started and shows the first word
    await expect(page.locator('text=Word 1 of')).toBeVisible();
    
    // Complete 5 word associations 
    for (let i = 0; i < 5; i++) {
      // Check if current word is visible
      const wordNumber = i + 1;
      await expect(page.locator(`text=Word ${wordNumber} of`)).toBeVisible();
      
      // Enter a response
      await page.fill('input[placeholder="Type your response..."]', `test response ${i}`);
      
      // Click next
      await page.click('text=Next');
    }
    
    // After completing all words, we should see the analysis
    await expect(page.locator('text=Test Complete')).toBeVisible();
    await expect(page.locator('text=Your Responses')).toBeVisible();
    
    // Verify that our responses are in the table
    const responseTable = page.locator('table');
    await expect(responseTable).toBeVisible();
    
    // Verify Take Test Again button is visible
    await expect(page.locator('text=Take Test Again')).toBeVisible();
    
    // Click Take Test Again and verify we're back at the start
    await page.click('text=Take Test Again');
    await expect(page.locator('text=Begin Test')).toBeVisible();
  });
  
  test('should show proper analysis after test completion', async ({ page }) => {
    // Navigate to the test page
    await page.goto('/jung-word-assessment');
    
    // Start the test
    await page.click('text=Begin Test');
    
    // Complete 10 word associations with varying response times
    // Playwright executes actions quickly, so we're naturally testing fast responses
    for (let i = 0; i < 10; i++) {
      await page.fill('input[placeholder="Type your response..."]', `test response ${i}`);
      await page.click('text=Next');
    }
    
    // After test completion, we should see results followed by analysis
    await expect(page.locator('text=Analysis of Your Word Association Test')).toBeVisible();
    
    // Check for statistical overview section
    await expect(page.locator('text=Statistical Overview')).toBeVisible();
    
    // Check for category exploration section
    await expect(page.locator('text=Explore by Category')).toBeVisible();
    
    // Try clicking on a category button
    await page.click('button:has-text("Family/Relationship")');
    
    // The category detail should be visible
    await expect(page.locator('text=Family/Relationship')).toBeVisible();
    
    // Click Take Test Again button
    await page.locator('button:has-text("Take Test Again")').click();
    
    // Verify we're back at the beginning
    await expect(page.locator('text=Begin Test')).toBeVisible();
  });
}); 