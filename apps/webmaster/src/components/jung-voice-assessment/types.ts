import { z } from 'zod';

// 単一の単語応答の型
export interface WordResponse {
  stimulusWord: string;
  responseWord: string;
  reactionTimeMs: number;
  isDelayed?: boolean;
}

// テスト結果の型
export interface TestResults {
  totalWords: number;
  averageReactionTimeMs: number;
  delayedResponsesCount: number;
  responses: WordResponse[];
  completedAt?: Date;
}

// メッセージの型
export interface Message {
  text: string;
  role: 'user' | 'assistant';
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
   * デフォルト: 'en-US'
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