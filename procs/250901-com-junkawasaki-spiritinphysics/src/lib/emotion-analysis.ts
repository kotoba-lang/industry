import { HumeClient } from 'hume';
import { readFileSync, writeFileSync, existsSync } from 'fs';
import { join } from 'path';

// サーバーサイドでのみKuzuをインポート
let kuzuManager: any = null;
let EmotionAnalysis: any;

if (typeof window === 'undefined') {
  try {
    const kuzuModule = require('./database/kuzu-manager');
    kuzuManager = kuzuModule.kuzuManager;
    EmotionAnalysis = kuzuModule.EmotionAnalysis;
  } catch (error) {
    console.warn('Kuzu manager not available:', error);
  }
}

const ARTIFACTS_CACHE_PATH = '/Users/junkawasaki/jun784/root/procs/250901-com-junkawasaki-spiritinphysics/.artifacts_cache';

interface EmotionAnalysisResult {
  participantId: string;
  videoFile: string;
  sessionType: string;
  emotions: Array<{
    name: string;
    score: number;
    confidence: number;
  }>;
  timestamp: string;
  processingTime: number;
}

interface HumeEmotionResponse {
  predictions: Array<{
    emotions: Array<{
      name: string;
      score: number;
    }>;
    confidence?: number;
  }>;
}

// Humeクライアントの初期化
const hume = new HumeClient({
  apiKey: process.env.HUME_API_KEY || '',
  secretKey: process.env.HUME_API || ''
});

/**
 * ビデオファイルに対して感情分析を実行
 */
export async function analyzeVideoEmotions(
  participantId: string,
  videoFileName: string,
  sessionType: string
): Promise<EmotionAnalysisResult | null> {
  try {
    const videoPath = join(ARTIFACTS_CACHE_PATH, participantId, videoFileName);

    if (!existsSync(videoPath)) {
      console.error(`Video file not found: ${videoPath}`);
      return null;
    }

    console.log(`Starting emotion analysis for ${participantId}/${videoFileName}`);
    const startTime = Date.now();

    // ビデオファイルを読み込み
    const videoBuffer = readFileSync(videoPath);

    // Hume APIで感情分析を実行
    const job = await hume.expressionMeasurement.batch.startInferenceJob({
      models: {
        face: {
          facs: {},
          descriptions: {}
        }
      },
      urls: [`file://${videoPath}`] // ファイルパスを指定
    });

    console.log(`Job started: ${job.jobId}`);

    // ジョブの完了を待つ
    await job.awaitCompletion();

    // 結果を取得
    const predictions = await hume.expressionMeasurement.batch.getJobPredictions(job.jobId);

    const processingTime = Date.now() - startTime;

    // 結果を処理
    const emotions = processHumePredictions(predictions);

    const result: EmotionAnalysisResult = {
      participantId,
      videoFile: videoFileName,
      sessionType,
      emotions,
      timestamp: new Date().toISOString(),
      processingTime
    };

    // 結果を保存
    await saveEmotionAnalysisResult(result);

    console.log(`Emotion analysis completed for ${participantId}/${videoFileName}`);
    return result;

  } catch (error) {
    console.error(`Error analyzing emotions for ${participantId}/${videoFileName}:`, error);
    return null;
  }
}

/**
 * Hume APIの予測結果を処理して感情データを抽出
 */
function processHumePredictions(predictions: HumeEmotionResponse): Array<{
  name: string;
  score: number;
  confidence: number;
}> {
  if (!predictions.predictions || predictions.predictions.length === 0) {
    return [];
  }

  // 最初の予測結果を使用（必要に応じて平均化などの処理を追加可能）
  const prediction = predictions.predictions[0];

  return prediction.emotions.map(emotion => ({
    name: emotion.name,
    score: emotion.score,
    confidence: prediction.confidence || 0.5
  })).sort((a, b) => b.score - a.score); // スコアの高い順にソート
}

/**
 * 感情分析結果をファイルに保存
 */
export async function saveEmotionAnalysisResult(result: EmotionAnalysisResult): Promise<void> {
  try {
    // ファイルに保存（既存の動作を維持）
    const resultPath = join(ARTIFACTS_CACHE_PATH, result.participantId, 'emotion_analysis.json');

    let existingResults: EmotionAnalysisResult[] = [];
    if (existsSync(resultPath)) {
      existingResults = JSON.parse(readFileSync(resultPath, 'utf-8'));
    }

    const existingIndex = existingResults.findIndex(
      r => r.videoFile === result.videoFile && r.sessionType === result.sessionType
    );

    if (existingIndex >= 0) {
      existingResults[existingIndex] = result;
    } else {
      existingResults.push(result);
    }

    writeFileSync(resultPath, JSON.stringify(existingResults, null, 2));
    console.log(`Emotion analysis result saved to file: ${resultPath}`);

    // Blobにも保存（利用可能な場合）
    if (blobStorage) {
      try {
        await blobStorage.saveEmotionAnalysis(result.participantId, result);
        console.log(`Emotion analysis result saved to Vercel Blob for ${result.participantId}`);
      } catch (blobError) {
        console.warn('Failed to save emotion analysis to Blob:', blobError);
      }
    }

    // Kuzuにも保存（利用可能な場合）
    if (kuzuManager) {
      try {
        const analysis: EmotionAnalysis = {
          id: `${result.participantId}_${result.videoFile}_${Date.now()}`,
          participantId: result.participantId,
          videoFileId: `${result.participantId}_${result.videoFile}`,
          sessionType: result.sessionType,
          timestamp: result.timestamp,
          processingTime: result.processingTime,
          emotions: result.emotions
        };

        await kuzuManager.saveEmotionAnalysis(analysis);
        console.log(`Emotion analysis result saved to Kuzu: ${analysis.id}`);
      } catch (kuzuError) {
        console.warn('Failed to save emotion analysis to Kuzu:', kuzuError);
      }
    }

  } catch (error) {
    console.error('Error saving emotion analysis result:', error);
  }
}

/**
 * 保存された感情分析結果を読み込み
 */
export async function loadEmotionAnalysisResults(participantId: string): Promise<EmotionAnalysisResult[]> {
  try {
    // Kuzuデータベースから感情分析データを取得（一本化）
    if (kuzuManager) {
      try {
        const kuzuResults = await kuzuManager.getEmotionAnalysis(participantId);
        console.log(`Loaded emotion analysis results from Kuzu for ${participantId}`);
        return kuzuResults.map(ka => ({
          participantId: ka.participantId,
          videoFile: ka.videoFileId.replace(`${ka.participantId}_`, ''),
          sessionType: ka.sessionType,
          emotions: ka.emotions,
          timestamp: ka.timestamp,
          processingTime: ka.processingTime
        }));
      } catch (kuzuError) {
        console.warn('Failed to load emotion analysis from Kuzu:', kuzuError);
        return [];
      }
    } else {
      console.warn('Kuzu manager not available');
      return [];
    }
  } catch (error) {
    console.error(`Error loading emotion analysis results for ${participantId}:`, error);
    return [];
  }
}

/**
 * 参加者の全ビデオファイルに対して感情分析を実行
 */
export async function analyzeAllParticipantVideos(participantId: string): Promise<EmotionAnalysisResult[]> {
  try {
    const participantPath = join(ARTIFACTS_CACHE_PATH, participantId);

    if (!existsSync(participantPath)) {
      console.error(`Participant directory not found: ${participantPath}`);
      return [];
    }

    // ビデオファイルを取得
    const videoFiles = require('fs').readdirSync(participantPath)
      .filter((file: string) => file.endsWith('.webm'));

    const results: EmotionAnalysisResult[] = [];

    for (const videoFile of videoFiles) {
      // セッションタイプをファイル名から判定
      const sessionType = videoFile.includes('session-1') ? 'session-1' : 'session-2';

      const result = await analyzeVideoEmotions(participantId, videoFile, sessionType);
      if (result) {
        results.push(result);
      }

      // APIレート制限を考慮して少し待つ
      await new Promise(resolve => setTimeout(resolve, 1000));
    }

    return results;
  } catch (error) {
    console.error(`Error analyzing all videos for ${participantId}:`, error);
    return [];
  }
}

/**
 * Kuzuから感情分析の統計情報を取得
 */
export async function getEmotionStatisticsFromKuzu(): Promise<{
  totalAnalyses: number;
  averageEmotions: Record<string, number>;
  dominantEmotions: Array<{ emotion: string; count: number }>;
  processingStats: {
    averageTime: number;
    totalTime: number;
  };
}> {
  try {
    if (kuzuManager) {
      return await kuzuManager.getEmotionStatistics();
    } else {
      console.warn('Kuzu manager not available, returning empty stats');
    }
  } catch (error) {
    console.error('Error getting emotion statistics from Kuzu:', error);
  }

  // Fallback to empty stats
  return {
    totalAnalyses: 0,
    averageEmotions: {},
    dominantEmotions: [],
    processingStats: { averageTime: 0, totalTime: 0 }
  };
}

/**
 * 感情分析の統計情報を生成（従来の関数）
 */
export function generateEmotionStatistics(results: EmotionAnalysisResult[]): {
  totalAnalyses: number;
  averageEmotions: Record<string, number>;
  dominantEmotions: Array<{ emotion: string; count: number }>;
  processingStats: {
    averageTime: number;
    totalTime: number;
  };
} {
  if (results.length === 0) {
    return {
      totalAnalyses: 0,
      averageEmotions: {},
      dominantEmotions: [],
      processingStats: { averageTime: 0, totalTime: 0 }
    };
  }

  const emotionScores: Record<string, number[]> = {};
  const dominantEmotions: Record<string, number> = {};
  let totalProcessingTime = 0;

  results.forEach(result => {
    totalProcessingTime += result.processingTime;

    result.emotions.forEach(emotion => {
      if (!emotionScores[emotion.name]) {
        emotionScores[emotion.name] = [];
      }
      emotionScores[emotion.name].push(emotion.score);
    });

    // 各分析で最もスコアの高い感情をカウント
    if (result.emotions.length > 0) {
      const dominantEmotion = result.emotions[0].name;
      dominantEmotions[dominantEmotion] = (dominantEmotions[dominantEmotion] || 0) + 1;
    }
  });

  // 平均感情スコアを計算
  const averageEmotions: Record<string, number> = {};
  Object.entries(emotionScores).forEach(([emotion, scores]) => {
    averageEmotions[emotion] = scores.reduce((a, b) => a + b, 0) / scores.length;
  });

  // ドミナント感情をソート
  const sortedDominantEmotions = Object.entries(dominantEmotions)
    .map(([emotion, count]) => ({ emotion, count }))
    .sort((a, b) => b.count - a.count);

  return {
    totalAnalyses: results.length,
    averageEmotions,
    dominantEmotions: sortedDominantEmotions,
    processingStats: {
      averageTime: totalProcessingTime / results.length,
      totalTime: totalProcessingTime
    }
  };
}
