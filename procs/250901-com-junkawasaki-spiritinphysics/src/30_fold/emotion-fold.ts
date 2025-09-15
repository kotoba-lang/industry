// LLM-BOUNDARY: 30_fold - 純関数（MDAG -> 投影）※副作用禁止

import { EmotionAnalysisResult, EmotionStatistics } from '@/00_schema';

// 感情分析結果のコレクションから統計を計算する純関数
export function foldEmotionStatistics(results: EmotionAnalysisResult[]): EmotionStatistics {
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
