import { inngest, events, BatchAnalysisEvent } from '../inngest';
import { analyzeAllParticipantVideos } from '../emotion-analysis';
import { readdirSync, existsSync } from 'fs';
import { join } from 'path';

const ARTIFACTS_CACHE_PATH = '/Users/junkawasaki/jun784/root/procs/250901-com-junkawasaki-spiritinphysics/.artifacts_cache';

// バッチ分析ワークフロー
export const batchAnalysisWorkflow = inngest.createFunction(
  {
    id: 'batch-analysis-workflow',
    name: 'Batch Video Emotion Analysis',
    description: '複数参加者の動画をバッチ処理',
    priority: {
      run: 'event.data.priority || "normal"',
    },
  },
  {
    event: events.BATCH_ANALYSIS_REQUESTED,
  },
  async ({ event, step, logger }) => {
    const { participantIds, batchId } = event.data as BatchAnalysisEvent;

    logger.info(`Starting batch analysis for ${participantIds.length} participants`, {
      batchId,
      participantIds,
    });

    // バッチ開始イベント送信
    await step.run('send-batch-started', async () => {
      const startResult = await inngest.send({
        name: events.BATCH_ANALYSIS_STARTED,
        data: {
          batchId,
          participantIds,
          startedAt: new Date().toISOString(),
          totalParticipants: participantIds.length,
        },
      });
      return startResult;
    });

    // 各参加者の分析を実行
    const results = [];
    let successCount = 0;
    let failureCount = 0;

    for (let i = 0; i < participantIds.length; i++) {
      const participantId = participantIds[i];

      try {
        logger.info(`Processing participant ${i + 1}/${participantIds.length}: ${participantId}`);

        // 参加者の動画ファイルを取得
        const participantPath = join(ARTIFACTS_CACHE_PATH, participantId);
        if (!existsSync(participantPath)) {
          logger.warn(`Participant directory not found: ${participantId}`);
          failureCount++;
          continue;
        }

        const videoFiles = readdirSync(participantPath)
          .filter(file => file.endsWith('.webm'));

        if (videoFiles.length === 0) {
          logger.warn(`No video files found for participant: ${participantId}`);
          failureCount++;
          continue;
        }

        // 各動画ファイルを個別に分析
        for (const videoFile of videoFiles) {
          const sessionType = videoFile.includes('session-1') ? 'session-1' : 'session-2';

          // 個別分析イベントを送信
          const videoResult = await inngest.send({
            name: events.VIDEO_ANALYSIS_REQUESTED,
            data: {
              participantId,
              videoFile,
              sessionType,
              batchId,
              priority: event.data.priority,
            },
          });
        }

        successCount++;
        results.push({
          participantId,
          status: 'queued',
          videoCount: videoFiles.length,
        });

        // APIレート制限を考慮
        await new Promise(resolve => setTimeout(resolve, 1000));

      } catch (error) {
        logger.error(`Failed to process participant ${participantId}`, {
          error: error instanceof Error ? error.message : 'Unknown error',
        });
        failureCount++;
      }
    }

    // バッチ完了イベント送信
    await step.run('send-batch-completed', async () => {
      const status = failureCount === 0 ? 'completed' : 'partial_failure';

      const completionResult = await inngest.send({
        name: events.BATCH_ANALYSIS_COMPLETED,
        data: {
          batchId,
          participantIds,
          results,
          successCount,
          failureCount,
          totalParticipants: participantIds.length,
          completedAt: new Date().toISOString(),
          status,
        },
      });

      return completionResult;

      logger.info(`Batch analysis completed`, {
        batchId,
        successCount,
        failureCount,
        totalParticipants: participantIds.length,
      });
    });

    return {
      success: true,
      batchId,
      totalParticipants: participantIds.length,
      processedParticipants: successCount,
      failedParticipants: failureCount,
      status: failureCount === 0 ? 'completed' : 'partial_failure',
    };
  }
);

// バッチ分析失敗時の処理ワークフロー
export const batchAnalysisFailureWorkflow = inngest.createFunction(
  {
    id: 'batch-analysis-failure-handler',
    name: 'Batch Analysis Failure Handler',
  },
  {
    event: events.BATCH_ANALYSIS_FAILED,
  },
  async ({ event, step, logger }) => {
    const { batchId, error, failedParticipants } = event.data;

    logger.error(`Batch analysis failed: ${batchId}`, {
      error,
      failedParticipants,
      timestamp: new Date().toISOString(),
    });

    // 失敗時のクリーンアップ
    await step.run('cleanup-failed-batch', async () => {
      // 必要に応じてクリーンアップ処理
      logger.info(`Cleanup completed for failed batch: ${batchId}`);
      return true;
    });

    return {
      handled: true,
      batchId,
      error,
      failedParticipants,
    };
  }
);
