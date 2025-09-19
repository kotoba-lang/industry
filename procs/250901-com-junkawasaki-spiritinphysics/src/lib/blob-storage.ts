import { put, head, del, list } from '@vercel/blob';
import { readFileSync } from 'fs';
import { join } from 'path';

export class BlobStorage {
  private static blobStoreUrl = 'https://kosv1afs1n9zpomr.public.blob.vercel-storage.com';

  /**
   * ファイルをVercel Blobにアップロード
   */
  static async uploadFile(filePath: string, blobPath: string): Promise<string> {
    try {
      const fileContent = readFileSync(filePath);
      const fileName = blobPath.split('/').pop() || 'file';

      // MIMEタイプの判定
      const mimeType = this.getMimeType(fileName);

      const blob = await put(blobPath, fileContent, {
        access: 'public',
        contentType: mimeType,
      });

      console.log(`File uploaded to Vercel Blob: ${blob.url}`);
      return blob.url;
    } catch (error) {
      console.error('Error uploading file to Vercel Blob:', error);
      throw error;
    }
  }

  /**
   * ファイルをVercel Blobからダウンロード
   */
  static async downloadFile(blobUrl: string): Promise<Buffer> {
    try {
      const response = await fetch(blobUrl);
      if (!response.ok) {
        if (response.status === 404) {
          throw new Error(`File not found: ${blobUrl}`);
        }
        throw new Error(`Failed to download file: ${response.status}`);
      }
      const arrayBuffer = await response.arrayBuffer();
      return Buffer.from(arrayBuffer);
    } catch (error) {
      console.error('Error downloading file from Vercel Blob:', error);
      throw error;
    }
  }

  /**
   * JSONデータをVercel Blobに保存
   */
  static async uploadJson(data: any, blobPath: string): Promise<string> {
    try {
      const jsonString = JSON.stringify(data, null, 2);
      const blob = await put(blobPath, jsonString, {
        access: 'public',
        contentType: 'application/json',
      });

      console.log(`JSON uploaded to Vercel Blob: ${blob.url}`);
      return blob.url;
    } catch (error) {
      console.error('Error uploading JSON to Vercel Blob:', error);
      throw error;
    }
  }

  /**
   * JSONデータをVercel Blobから取得
   */
  static async downloadJson(blobUrl: string): Promise<any> {
    try {
      const buffer = await this.downloadFile(blobUrl);
      return JSON.parse(buffer.toString());
    } catch (error) {
      console.error('Error downloading JSON from Vercel Blob:', error);
      throw error;
    }
  }

  /**
   * ファイルをVercel Blobから削除
   */
  static async deleteFile(blobUrl: string): Promise<void> {
    try {
      // blobUrlからpathnameを取得
      const url = new URL(blobUrl);
      const pathname = url.pathname;

      await del(pathname);
      console.log(`File deleted from Vercel Blob: ${blobUrl}`);
    } catch (error) {
      console.error('Error deleting file from Vercel Blob:', error);
      throw error;
    }
  }

  /**
   * フォルダ内のファイルを一覧取得
   */
  static async listFiles(prefix: string): Promise<string[]> {
    try {
      const { blobs } = await list({ prefix });
      return blobs.map(blob => blob.url);
    } catch (error) {
      console.error('Error listing files from Vercel Blob:', error);
      throw error;
    }
  }

  /**
   * ファイルが存在するかチェック
   */
  static async fileExists(blobUrl: string): Promise<boolean> {
    try {
      const response = await head(blobUrl);
      return response !== null;
    } catch (error) {
      return false;
    }
  }

  /**
   * ファイル名からMIMEタイプを判定
   */
  private static getMimeType(fileName: string): string {
    const ext = fileName.split('.').pop()?.toLowerCase();

    const mimeTypes: Record<string, string> = {
      'json': 'application/json',
      'jsonl': 'application/json',
      'webm': 'video/webm',
      'mp4': 'video/mp4',
      'jpg': 'image/jpeg',
      'jpeg': 'image/jpeg',
      'png': 'image/png',
      'gif': 'image/gif',
      'txt': 'text/plain',
      'csv': 'text/csv',
    };

    return mimeTypes[ext || ''] || 'application/octet-stream';
  }

  /**
   * 参加者データをBlobに保存
   */
  static async saveParticipantData(participantId: string, data: any): Promise<string> {
    const blobPath = `participants/${participantId}/consent.json`;
    return await this.uploadJson(data, blobPath);
  }

  /**
   * セッションデータをBlobに保存
   */
  static async saveSessionData(participantId: string, sessionData: any): Promise<string> {
    const blobPath = `participants/${participantId}/session_data.json`;
    return await this.uploadJson(sessionData, blobPath);
  }

  /**
   * ビデオファイルをBlobに保存
   */
  static async saveVideoFile(participantId: string, videoPath: string, sessionType: string): Promise<string> {
    const fileName = `${sessionType}-video.webm`;
    const blobPath = `participants/${participantId}/videos/${fileName}`;
    return await this.uploadFile(videoPath, blobPath);
  }

  /**
   * 感情分析結果をBlobに保存
   */
  static async saveEmotionAnalysis(participantId: string, analysisData: any): Promise<string> {
    const blobPath = `participants/${participantId}/emotion_analysis.json`;
    return await this.uploadJson(analysisData, blobPath);
  }

  /**
   * 参加者データをBlobから取得
   */
  static async getParticipantData(participantId: string): Promise<any> {
    const blobUrl = `${this.blobStoreUrl}/participants/${participantId}/consent.json`;
    return await this.downloadJson(blobUrl);
  }

  /**
   * セッションデータをBlobから取得
   */
  static async getSessionData(participantId: string): Promise<any> {
    const blobUrl = `${this.blobStoreUrl}/participants/${participantId}/session_data.json`;
    return await this.downloadJson(blobUrl);
  }

  /**
   * 感情分析結果をBlobから取得
   */
  static async getEmotionAnalysis(participantId: string): Promise<any> {
    const blobUrl = `${this.blobStoreUrl}/participants/${participantId}/emotion_analysis.json`;
    return await this.downloadJson(blobUrl);
  }

  /**
   * 全参加者のリストをBlobから取得
   */
  static async getAllParticipants(): Promise<string[]> {
    try {
      const files = await this.listFiles('participants/');
      const participantIds = new Set<string>();

      files.forEach(file => {
        const match = file.match(/participants\/([^\/]+)\//);
        if (match) {
          participantIds.add(match[1]);
        }
      });

      return Array.from(participantIds);
    } catch (error) {
      console.error('Error getting all participants from Blob:', error);
      return [];
    }
  }

  /**
   * 参加者データが存在するかチェック
   */
  static async participantExists(participantId: string): Promise<boolean> {
    const blobUrl = `${this.blobStoreUrl}/participants/${participantId}/consent.json`;
    return await this.fileExists(blobUrl);
  }
}
