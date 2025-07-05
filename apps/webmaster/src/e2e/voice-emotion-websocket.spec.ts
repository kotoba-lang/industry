import { expect, test } from "@playwright/test";

test.describe("リアルタイム音声感情分析", () => {
    test("ページが正しく表示されること", async ({ page }) => {
        // ページにアクセス
        await page.goto("/voice-emotion-websocket");

        // タイトルを確認
        await expect(page).toHaveTitle(/リアルタイム音声感情分析/);

        // 見出しを確認
        const heading = page.locator("h1").filter({
            hasText: "リアルタイム音声感情分析",
        });
        await expect(heading).toBeVisible();

        // 説明テキストを確認
        const description = page.getByText(
            "Hume AI の WebSocket APIを使用して、音声からリアルタイムで感情を分析します。",
        );
        await expect(description).toBeVisible();

        // 録音ボタンを確認
        const recordButton = page.getByRole("button", { name: "録音開始" });
        await expect(recordButton).toBeVisible();
    });

    test("接続状態のUIが表示されること", async ({ page }) => {
        // ページにアクセス
        await page.goto("/voice-emotion-websocket");

        // 接続状態のテキストを確認
        const connectionState = page.getByText("切断");
        await expect(connectionState).toBeVisible();

        // 録音ボタンが有効であること
        const recordButton = page.getByRole("button", { name: "録音開始" });
        await expect(recordButton).toBeEnabled();

        // 通知メッセージが表示されること
        const notification = page.getByText(
            "WebSocket接続は録音開始時に自動的に確立されます。",
        );
        await expect(notification).toBeVisible();
    });

    // 注: 実際のマイク録音とWebSocket通信はE2Eテストでは難しいため、
    // UI要素の確認のみを行っています。実際のAPIリクエストは
    // モックするか、統合テストで別途検証する必要があります。
});
