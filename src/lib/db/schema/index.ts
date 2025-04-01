// 全てのスキーマをエクスポート
export * from './demographic';
export * from './consent';
export * from './hume_emotion';

// リレーションの定義
import { relations } from 'drizzle-orm';
import { demographicData } from './demographic';
import { consentRecords } from './consent';
import { 
  emotionRecords, 
  facialEmotions, 
  facialRecognition, 
  voiceEmotions, 
  voiceRecognition
} from './hume_emotion';

// 人口統計データと同意記録の関連付け
export const demographicRelations = relations(demographicData, ({ one }) => ({
  consentRecord: one(consentRecords, {
    fields: [demographicData.userId],
    references: [consentRecords.userId],
  }),
}));

export const consentRelations = relations(consentRecords, ({ one }) => ({
  demographicData: one(demographicData, {
    fields: [consentRecords.userId],
    references: [demographicData.userId],
  }),
})); 