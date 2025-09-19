// LLM-BOUNDARY: 70_supervisors - ルート単位の調停（invalidate/revalidate）

import { revalidatePath, revalidateTag } from 'next/cache';
import { redirect } from 'next/navigation';

// 実験ページのスーパーバイザー
export class ExperimentSupervisor {
  // 実験開始時のinvalidate
  static async startExperiment(participantId: string) {
    // 関連するキャッシュを無効化
    revalidateTag(`experiment-${participantId}`);
    revalidateTag('experiment-list');
  }

  // 実験完了時のinvalidate
  static async completeExperiment(participantId: string) {
    revalidateTag(`experiment-${participantId}`);
    revalidateTag('experiment-list');
    revalidateTag('analytics');

    // 完了ページにリダイレクト
    redirect(`/steps/complete?participant=${participantId}`);
  }

  // セッションデータ保存時のinvalidate
  static async saveSessionData(participantId: string) {
    revalidateTag(`session-${participantId}`);
    revalidateTag(`experiment-${participantId}`);
  }

  // 参加者初期化時のinvalidate
  static async initializeParticipant(participantId: string) {
    revalidateTag(`participant-${participantId}`);
    revalidateTag('participant-list');
  }
}

// 管理画面のスーパーバイザー
export class AdminSupervisor {
  // 分析データ更新時のinvalidate
  static async updateAnalytics() {
    revalidateTag('analytics');
    revalidateTag('emotion-statistics');
    revalidateTag('participant-list');
  }

  // 感情分析完了時のinvalidate
  static async completeEmotionAnalysis(participantId: string) {
    revalidateTag(`emotion-${participantId}`);
    revalidateTag('emotion-statistics');
    revalidateTag('analytics');
  }

  // データエクスポート時のinvalidate
  static async exportData() {
    revalidateTag('export-jobs');
  }
}

// 汎用的なキャッシュ管理
export class CacheSupervisor {
  // パスベースのrevalidate
  static async revalidateExperimentPaths(participantId?: string) {
    if (participantId) {
      revalidatePath(`/steps/1`);
      revalidatePath(`/steps/2`);
      revalidatePath(`/steps/complete`);
    }
    revalidatePath('/admin');
    revalidatePath('/admin/analytics');
  }

  // タグベースのrevalidate
  static async revalidateTags(tags: string[]) {
    tags.forEach(tag => revalidateTag(tag));
  }

  // 完全なrevalidate（開発時用）
  static async revalidateAll() {
    revalidatePath('/', 'layout');
  }
}

// Server Actionのラッパー
export function withSupervision<T extends any[], R>(
  action: (...args: T) => Promise<R>,
  supervisor: (result: R, ...args: T) => Promise<void>
) {
  return async (...args: T): Promise<R> => {
    const result = await action(...args);
    await supervisor(result, ...args);
    return result;
  };
}
