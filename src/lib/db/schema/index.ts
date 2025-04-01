// Export all schemas
export * from './consent';
export * from './demographic';
export * from './emotion';

// Relations
import { relations } from 'drizzle-orm';
import { demographicData } from './demographic';
import { consentRecords } from './consent';
import { emotionData, faceEmotionData } from './emotion';

// Define relationships between tables
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

// Emotion data relations
export const emotionDataRelations = relations(emotionData, ({ one }) => ({
  // No relations for now, but can be extended in the future
})); 