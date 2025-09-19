// サーバーサイドでのみKuzuをインポート
let Database: any;
let kuzuLoaded = false;

if (typeof window === 'undefined') {
  try {
    const kuzu = require('kuzu');
    Database = kuzu.Database;
    kuzuLoaded = true;
  } catch (error) {
    console.warn('Kuzu not available:', error);
  }
}

import { join } from 'path';

export interface Participant {
  id: string;
  signature: string;
  agreedAt: string;
  agreements: Record<string, any>;
}

export interface Session {
  id: string;
  participantId: string;
  events: any[];
  createdAt: string;
}

export interface VideoFile {
  id: string;
  participantId: string;
  sessionId: string;
  fileName: string;
  filePath: string;
  fileSize: number;
  createdAt: string;
}

export interface EmotionAnalysis {
  id: string;
  participantId: string;
  videoFileId: string;
  sessionType: string;
  emotions: Array<{
    name: string;
    score: number;
    confidence: number;
  }>;
  timestamp: string;
  processingTime: number;
}

export interface Emotion {
  id: string;
  analysisId: string;
  name: string;
  score: number;
  confidence: number;
}

export class KuzuManager {
  private db: Database;
  private dbPath: string;

  constructor(dbPath?: string) {
    this.dbPath = dbPath || join(process.cwd(), '.kuzu_db');

    if (!kuzuLoaded || !Database) {
      console.warn('Kuzu not available, using fallback mode');
      this.db = null as any;
      return;
    }

    try {
      this.db = new Database(this.dbPath);
    } catch (error) {
      console.error('Failed to create Kuzu database:', error);
      this.db = null as any;
    }
  }

  /**
   * データベース初期化とスキーマ作成
   */
  async initialize(): Promise<void> {
    if (!this.db) {
      console.warn('Kuzu not available, skipping initialization');
      return;
    }

    let conn: any;
    try {
      conn = new this.db.Connection();
    } catch (error) {
      console.error('Failed to create database connection:', error);
      return;
    }

    try {
      // 参加者ノード
      await conn.query(`
        CREATE NODE TABLE Participant (
          id STRING PRIMARY KEY,
          signature STRING,
          agreedAt STRING,
          agreements STRING
        )
      `);

      // セッションノード
      await conn.query(`
        CREATE NODE TABLE Session (
          id STRING PRIMARY KEY,
          participantId STRING,
          events STRING,
          createdAt STRING
        )
      `);

      // ビデオファイルノード
      await conn.query(`
        CREATE NODE TABLE VideoFile (
          id STRING PRIMARY KEY,
          participantId STRING,
          sessionId STRING,
          fileName STRING,
          filePath STRING,
          fileSize INT64,
          createdAt STRING
        )
      `);

      // 感情分析結果ノード
      await conn.query(`
        CREATE NODE TABLE EmotionAnalysis (
          id STRING PRIMARY KEY,
          participantId STRING,
          videoFileId STRING,
          sessionType STRING,
          timestamp STRING,
          processingTime INT64
        )
      `);

      // 感情ノード
      await conn.query(`
        CREATE NODE TABLE Emotion (
          id STRING PRIMARY KEY,
          analysisId STRING,
          name STRING,
          score DOUBLE,
          confidence DOUBLE
        )
      `);

      // リレーションシップ定義
      await conn.query(`
        CREATE REL TABLE PARTICIPATED_IN (
          FROM Participant TO Session
        )
      `);

      await conn.query(`
        CREATE REL TABLE HAS_VIDEO (
          FROM Session TO VideoFile
        )
      `);

      await conn.query(`
        CREATE REL TABLE ANALYZED_BY (
          FROM VideoFile TO EmotionAnalysis
        )
      `);

      await conn.query(`
        CREATE REL TABLE CONTAINS_EMOTION (
          FROM EmotionAnalysis TO Emotion
        )
      `);

      console.log('Kuzu database initialized successfully');
    } catch (error) {
      console.error('Error initializing Kuzu database:', error);
      throw error;
    } finally {
      await conn.close();
    }
  }

  /**
   * 参加者データの保存
   */
  async saveParticipant(participant: Participant): Promise<void> {
    if (!this.db) {
      console.warn('Kuzu not available, skipping participant save');
      return;
    }

    let conn: any;
    try {
      conn = new this.db.Connection();
    } catch (error) {
      console.error('Failed to create database connection:', error);
      return;
    }

    try {
      await conn.query(`
        CREATE (p:Participant {
          id: $id,
          signature: $signature,
          agreedAt: $agreedAt,
          agreements: $agreements
        })
      `, {
        id: participant.id,
        signature: participant.signature,
        agreedAt: participant.agreedAt,
        agreements: JSON.stringify(participant.agreements)
      });

      console.log(`Participant ${participant.id} saved to Kuzu`);
    } catch (error) {
      console.error('Error saving participant:', error);
      throw error;
    } finally {
      await conn.close();
    }
  }

  /**
   * セッションデータの保存
   */
  async saveSession(session: Session): Promise<void> {
    if (!this.db) {
      console.warn('Kuzu not available, skipping session save');
      return;
    }

    const conn = new this.db.Connection();

    try {
      // セッション作成
      await conn.query(`
        CREATE (s:Session {
          id: $id,
          participantId: $participantId,
          events: $events,
          createdAt: $createdAt
        })
      `, {
        id: session.id,
        participantId: session.participantId,
        events: JSON.stringify(session.events),
        createdAt: session.createdAt
      });

      // 参加者とのリレーションシップ作成
      await conn.query(`
        MATCH (p:Participant {id: $participantId}), (s:Session {id: $sessionId})
        CREATE (p)-[:PARTICIPATED_IN]->(s)
      `, {
        participantId: session.participantId,
        sessionId: session.id
      });

      console.log(`Session ${session.id} saved to Kuzu`);
    } catch (error) {
      console.error('Error saving session:', error);
      throw error;
    } finally {
      await conn.close();
    }
  }

  /**
   * ビデオファイルの保存
   */
  async saveVideoFile(videoFile: VideoFile): Promise<void> {
    if (!this.db) {
      console.warn('Kuzu not available, skipping video file save');
      return;
    }

    const conn = new this.db.Connection();

    try {
      // ビデオファイル作成
      await conn.query(`
        CREATE (v:VideoFile {
          id: $id,
          participantId: $participantId,
          sessionId: $sessionId,
          fileName: $fileName,
          filePath: $filePath,
          fileSize: $fileSize,
          createdAt: $createdAt
        })
      `, {
        id: videoFile.id,
        participantId: videoFile.participantId,
        sessionId: videoFile.sessionId,
        fileName: videoFile.fileName,
        filePath: videoFile.filePath,
        fileSize: videoFile.fileSize,
        createdAt: videoFile.createdAt
      });

      // セッションとのリレーションシップ作成
      await conn.query(`
        MATCH (s:Session {id: $sessionId}), (v:VideoFile {id: $videoFileId})
        CREATE (s)-[:HAS_VIDEO]->(v)
      `, {
        sessionId: videoFile.sessionId,
        videoFileId: videoFile.id
      });

      console.log(`Video file ${videoFile.fileName} saved to Kuzu`);
    } catch (error) {
      console.error('Error saving video file:', error);
      throw error;
    } finally {
      await conn.close();
    }
  }

  /**
   * 感情分析結果の保存
   */
  async saveEmotionAnalysis(analysis: EmotionAnalysis): Promise<void> {
    if (!this.db) {
      console.warn('Kuzu not available, skipping emotion analysis save');
      return;
    }

    const conn = new this.db.Connection();

    try {
      // 感情分析結果作成
      await conn.query(`
        CREATE (ea:EmotionAnalysis {
          id: $id,
          participantId: $participantId,
          videoFileId: $videoFileId,
          sessionType: $sessionType,
          timestamp: $timestamp,
          processingTime: $processingTime
        })
      `, {
        id: analysis.id,
        participantId: analysis.participantId,
        videoFileId: analysis.videoFileId,
        sessionType: analysis.sessionType,
        timestamp: analysis.timestamp,
        processingTime: analysis.processingTime
      });

      // ビデオファイルとのリレーションシップ作成
      await conn.query(`
        MATCH (v:VideoFile {id: $videoFileId}), (ea:EmotionAnalysis {id: $analysisId})
        CREATE (v)-[:ANALYZED_BY]->(ea)
      `, {
        videoFileId: analysis.videoFileId,
        analysisId: analysis.id
      });

      // 各感情を保存
      for (const emotion of analysis.emotions) {
        const emotionId = `${analysis.id}_${emotion.name}`;

        await conn.query(`
          CREATE (e:Emotion {
            id: $id,
            analysisId: $analysisId,
            name: $name,
            score: $score,
            confidence: $confidence
          })
        `, {
          id: emotionId,
          analysisId: analysis.id,
          name: emotion.name,
          score: emotion.score,
          confidence: emotion.confidence
        });

        // 感情分析とのリレーションシップ作成
        await conn.query(`
          MATCH (ea:EmotionAnalysis {id: $analysisId}), (e:Emotion {id: $emotionId})
          CREATE (ea)-[:CONTAINS_EMOTION]->(e)
        `, {
          analysisId: analysis.id,
          emotionId: emotionId
        });
      }

      console.log(`Emotion analysis ${analysis.id} saved to Kuzu`);
    } catch (error) {
      console.error('Error saving emotion analysis:', error);
      throw error;
    } finally {
      await conn.close();
    }
  }

  /**
   * 参加者データの取得
   */
  async getParticipant(participantId: string): Promise<Participant | null> {
    if (!this.db) {
      console.warn('Kuzu not available, returning null');
      return null;
    }

    let conn: any;
    try {
      conn = new this.db.Connection();
    } catch (error) {
      console.error('Failed to create database connection:', error);
      return null;
    }

    try {
      const result = await conn.query(`
        MATCH (p:Participant {id: $id})
        RETURN p.id, p.signature, p.agreedAt, p.agreements
      `, { id: participantId });

      if (result.length === 0) {
        return null;
      }

      const row = result[0];
      return {
        id: row['p.id'],
        signature: row['p.signature'],
        agreedAt: row['p.agreedAt'],
        agreements: JSON.parse(row['p.agreements'])
      };
    } catch (error) {
      console.error('Error getting participant:', error);
      throw error;
    } finally {
      await conn.close();
    }
  }

  /**
   * 全参加者データの取得
   */
  async getAllParticipants(): Promise<Participant[]> {
    if (!this.db) {
      console.warn('Kuzu not available, returning empty array');
      return [];
    }

    let conn: any;
    try {
      conn = new this.db.Connection();
    } catch (error) {
      console.error('Failed to create database connection:', error);
      return [];
    }

    try {
      const result = await conn.query(`
        MATCH (p:Participant)
        RETURN p.id, p.signature, p.agreedAt, p.agreements
      `);

      return result.map((row: any) => ({
        id: row['p.id'],
        signature: row['p.signature'],
        agreedAt: row['p.agreedAt'],
        agreements: JSON.parse(row['p.agreements'])
      }));
    } catch (error) {
      console.error('Error getting all participants:', error);
      throw error;
    } finally {
      await conn.close();
    }
  }

  /**
   * 感情分析結果の取得
   */
  async getEmotionAnalysis(participantId: string): Promise<EmotionAnalysis[]> {
    if (!this.db) {
      console.warn('Kuzu not available, returning empty array');
      return [];
    }

    const conn = new this.db.Connection();

    try {
      const result = await conn.query(`
        MATCH (ea:EmotionAnalysis {participantId: $participantId})
        OPTIONAL MATCH (ea)-[:CONTAINS_EMOTION]->(e:Emotion)
        RETURN ea, COLLECT(e) as emotions
      `, { participantId });

      return result.map((row: any) => {
        const ea = row.ea;
        const emotions = row.emotions || [];

        return {
          id: ea.id,
          participantId: ea.participantId,
          videoFileId: ea.videoFileId,
          sessionType: ea.sessionType,
          timestamp: ea.timestamp,
          processingTime: ea.processingTime,
          emotions: emotions.map((e: any) => ({
            name: e.name,
            score: e.score,
            confidence: e.confidence
          }))
        };
      });
    } catch (error) {
      console.error('Error getting emotion analysis:', error);
      throw error;
    } finally {
      await conn.close();
    }
  }

  /**
   * 感情統計の取得
   */
  async getEmotionStatistics(): Promise<any> {
    if (!this.db) {
      console.warn('Kuzu not available, returning empty stats');
      return {
        totalAnalyses: 0,
        averageEmotions: {},
        dominantEmotions: [],
        processingStats: { averageTime: 0, totalTime: 0 }
      };
    }

    const conn = new this.db.Connection();

    try {
      const result = await conn.query(`
        MATCH (e:Emotion)
        RETURN e.name, COUNT(e) as count, AVG(e.score) as avgScore
        ORDER BY count DESC
      `);

      const stats = {
        totalAnalyses: 0,
        averageEmotions: {} as Record<string, number>,
        dominantEmotions: [] as Array<{ emotion: string; count: number }>
      };

      for (const row of result) {
        stats.averageEmotions[row['e.name']] = row.avgScore;
        stats.dominantEmotions.push({
          emotion: row['e.name'],
          count: row.count
        });
        stats.totalAnalyses += row.count;
      }

      return stats;
    } catch (error) {
      console.error('Error getting emotion statistics:', error);
      throw error;
    } finally {
      await conn.close();
    }
  }

  /**
   * データベース接続のクローズ
   */
  async close(): Promise<void> {
    await this.db.close();
  }

  /**
   * カスタムクエリの実行
   */
  async executeQuery(query: string, params: Record<string, any> = {}): Promise<any> {
    if (!this.db) {
      throw new Error('Kuzu database not available');
    }

    const conn = new this.db.Connection();

    try {
      return await conn.query(query, params);
    } catch (error) {
      console.error('Error executing query:', error);
      throw error;
    } finally {
      await conn.close();
    }
  }
}

// シングルトンインスタンス
export const kuzuManager = new KuzuManager();
