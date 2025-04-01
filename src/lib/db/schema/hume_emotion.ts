import { pgTable, uuid, timestamp, json, text, integer, serial, boolean, real } from 'drizzle-orm/pg-core';
import { relations } from 'drizzle-orm';

// スキーマを定義
const schema = 'spirit_in_physics';

// テーブル: 感情認識レコード (主テーブル)
export const emotionRecords = pgTable('emotion_records', {
  id: uuid('id').defaultRandom().primaryKey(),
  userId: text('user_id').notNull(),
  assessmentId: text('assessment_id').notNull(),
  recordedAt: timestamp('recorded_at').defaultNow().notNull(),
  testType: text('test_type').notNull(), // 'word' または 'voice'
  stimulusWord: text('stimulus_word'),
  responseWord: text('response_word'),
  reactionTimeMs: integer('reaction_time_ms'),
}, (table) => {
  return {
    ...table,
    schema,
  };
});

// テーブル: 顔の感情データ
export const facialEmotions = pgTable('facial_emotions', {
  id: uuid('id').defaultRandom().primaryKey(),
  emotionRecordId: uuid('emotion_record_id').references(() => emotionRecords.id, { onDelete: 'cascade' }).notNull(),
  emotionName: text('emotion_name').notNull(), // 例: 'Joy', 'Sadness' など
  score: real('score').notNull(), // 感情スコア (0-1)
}, (table) => {
  return {
    ...table,
    schema,
  };
});

// テーブル: 顔認識の追加メタデータ
export const facialRecognition = pgTable('facial_recognition', {
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
}, (table) => {
  return {
    ...table,
    schema,
  };
});

// テーブル: 音声感情データ
export const voiceEmotions = pgTable('voice_emotions', {
  id: uuid('id').defaultRandom().primaryKey(),
  emotionRecordId: uuid('emotion_record_id').references(() => emotionRecords.id, { onDelete: 'cascade' }).notNull(),
  emotionName: text('emotion_name').notNull(), // 例: 'Confidence', 'Confusion' など
  score: real('score').notNull(), // 感情スコア (0-1)
}, (table) => {
  return {
    ...table,
    schema,
  };
});

// テーブル: 音声認識の追加メタデータ
export const voiceRecognition = pgTable('voice_recognition', {
  id: uuid('id').defaultRandom().primaryKey(),
  emotionRecordId: uuid('emotion_record_id').references(() => emotionRecords.id, { onDelete: 'cascade' }).notNull(),
  // 音声の特徴
  speechDurationMs: integer('speech_duration_ms'),
  speakingRate: real('speaking_rate'),
  pauseCount: integer('pause_count'),
  // その他のメタデータ
  confidence: real('confidence'),
  rawData: json('raw_data'), // Humeからの生のレスポンス
}, (table) => {
  return {
    ...table,
    schema,
  };
});

// リレーションの定義
export const emotionRecordsRelations = relations(emotionRecords, ({ many }) => ({
  facialEmotions: many(facialEmotions),
  facialRecognition: many(facialRecognition),
  voiceEmotions: many(voiceEmotions),
  voiceRecognition: many(voiceRecognition),
}));

export const facialEmotionsRelations = relations(facialEmotions, ({ one }) => ({
  emotionRecord: one(emotionRecords, {
    fields: [facialEmotions.emotionRecordId],
    references: [emotionRecords.id],
  }),
}));

export const facialRecognitionRelations = relations(facialRecognition, ({ one }) => ({
  emotionRecord: one(emotionRecords, {
    fields: [facialRecognition.emotionRecordId],
    references: [emotionRecords.id],
  }),
}));

export const voiceEmotionsRelations = relations(voiceEmotions, ({ one }) => ({
  emotionRecord: one(emotionRecords, {
    fields: [voiceEmotions.emotionRecordId],
    references: [emotionRecords.id],
  }),
}));

export const voiceRecognitionRelations = relations(voiceRecognition, ({ one }) => ({
  emotionRecord: one(emotionRecords, {
    fields: [voiceRecognition.emotionRecordId],
    references: [emotionRecords.id],
  }),
})); 