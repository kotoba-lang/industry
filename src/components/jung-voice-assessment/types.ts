import { z } from 'zod';

// 単一の単語応答の型
export interface WordResponse {
  stimulus: string;
  response: string;
  reactionTimeMs: number;
  isDelayed: boolean;
}

// テスト結果の型
export interface TestResults {
  responses: WordResponse[];
  averageReactionTimeMs: number;
  delayedResponseCount: number;
  completedAt: Date;
}

// メッセージの型
export interface Message {
  id: string;
  content: string;
  sender: 'user' | 'assistant';
  timestamp: Date;
}

// JungVoiceAssessment コンポーネントのプロップスの型
export interface JungVoiceAssessmentProps {
  /**
   * テストする単語の数
   * デフォルト: 100
   */
  numberOfWords?: number;
  
  /**
   * Hume AI API キー
   */
  apiKey?: string;
  
  /**
   * Hume AI 音声生成ID
   */
  generationId?: string;
  
  /**
   * 使用する音声の名前
   */
  voiceName?: string;
  
  /**
   * 音声認識の言語
   * デフォルト: 'ja-JP'
   */
  speechRecognitionLang?: string;
  
  /**
   * テスト完了時のコールバック
   */
  onTestComplete?: (results: TestResults) => void;
  
  /**
   * 追加のCSSクラス
   */
  className?: string;
}

// JungVoiceTest コンポーネントのプロップスの型
export interface JungVoiceTestProps {
  numberOfWords?: number;
  apiKey?: string;
  generationId?: string;
  voiceName?: string;
  speechRecognitionLang?: string;
  onTestComplete?: (results: TestResults) => void;
  className?: string;
}

// AI ガイドメッセージの型
export interface GuideMessage {
  introduction: string;
  nextWord: string;
  testComplete: string;
  delayed: string;
  normal: string;
} 