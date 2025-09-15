import { del, head, list, put } from "@vercel/blob";
import { v4 as uuidv4 } from "uuid";

export interface ArtifactMetadata {
    id: string;
    participantId: string;
    type: "consent" | "session_data" | "video" | "audio";
    sessionId?: string;
    filename: string;
    size: number;
    uploadedAt: string;
    url: string;
}

export class BlobStorageService {
    private static instance: BlobStorageService;
    private readonly ARTIFACTS_PREFIX = "artifacts/";

    private constructor() {}

    static getInstance(): BlobStorageService {
        if (!BlobStorageService.instance) {
            BlobStorageService.instance = new BlobStorageService();
        }
        return BlobStorageService.instance;
    }

    /**
     * アーティファクトを Blob Storage にアップロード
     */
    async uploadArtifact(
        file: File | Buffer,
        metadata: Omit<ArtifactMetadata, "id" | "size" | "uploadedAt" | "url">,
    ): Promise<ArtifactMetadata> {
        const id = uuidv4();
        const key =
            `${this.ARTIFACTS_PREFIX}${metadata.participantId}/${metadata.type}/${id}-${metadata.filename}`;

        try {
            // ファイルサイズを取得
            const size = file instanceof Buffer
                ? file.length
                : (file as File).size;

            const blob = await put(key, file, {
                access: "public",
                contentType: this.getContentType(metadata.filename),
            });

            const artifactMetadata: ArtifactMetadata = {
                id,
                ...metadata,
                size: size,
                uploadedAt: new Date().toISOString(),
                url: blob.url,
            };

            return artifactMetadata;
        } catch (error) {
            console.error("Failed to upload artifact:", error);
            throw new Error(`Failed to upload artifact: ${error}`);
        }
    }

    /**
     * アーティファクトをダウンロード
     */
    async downloadArtifact(url: string): Promise<Blob> {
        try {
            const response = await fetch(url);
            if (!response.ok) {
                throw new Error(
                    `Failed to download artifact: ${response.statusText}`,
                );
            }
            return await response.blob();
        } catch (error) {
            console.error("Failed to download artifact:", error);
            throw new Error(`Failed to download artifact: ${error}`);
        }
    }

    /**
     * 特定参加者のアーティファクト一覧を取得
     */
    async listParticipantArtifacts(
        participantId: string,
    ): Promise<ArtifactMetadata[]> {
        try {
            const prefix = `${this.ARTIFACTS_PREFIX}${participantId}/`;
            const { blobs } = await list({ prefix });

            return blobs.map((blob) => this.blobToMetadata(blob));
        } catch (error) {
            console.error("Failed to list participant artifacts:", error);
            throw new Error(`Failed to list participant artifacts: ${error}`);
        }
    }

    /**
     * アーティファクトを削除
     */
    async deleteArtifact(url: string): Promise<void> {
        try {
            await del(url);
        } catch (error) {
            console.error("Failed to delete artifact:", error);
            throw new Error(`Failed to delete artifact: ${error}`);
        }
    }

    /**
     * ファイル名から Content-Type を推定
     */
    private getContentType(filename: string): string {
        const ext = filename.toLowerCase().split(".").pop();

        switch (ext) {
            case "json":
                return "application/json";
            case "webm":
                return "video/webm";
            case "mp4":
                return "video/mp4";
            case "mp3":
                return "audio/mpeg";
            case "wav":
                return "audio/wav";
            default:
                return "application/octet-stream";
        }
    }

    /**
     * Blob オブジェクトから ArtifactMetadata に変換
     */
    private blobToMetadata(blob: any): ArtifactMetadata {
        const parts = blob.key.replace(this.ARTIFACTS_PREFIX, "").split("/");
        const participantId = parts[0];
        const type = parts[1] as ArtifactMetadata["type"];
        const filename = parts.slice(2).join("/");

        return {
            id: blob.key.split("/").pop()?.split("-")[0] || "",
            participantId,
            type,
            filename,
            size: blob.size || 0,
            uploadedAt: blob.uploadedAt,
            url: blob.url,
        };
    }

    /**
     * 感情分析結果をBlobに保存
     */
    async saveEmotionAnalysis(participantId: string, analysisData: any): Promise<string> {
        const blobPath = `participants/${participantId}/emotion_analysis.json`;
        return await this.uploadJson(analysisData, blobPath);
    }

    /**
     * 感情分析結果をBlobから取得
     */
    async getEmotionAnalysis(participantId: string): Promise<any> {
        const blobUrl = `${this.blobStoreUrl}/participants/${participantId}/emotion_analysis.json`;
        return await this.downloadJson(blobUrl);
    }
}

export const blobStorage = BlobStorageService.getInstance();
