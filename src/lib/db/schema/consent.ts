import { pgTable, text, timestamp, uuid, boolean } from 'drizzle-orm/pg-core';
import { v4 as uuidv4 } from 'uuid';

/**
 * 研究参加の同意記録テーブル
 * ICH-GCPに準拠した同意プロセスの記録を保存
 */
export const consentRecords = pgTable('consent_records', {
  // 主キー
  id: uuid('id').primaryKey().defaultFn(() => uuidv4()),
  
  // 基本情報
  userId: uuid('user_id').notNull(), // ユーザーの識別子
  createdAt: timestamp('created_at').defaultNow(), // 作成日時
  
  // 同意情報
  consentGiven: boolean('consent_given').notNull(), // 同意が得られたか
  consentVersion: text('consent_version').notNull(), // 同意書バージョン
  consentText: text('consent_text'), // 同意時に表示されたテキスト（オプション）
  
  // 監査用情報
  ipAddress: text('ip_address'), // 同意時のIPアドレス
  userAgent: text('user_agent'), // 同意時のブラウザ情報
  
  // 研究関連のメタデータ
  studyId: text('study_id'), // 研究ID
  researcherNote: text('researcher_note'), // 研究者のメモ
});

// スキーマの型定義をエクスポート
export type ConsentRecord = typeof consentRecords.$inferSelect;
export type NewConsentRecord = typeof consentRecords.$inferInsert; 