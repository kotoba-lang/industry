import { serve } from 'inngest/next';
import { inngest } from './inngest';
import {
  videoAnalysisWorkflow,
  videoAnalysisFailureWorkflow,
  resultsProcessingWorkflow
} from './workflows/video-analysis';
import {
  batchAnalysisWorkflow,
  batchAnalysisFailureWorkflow
} from './workflows/batch-analysis';

// 全ワークフローの統合（Inngest v3形式）
export default serve({
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
