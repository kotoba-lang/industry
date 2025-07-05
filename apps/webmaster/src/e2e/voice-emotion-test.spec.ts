import { expect, test } from "@playwright/test";

test.describe("音声感情分析テスト", () => {
    test("ページが正しく表示されること", async ({ page }) => {
        // ページにアクセス
        await page.goto("/voice-emotion-test");

        // タイトルを確認
        await expect(page).toHaveTitle(/Voice Emotion Analysis Test/);

        // 見出しを確認
        const heading = page.locator("h1").filter({
            hasText: "音声感情分析テスト",
        });
        await expect(heading).toBeVisible();

        // 説明テキストを確認
        const description = page.getByText(
            "Hume AI の音声感情分析機能をテストします。",
        );
        await expect(description).toBeVisible();

        // 録音ボタンを確認
        const recordButton = page.getByRole("button", { name: "録音開始" });
        await expect(recordButton).toBeVisible();
    });

    test("録音機能を持つカードが表示されること", async ({ page }) => {
        // ページにアクセス
        await page.goto("/voice-emotion-test");

        // カードの見出しを確認
        const cardTitle = page.locator("h3").filter({
            hasText: "音声感情分析テスト",
        });
        await expect(cardTitle).toBeVisible();

        // 録音ボタンを確認
        const recordButton = page.getByRole("button", { name: "録音開始" });
        await expect(recordButton).toBeVisible();
    });

    // 注: 実際のマイク録音と音声分析は自動テストでは難しいため、
    // UI要素の確認のみを行っています。実際のAPIリクエストは
    // モックするか、統合テストで別途検証する必要があります。
});
