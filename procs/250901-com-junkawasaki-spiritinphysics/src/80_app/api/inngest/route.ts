import { serve } from 'inngest/next';
import { inngest } from '@/lib/inngest';
import {
  videoAnalysisWorkflow,
  videoAnalysisFailureWorkflow,
  resultsProcessingWorkflow
} from '@/lib/workflows/video-analysis';
import {
  batchAnalysisWorkflow,
  batchAnalysisFailureWorkflow
} from '@/lib/workflows/batch-analysis';

// Inngest APIルート（v3形式）
export const { GET, POST, PUT } = serve({
  client: inngest,
  functions: [
    // 動画分析ワークフロー
    videoAnalysisWorkflow,
    videoAnalysisFailureWorkflow,
    resultsProcessingWorkflow,

    // バッチ分析ワークフロー
    batchAnalysisWorkflow,
    batchAnalysisFailureWorkflow,
  ],
});
