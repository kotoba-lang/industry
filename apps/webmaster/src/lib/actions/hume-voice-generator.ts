/**
 * Hume AIのAPIを利用して音声を生成し、ローカルに保存するサービス
 */
"use server"; // Enable Server Actions

import fs from "fs";
import path from "path";
import { v4 as uuidv4 } from "uuid";
import { z } from "zod";

// Generate Voice Input Schema
const GenerateVoiceInputSchema = z.object({
    text: z.string().min(1).max(1000),
    voiceName: z.string().default("David Hume"),
    apiKey: z.string().min(1),
    fileName: z.string().optional(),
});

export type GenerateVoiceInput = z.infer<typeof GenerateVoiceInputSchema>;

// Response type
export interface GenerateVoiceResponse {
    success: boolean;
    audioUrl?: string;
    filePath?: string;
    error?: string;
    fileExists?: boolean;
}

/**
 * 音声を生成してローカルに保存するサーバーアクション
 */
export async function generateAndSaveVoice(
    input: GenerateVoiceInput,
): Promise<GenerateVoiceResponse> {
    try {
        // バリデーション
        const validatedInput = GenerateVoiceInputSchema.parse(input);

        // ファイル名を作成（指定されていなければテキストに基づいて生成）
        const fileName = validatedInput.fileName ||
            `${
                validatedInput.text.substring(0, 20).replace(
                    /[^a-z0-9]/gi,
                    "_",
                )
            }_${uuidv4().substring(0, 8)}.mp3`;

        // 保存先ディレクトリを確認し、存在しなければ作成
        const audioDir = path.join(process.cwd(), "public", "audio");
        if (!fs.existsSync(audioDir)) {
            fs.mkdirSync(audioDir, { recursive: true });
        }

        // 既存のファイルをチェック - 同じテキストコンテンツを持つファイルがあるか確認
        const existingFiles = fs.readdirSync(audioDir).filter((file) =>
            file.endsWith(".mp3")
        );

        // 同じテキストで始まるファイル名を探す（ファイル名は「テキスト_UUID.mp3」の形式）
        const textPrefix = validatedInput.text.substring(0, 20).replace(
            /[^a-z0-9]/gi,
            "_",
        );
        const matchingFile = existingFiles.find((file) =>
            file.startsWith(textPrefix + "_") ||
            file === validatedInput.fileName
        );

        // 同じ内容のファイルが既に存在する場合は、それを返す
        if (matchingFile) {
            const audioUrl = `/audio/${matchingFile}`;
            const filePath = path.join(audioDir, matchingFile);

            return {
                success: true,
                audioUrl,
                filePath,
                // 新規生成ではなく既存ファイルを使用したことを示す
                fileExists: true,
            };
        }

        // Hume AIのTTSエンドポイントを呼び出す
        const apiUrl = "https://api.hume.ai/v0/tts";
        const headers = {
            "X-Hume-Api-Key": validatedInput.apiKey,
            "Content-Type": "application/json",
        };

        const requestData = {
            utterances: [
                {
                    text: validatedInput.text,
                    description: validatedInput.voiceName,
                },
            ],
            format: {
                type: "mp3",
            },
            num_generations: 1,
        };

        const fetchResponse = await fetch(apiUrl, {
            method: "POST",
            headers: headers,
            body: JSON.stringify(requestData),
        });

        if (!fetchResponse.ok) {
            const errorText = await fetchResponse.text();
            throw new Error(
                `HTTP error! status: ${fetchResponse.status}, message: ${errorText}`,
            );
        }

        const response = await fetchResponse.json();

        // APIはbase64形式の音声データを含む生成の配列を返す
        if (
            response && response.generations && response.generations.length > 0
        ) {
            const generation = response.generations[0];

            // 応答にaudioプロパティ（base64エンコード）があるか確認
            if (generation.audio) {
                // base64をバイナリに変換
                const binaryString = atob(generation.audio);
                const len = binaryString.length;
                const bytes = new Uint8Array(len);

                for (let i = 0; i < len; i++) {
                    bytes[i] = binaryString.charCodeAt(i);
                }

                // 音声ファイルを保存
                const filePath = path.join(audioDir, fileName);
                fs.writeFileSync(filePath, Buffer.from(bytes));

                // 公開URLを作成
                const audioUrl = `/audio/${fileName}`;

                return {
                    success: true,
                    audioUrl,
                    filePath,
                };
            } else {
                throw new Error("Audio data not found in response");
            }
        } else {
            throw new Error("No generations found in response");
        }
    } catch (error) {
        console.error("Error generating voice:", error);
        return {
            success: false,
            error: error instanceof Error
                ? error.message
                : "Unknown error occurred",
        };
    }
}

/**
 * 保存された音声ファイルのリストを取得するサーバーアクション
 */
export async function getVoiceFiles(): Promise<
    { success: boolean; files?: string[]; error?: string }
> {
    try {
        const audioDir = path.join(process.cwd(), "public", "audio");

        // ディレクトリが存在しなければ作成
        if (!fs.existsSync(audioDir)) {
            fs.mkdirSync(audioDir, { recursive: true });
            return { success: true, files: [] };
        }

        // ファイル一覧を取得
        const files = fs.readdirSync(audioDir)
            .filter((file) => file.endsWith(".mp3"))
            .map((file) => `/audio/${file}`);

        return {
            success: true,
            files,
        };
    } catch (error) {
        console.error("Error getting voice files:", error);
        return {
            success: false,
            error: error instanceof Error
                ? error.message
                : "Unknown error occurred",
        };
    }
}

/**
 * 音声ファイルを削除するサーバーアクション
 */
export async function deleteVoiceFile(
    fileName: string,
): Promise<{ success: boolean; error?: string }> {
    try {
        // ファイル名からパスを抽出
        const baseName = path.basename(fileName);
        const filePath = path.join(process.cwd(), "public", "audio", baseName);

        // ファイルが存在するかチェック
        if (!fs.existsSync(filePath)) {
            return {
                success: false,
                error: "File not found",
            };
        }

        // ファイルを削除
        fs.unlinkSync(filePath);

        return {
            success: true,
        };
    } catch (error) {
        console.error("Error deleting voice file:", error);
        return {
            success: false,
            error: error instanceof Error
                ? error.message
                : "Unknown error occurred",
        };
    }
}
