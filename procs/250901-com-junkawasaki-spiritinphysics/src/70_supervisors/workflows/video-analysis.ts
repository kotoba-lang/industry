import { inngest, events, VideoAnalysisEvent, AnalysisResultEvent } from '@/50_adapters';
import { emotionAnalysisAdapter } from '@/50_adapters';
import { storageAdapter } from '@/50_adapters';
import { EmotionAnalysisResult } from '@/00_schema';
import { existsSync } from 'fs';
import { join } from 'path';

const ARTIFACTS_CACHE_PATH = '/Users/junkawasaki/jun784/root/procs/250901-com-junkawasaki-spiritinphysics/.artifacts_cache';

// 個別動画分析ワークフロー
export const videoAnalysisWorkflow = inngest.createFunction(
  {
    id: 'video-analysis-workflow',
    name: 'Video Emotion Analysis',
    description: 'Hume AIを使用した個別動画の感情分析',
    priority: {
      run: 'event.data.priority || "normal"',
    },
  },
  {
    event: events.VIDEO_ANALYSIS_REQUESTED,
  },
  async ({ event, step, logger }) => {
    const { participantId, videoFile, sessionType, retryCount = 0 } = event.data as VideoAnalysisEvent;

    logger.info(`Starting video analysis for ${participantId}/${videoFile}`, {
      participantId,
      videoFile,
      sessionType,
      retryCount,
    });

    // ステップ1: 動画ファイルの存在確認
    const fileExists = await step.run('check-video-file', async () => {
      const videoPath = join(ARTIFACTS_CACHE_PATH, participantId, videoFile);
      const exists = existsSync(videoPath);

      if (!exists) {
        throw new Error(`Video file not found: ${videoPath}`);
      }

      logger.info(`Video file verified: ${videoPath}`);
      return true;
    });

    // ステップ2: 分析開始イベント送信
    await step.run('send-analysis-started', async () => {
      const startResult = await inngest.send({
        name: events.VIDEO_ANALYSIS_STARTED,
        data: {
          participantId,
          videoFile,
          sessionType,
          startedAt: new Date().toISOString(),
        },
      });
      return startResult;
    });

    // ステップ3: Hume AI感情分析実行
    let emotionResult: EmotionAnalysisResult;
    await step.run('analyze-emotions', async () => {
      logger.info(`Starting emotion analysis for ${participantId}/${videoFile}`);

      try {
        const result = await emotionAnalysisAdapter.analyzeVideoEmotions(participantId, videoFile, sessionType);

        if (!result) {
          throw new Error('Emotion analysis returned no result');
        }

        emotionResult = result;

        logger.info(`Emotion analysis completed for ${participantId}/${videoFile}`, {
          emotionsDetected: result.emotions.length,
          processingTime: result.processingTime,
        });

        return result;
      } catch (error) {
        logger.error(`Emotion analysis failed for ${participantId}/${videoFile}`, {
          error: error instanceof Error ? error.message : 'Unknown error',
          retryCount,
        });

        // リトライカウントを増やして再送
        if (retryCount < 3) {
          const retryResult = await inngest.send({
            name: events.VIDEO_ANALYSIS_REQUESTED,
            data: {
              ...event.data,
              retryCount: retryCount + 1,
            },
          });
          return retryResult;
        }

        throw error;
      }
    });

    // ステップ4: 結果の永続化
    await step.run('persist-results', async () => {
      try {
        await storageAdapter.saveEmotionAnalysis(participantId, emotionResult);

        logger.info(`Analysis results persisted for ${participantId}/${videoFile}`, {
          emotionsCount: emotionResult.emotions.length,
          dominantEmotion: emotionResult.emotions[0]?.name,
        });
      } catch (error) {
        logger.error(`Failed to persist results for ${participantId}/${videoFile}`, {
          error: error instanceof Error ? error.message : 'Unknown error',
        });
        throw error;
      }
    });

    // ステップ5: 完了イベント送信
    await step.run('send-completion-event', async () => {
      const completionEvent: AnalysisResultEvent = {
        participantId,
        videoFile,
        results: emotionResult,
        processingTime: emotionResult.processingTime,
        metadata: {
          sessionType,
          emotionsDetected: emotionResult.emotions.length,
          timestamp: emotionResult.timestamp,
        },
      };

      const completionResult = await inngest.send({
        name: events.VIDEO_ANALYSIS_COMPLETED,
        data: completionEvent,
      });
      return completionResult;

      logger.info(`Video analysis workflow completed for ${participantId}/${videoFile}`);
    });

    return {
      success: true,
      participantId,
      videoFile,
      sessionType,
      emotionsDetected: emotionResult.emotions.length,
      processingTime: emotionResult.processingTime,
    };
  }
);

// 動画分析失敗時の処理ワークフロー
export const videoAnalysisFailureWorkflow = inngest.createFunction(
  {
    id: 'video-analysis-failure-handler',
    name: 'Video Analysis Failure Handler',
  },
  {
    event: events.VIDEO_ANALYSIS_FAILED,
  },
  async ({ event, step, logger }) => {
    const { participantId, videoFile, error } = event.data;

    logger.error(`Video analysis failed for ${participantId}/${videoFile}`, {
      error,
      timestamp: new Date().toISOString(),
    });

    // 失敗時のクリーンアップ処理
    await step.run('cleanup-failed-analysis', async () => {
      // 必要に応じて一時ファイルのクリーンアップなど
      logger.info(`Cleanup completed for failed analysis: ${participantId}/${videoFile}`);
    });

    // 通知送信（将来の拡張用）
    await step.run('send-notification', async () => {
      const notificationResult = await inngest.send({
        name: events.NOTIFICATION_SENT,
        data: {
          type: 'analysis_failure',
          participantId,
          videoFile,
          message: `動画分析に失敗しました: ${error}`,
          timestamp: new Date().toISOString(),
        },
      });
      return notificationResult;
    });

    return {
      handled: true,
      participantId,
      videoFile,
      error,
    };
  }
);

// 結果処理ワークフロー
export const resultsProcessingWorkflow = inngest.createFunction(
  {
    id: 'results-processing-workflow',
    name: 'Analysis Results Processing',
  },
  {
    event: events.VIDEO_ANALYSIS_COMPLETED,
  },
  async ({ event, step, logger }) => {
    const { participantId, videoFile, results } = event.data as AnalysisResultEvent;

    logger.info(`Processing results for ${participantId}/${videoFile}`);

    // 統計更新
    await step.run('update-statistics', async () => {
      // 参加者の統計情報を更新
      logger.info(`Statistics updated for ${participantId}`);
    });

    // 相関分析のトリガー（必要に応じて）
    await step.run('trigger-correlation-analysis', async () => {
      // 新しい分析結果に基づいて相関分析をトリガー
      logger.info(`Correlation analysis triggered for ${participantId}`);
    });

    return {
      processed: true,
      participantId,
      videoFile,
      resultsProcessed: true,
    };
  }
);
