ALTER TABLE "consent_records" ENABLE ROW LEVEL SECURITY;--> statement-breakpoint
ALTER TABLE "demographic_data" ENABLE ROW LEVEL SECURITY;--> statement-breakpoint
ALTER TABLE "emotion_data" ENABLE ROW LEVEL SECURITY;--> statement-breakpoint
ALTER TABLE "face_emotion_data" ENABLE ROW LEVEL SECURITY;--> statement-breakpoint
CREATE POLICY "Anyone can insert consent_records" ON "consent_records" AS PERMISSIVE FOR INSERT TO "anon" WITH CHECK (true);--> statement-breakpoint
CREATE POLICY "Authenticated users can select consent_records" ON "consent_records" AS PERMISSIVE FOR SELECT TO "authenticated" USING (true);--> statement-breakpoint
CREATE POLICY "Anyone can insert demographic_data" ON "demographic_data" AS PERMISSIVE FOR INSERT TO "anon" WITH CHECK (true);--> statement-breakpoint
CREATE POLICY "Authenticated users can select demographic_data" ON "demographic_data" AS PERMISSIVE FOR SELECT TO "authenticated" USING (true);--> statement-breakpoint
CREATE POLICY "Anyone can insert emotion_data" ON "emotion_data" AS PERMISSIVE FOR INSERT TO "anon" WITH CHECK (true);--> statement-breakpoint
CREATE POLICY "Authenticated users can select emotion_data" ON "emotion_data" AS PERMISSIVE FOR SELECT TO "authenticated" USING (true);--> statement-breakpoint
CREATE POLICY "Anyone can insert face_emotion_data" ON "face_emotion_data" AS PERMISSIVE FOR INSERT TO "anon" WITH CHECK (true);--> statement-breakpoint
CREATE POLICY "Authenticated users can select face_emotion_data" ON "face_emotion_data" AS PERMISSIVE FOR SELECT TO "authenticated" USING (true);