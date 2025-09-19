// LLM-BOUNDARY: 50_adapters - RouteHandler/ServerActions/外部API実装

import { EmotionAnalysisPort } from '@/20_ports';
import { EmotionAnalysisResult, EmotionStatistics } from '@/00_schema';
import { HumeClient } from 'hume';
import { readFileSync, existsSync } from 'fs';
import { join } from 'path';
import { foldEmotionStatistics } from '@/30_fold';

const ARTIFACTS_CACHE_PATH = '/Users/junkawasaki/jun784/root/procs/250901-com-junkawasaki-spiritinphysics/.artifacts_cache';

const hume = new HumeClient({
  apiKey: process.env.HUME_API_KEY || '',
  secretKey: process.env.HUME_API || ''
});

export class EmotionAnalysisAdapter implements EmotionAnalysisPort {
  async analyzeVideoEmotions(
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

      const videoBuffer = readFileSync(videoPath);

      const job = await hume.expressionMeasurement.batch.startInferenceJob({
        models: {
          face: {
            facs: {},
            descriptions: {}
          }
        },
        urls: [`file://${videoPath}`]
      });

      console.log(`Job started: ${job.jobId}`);
      await job.awaitCompletion();

      const predictions = await hume.expressionMeasurement.batch.getJobPredictions(job.jobId);
      const processingTime = Date.now() - startTime;

      const emotions = this.processHumePredictions(predictions);

      const result: EmotionAnalysisResult = {
        participantId,
        videoFile: videoFileName,
        sessionType,
        emotions,
        timestamp: new Date().toISOString(),
        processingTime
      };

      return result;

    } catch (error) {
      console.error(`Error analyzing emotions for ${participantId}/${videoFileName}:`, error);
      return null;
    }
  }

  async analyzeAllParticipantVideos(participantId: string): Promise<EmotionAnalysisResult[]> {
    try {
      const participantPath = join(ARTIFACTS_CACHE_PATH, participantId);

      if (!existsSync(participantPath)) {
        console.error(`Participant directory not found: ${participantPath}`);
        return [];
      }

      const videoFiles = require('fs').readdirSync(participantPath)
        .filter((file: string) => file.endsWith('.webm'));

      const results: EmotionAnalysisResult[] = [];

      for (const videoFile of videoFiles) {
        const sessionType = videoFile.includes('session-1') ? 'session-1' : 'session-2';
        const result = await this.analyzeVideoEmotions(participantId, videoFile, sessionType);
        if (result) {
          results.push(result);
        }
        await new Promise(resolve => setTimeout(resolve, 1000));
      }

      return results;
    } catch (error) {
      console.error(`Error analyzing all videos for ${participantId}:`, error);
      return [];
    }
  }

  async getEmotionStatistics(): Promise<EmotionStatistics> {
    try {
      // これは管理画面での統計計算用
      // 実際の実装ではKuzuからデータを取得して計算
      const results: EmotionAnalysisResult[] = [];
      return foldEmotionStatistics(results);
    } catch (error) {
      console.error('Error getting emotion statistics:', error);
      return {
        totalAnalyses: 0,
        averageEmotions: {},
        dominantEmotions: [],
        processingStats: { averageTime: 0, totalTime: 0 }
      };
    }
  }

  private processHumePredictions(predictions: any): Array<{
    name: string;
    score: number;
    confidence: number;
  }> {
    if (!predictions.predictions || predictions.predictions.length === 0) {
      return [];
    }

    const prediction = predictions.predictions[0];

    return prediction.emotions.map((emotion: any) => ({
      name: emotion.name,
      score: emotion.score,
      confidence: prediction.confidence || 0.5
    })).sort((a: any, b: any) => b.score - a.score);
  }
}

export const emotionAnalysisAdapter = new EmotionAnalysisAdapter();
