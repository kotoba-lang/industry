import { pgTable, text, timestamp, uuid } from "drizzle-orm/pg-core";
import { SCHEMA } from "./consent";

/**
 * 人口統計データテーブル
 * CDISCに基づいて収集された人口統計情報を保存
 */
export const demographicData = pgTable("demographic_data", {
  // 主キー
  id: uuid("id").primaryKey().defaultRandom(),

  // 基本情報
  userId: uuid("user_id").notNull(), // ユーザーの識別子
  createdAt: timestamp("created_at").defaultNow().notNull(), // 作成日時

  // 人口統計データ (CDISCに基づく)
  ageGroup: text("age_group").notNull(), // 年代
  gender: text("gender").notNull(), // 性別
  ethnicity: text("ethnicity").notNull(), // 人種・民族
  income: text("income").notNull(), // 年収

  // 追加のメタデータ
  ipAddress: text("ip_address"), // アクセス元IP (匿名化可能)
  userAgent: text("user_agent"), // ブラウザ情報

  // 研究関連のメタデータ
  studyId: text("study_id"), // 研究ID
  consentVersion: text("consent_version"), // 同意書バージョン
}, (table) => {
  return {
    ...table,
    schema: SCHEMA,
  };
}).enableRLS();

// スキーマの型定義をエクスポート
export type DemographicData = typeof demographicData.$inferSelect;
export type NewDemographicData = typeof demographicData.$inferInsert;
