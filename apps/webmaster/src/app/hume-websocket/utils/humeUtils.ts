/**
 * Hume AIのAPIとWebSocketに関するユーティリティ関数
 */

/**
 * Hume APIキーを取得する
 * NEXT_PUBLIC_HUME_API_KEYが設定されていない場合はエラーをスローする
 */
export const getHumeApiKey = (): string => {
    const apiKey = process.env.NEXT_PUBLIC_HUME_API_KEY;
    if (!apiKey) {
        throw new Error(
            "NEXT_PUBLIC_HUME_API_KEY が設定されていません。.env.local ファイルを確認してください。",
        );
    }
    return apiKey;
};

/**
 * Hume WebSocket接続URLを取得する
 */
export const getHumeWebSocketUrl = (): string => {
    const apiKey = getHumeApiKey();
    return `wss://api.hume.ai/v0/expression-measurement/ws?apiKey=${apiKey}`;
};

/**
 * 代替のREST APIエンドポイントURL（WebSocketが使えない場合の代替）
 */
export const getHumeRestApiUrl = (
    model: "language" | "face" | "prosody",
): string => {
    const apiKey = getHumeApiKey();
    const baseUrl =
        "https://api.hume.ai/v0/expression-measurement/measurements";

    switch (model) {
        case "language":
            return `${baseUrl}/language?apiKey=${apiKey}`;
        case "face":
            return `${baseUrl}/face?apiKey=${apiKey}`;
        case "prosody":
            return `${baseUrl}/prosody?apiKey=${apiKey}`;
    }
};

/**
 * エラーメッセージを人間が読みやすい形式に変換する
 */
export const formatHumeError = (error: any): string => {
    if (typeof error === "string") {
        return error;
    }

    if (error instanceof Error) {
        return error.message;
    }

    if (error && typeof error === "object") {
        if (error.message) {
            return error.message;
        }
        if (error.error && error.error.message) {
            return error.error.message;
        }
    }

    return "不明なエラーが発生しました";
};
