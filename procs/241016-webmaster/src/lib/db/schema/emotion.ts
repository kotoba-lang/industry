import { json, pgTable, text, timestamp, uuid } from "drizzle-orm/pg-core";
import { SCHEMA } from "./consent";

/**
 * 感情データテーブル
 * ユングの単語連想テストからの感情分析データを保存
 */
export const emotionData = pgTable("emotion_data", {
  // 主キー
  id: uuid("id").primaryKey().defaultRandom(),

  // 基本情報
  userId: uuid("user_id").notNull(), // ユーザーの識別子
  assessmentId: uuid("assessment_id").notNull(), // 評価セッションID
  createdAt: timestamp("created_at").defaultNow().notNull(), // 作成日時

  // 単語連想データ
  stimulusWord: text("stimulus_word").notNull(), // 刺激語
  responseWord: text("response_word").notNull(), // 反応語
  reactionTimeMs: text("reaction_time_ms").notNull(), // 反応時間（ミリ秒）

  // 感情データ
  faceEmotions: json("face_emotions").$type<Record<string, number>>(), // 顔の感情スコア
  voiceEmotions: json("voice_emotions").$type<Record<string, number>>(), // 音声の感情スコア

  // タイムスタンプ
  timestamp: timestamp("timestamp").notNull(), // 記録時のタイムスタンプ
}, (table) => {
  return {
    ...table,
    schema: SCHEMA,
  };
}).enableRLS();

/**
 * 顔の感情データテーブル
 * 顔の表情から分析した感情データを保存
 */
export const faceEmotionData = pgTable("face_emotion_data", {
  // 主キー
  id: uuid("id").primaryKey().defaultRandom(),

  // 基本情報
  userId: uuid("user_id").notNull(), // ユーザーの識別子
  assessmentId: uuid("assessment_id").notNull(), // 評価セッションID
  createdAt: timestamp("created_at").defaultNow().notNull(), // 作成日時

  // 単語連想データ
  stimulusWord: text("stimulus_word").notNull(), // 刺激語
  responseWord: text("response_word").notNull(), // 反応語
  reactionTimeMs: text("reaction_time_ms").notNull(), // 反応時間（ミリ秒）

  // 感情データ
  emotions: json("emotions").$type<Record<string, number>>(), // 感情スコア

  // タイムスタンプ
  timestamp: timestamp("timestamp").notNull(), // 記録時のタイムスタンプ
}, (table) => {
  return {
    ...table,
    schema: SCHEMA,
  };
}).enableRLS();

// スキーマの型定義をエクスポート
export type EmotionData = typeof emotionData.$inferSelect;
export type NewEmotionData = typeof emotionData.$inferInsert;
export type FaceEmotionData = typeof faceEmotionData.$inferSelect;
export type NewFaceEmotionData = typeof faceEmotionData.$inferInsert;
