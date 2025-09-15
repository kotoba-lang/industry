// クライアント側ワークフローサービス（サーバーサイドAPIを呼び出すのみ）

export class WorkflowService {
  /**
   * 個別動画分析ワークフローを開始
   */
  static async startVideoAnalysis(
    participantId: string,
    videoFile: string,
    sessionType: string,
    priority: 'low' | 'normal' | 'high' = 'normal'
  ): Promise<{ success: boolean; eventId?: string; error?: string }> {
    try {
      const response = await fetch('/api/admin/emotion-analysis', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          action: 'analyze-single-workflow',
          participantId,
          videoFile,
          sessionType,
          priority,
        }),
      });

      const result = await response.json();

      if (!result.success) {
        throw new Error(result.error || 'ワークフロー開始に失敗しました');
      }

      return {
        success: true,
        eventId: result.data?.eventId,
      };
    } catch (error) {
      console.error('Failed to start video analysis workflow:', error);
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  /**
   * バッチ動画分析ワークフローを開始
   */
  static async startBatchAnalysis(
    participantIds: string[],
    priority: 'low' | 'normal' | 'high' = 'normal'
  ): Promise<{ success: boolean; batchId?: string; eventId?: string; error?: string }> {
    try {
      const response = await fetch('/api/admin/emotion-analysis', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          action: 'analyze-batch-workflow',
          participantId: participantIds[0], // 個別参加者用に変更
          priority,
        }),
      });

      const result = await response.json();

      if (!result.success) {
        throw new Error(result.error || 'ワークフロー開始に失敗しました');
      }

      return {
        success: true,
        batchId: result.data?.batchId,
        eventId: result.data?.eventId,
      };
    } catch (error) {
      console.error('Failed to start batch analysis workflow:', error);
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  /**
   * 参加者の全動画を分析
   */
  static async analyzeParticipantVideos(
    participantId: string,
    priority: 'low' | 'normal' | 'high' = 'normal'
  ): Promise<{ success: boolean; batchId?: string; eventId?: string; error?: string }> {
    return this.startBatchAnalysis([participantId], priority);
  }

  /**
   * 全参加者の動画を分析
   */
  static async analyzeAllParticipants(
    priority: 'low' | 'normal' | 'high' = 'normal'
  ): Promise<{ success: boolean; batchId?: string; eventId?: string; error?: string }> {
    try {
      const response = await fetch('/api/admin/emotion-analysis', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          action: 'analyze-all-workflow',
          priority,
        }),
      });

      const result = await response.json();

      if (!result.success) {
        throw new Error(result.error || '全参加者分析ワークフロー開始に失敗しました');
      }

      return {
        success: true,
        batchId: result.data?.batchId,
        eventId: result.data?.eventId,
      };
    } catch (error) {
      console.error('Failed to start all participants analysis workflow:', error);
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  /**
   * ワークフロー状態を確認
   */
  static async getWorkflowStatus(eventId: string): Promise<any> {
    try {
      // 簡易的な状態確認
      return {
        eventId,
        status: 'processing',
        timestamp: new Date().toISOString(),
      };
    } catch (error) {
      console.error('Failed to get workflow status:', error);
      return {
        eventId,
        status: 'error',
        error: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  /**
   * ワークフロー統計を取得
   */
  static async getWorkflowStatistics(): Promise<{
    totalEvents: number;
    activeWorkflows: number;
    completedWorkflows: number;
    failedWorkflows: number;
  }> {
    // 簡易的な統計情報
    return {
      totalEvents: 0,
      activeWorkflows: 0,
      completedWorkflows: 0,
      failedWorkflows: 0,
    };
  }
}
