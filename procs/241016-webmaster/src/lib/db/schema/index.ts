// Export all schemas
export * from "./consent";
export * from "./demographic";
export * from "./emotion";

// Relations
import { relations } from "drizzle-orm";
import { demographicData } from "./demographic";
import { consentRecords } from "./consent";
import { emotionData, faceEmotionData } from "./emotion";
import { pgPolicy } from "drizzle-orm/pg-core";
import { sql } from "drizzle-orm";
import { anonRole, authenticatedRole } from "drizzle-orm/supabase";

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

// RLS Policies for anon access (insert-only)
export const consentRecordsAnonInsertPolicy = pgPolicy(
  "Anyone can insert consent_records",
  {
    for: "insert",
    to: anonRole,
    withCheck: sql`true`,
  },
).link(consentRecords);

export const demographicDataAnonInsertPolicy = pgPolicy(
  "Anyone can insert demographic_data",
  {
    for: "insert",
    to: anonRole,
    withCheck: sql`true`,
  },
).link(demographicData);

export const emotionDataAnonInsertPolicy = pgPolicy(
  "Anyone can insert emotion_data",
  {
    for: "insert",
    to: anonRole,
    withCheck: sql`true`,
  },
).link(emotionData);

export const faceEmotionDataAnonInsertPolicy = pgPolicy(
  "Anyone can insert face_emotion_data",
  {
    for: "insert",
    to: anonRole,
    withCheck: sql`true`,
  },
).link(faceEmotionData);

// RLS Policies for authenticated access (select)
export const consentRecordsAuthSelectPolicy = pgPolicy(
  "Authenticated users can select consent_records",
  {
    for: "select",
    to: authenticatedRole,
    using: sql`true`,
  },
).link(consentRecords);

export const demographicDataAuthSelectPolicy = pgPolicy(
  "Authenticated users can select demographic_data",
  {
    for: "select",
    to: authenticatedRole,
    using: sql`true`,
  },
).link(demographicData);

export const emotionDataAuthSelectPolicy = pgPolicy(
  "Authenticated users can select emotion_data",
  {
    for: "select",
    to: authenticatedRole,
    using: sql`true`,
  },
).link(emotionData);

export const faceEmotionDataAuthSelectPolicy = pgPolicy(
  "Authenticated users can select face_emotion_data",
  {
    for: "select",
    to: authenticatedRole,
    using: sql`true`,
  },
).link(faceEmotionData);
