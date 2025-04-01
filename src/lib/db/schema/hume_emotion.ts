import { pgTable, uuid, timestamp, json, text, integer, serial, boolean, real, pgSchema } from 'drizzle-orm/pg-core';
import { relations } from 'drizzle-orm';

// スキーマを定義
export const spiritSchema = pgSchema('spirit_in_physics');

// テーブル: 感情認識レコード (主テーブル)
export const emotionRecords = spiritSchema.table('emotion_records', {
  id: uuid('id').defaultRandom().primaryKey(),
  userId: text('user_id').notNull(),
  assessmentId: text('assessment_id').notNull(),
  recordedAt: timestamp('recorded_at').defaultNow().notNull(),
  testType: text('test_type').notNull(), // 'word' または 'voice'
  stimulusWord: text('stimulus_word'),
  responseWord: text('response_word'),
  reactionTimeMs: integer('reaction_time_ms'),
});

// テーブル: 顔の感情データ
export const facialEmotions = spiritSchema.table('facial_emotions', {
  id: uuid('id').defaultRandom().primaryKey(),
  emotionRecordId: uuid('emotion_record_id').references(() => emotionRecords.id, { onDelete: 'cascade' }).notNull(),
  emotionName: text('emotion_name').notNull(), // 例: 'Joy', 'Sadness' など
  score: real('score').notNull(), // 感情スコア (0-1)
});

// テーブル: 顔認識の追加メタデータ
export const facialRecognition = spiritSchema.table('facial_recognition', {
  id: uuid('id').defaultRandom().primaryKey(),
  emotionRecordId: uuid('emotion_record_id').references(() => emotionRecords.id, { onDelete: 'cascade' }).notNull(),
  // 顔の検出情報
  bboxX: real('bbox_x'),
  bboxY: real('bbox_y'),
  bboxWidth: real('bbox_width'),
  bboxHeight: real('bbox_height'),
  // その他のメタデータ
  confidence: real('confidence'),
  rawData: json('raw_data'), // Humeからの生のレスポンス
});

// テーブル: 音声感情データ
export const voiceEmotions = spiritSchema.table('voice_emotions', {
  id: uuid('id').defaultRandom().primaryKey(),
  emotionRecordId: uuid('emotion_record_id').references(() => emotionRecords.id, { onDelete: 'cascade' }).notNull(),
  emotionName: text('emotion_name').notNull(), // 例: 'Confidence', 'Confusion' など
  score: real('score').notNull(), // 感情スコア (0-1)
});

// テーブル: 音声認識の追加メタデータ
export const voiceRecognition = spiritSchema.table('voice_recognition', {
  id: uuid('id').defaultRandom().primaryKey(),
  emotionRecordId: uuid('emotion_record_id').references(() => emotionRecords.id, { onDelete: 'cascade' }).notNull(),
  // 音声の特徴
  speechDurationMs: integer('speech_duration_ms'),
  speakingRate: real('speaking_rate'),
  pauseCount: integer('pause_count'),
  // その他のメタデータ
  confidence: real('confidence'),
  rawData: json('raw_data'), // Humeからの生のレスポンス
});

// Add this table definition
export const emotionAssessments = spiritSchema.table('emotion_assessments', {
  id: uuid('id').defaultRandom().primaryKey(),
  userId: text('user_id').notNull(),
  createdAt: timestamp('created_at').defaultNow().notNull(),
  updatedAt: timestamp('updated_at').defaultNow().notNull(),
});

// リレーションの定義
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const emotionRecordsRelations = relations(emotionRecords as any, ({ many }) => ({
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  facialEmotions: many(facialEmotions as any),
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  facialRecognition: many(facialRecognition as any),
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  voiceEmotions: many(voiceEmotions as any),
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  voiceRecognition: many(voiceRecognition as any),
}));

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const facialEmotionsRelations = relations(facialEmotions as any, ({ one }) => ({
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  emotionRecord: one(emotionRecords as any, {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    fields: [facialEmotions.emotionRecordId as any],
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    references: [emotionRecords.id as any],
  }),
}));

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const facialRecognitionRelations = relations(facialRecognition as any, ({ one }) => ({
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  emotionRecord: one(emotionRecords as any, {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    fields: [facialRecognition.emotionRecordId as any],
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    references: [emotionRecords.id as any],
  }),
}));

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const voiceEmotionsRelations = relations(voiceEmotions as any, ({ one }) => ({
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  emotionRecord: one(emotionRecords as any, {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    fields: [voiceEmotions.emotionRecordId as any],
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    references: [emotionRecords.id as any],
  }),
}));

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const voiceRecognitionRelations = relations(voiceRecognition as any, ({ one }) => ({
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  emotionRecord: one(emotionRecords as any, {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    fields: [voiceRecognition.emotionRecordId as any],
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    references: [emotionRecords.id as any],
  }),
}));