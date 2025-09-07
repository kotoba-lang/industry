CREATE TABLE IF NOT EXISTS "consent_records" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" uuid NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"consent_given" boolean NOT NULL,
	"consent_version" text NOT NULL,
	"consent_text" text,
	"ip_address" text,
	"user_agent" text,
	"study_id" text,
	"researcher_note" text
);
--> statement-breakpoint
CREATE TABLE IF NOT EXISTS "demographic_data" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" uuid NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"age_group" text NOT NULL,
	"gender" text NOT NULL,
	"ethnicity" text NOT NULL,
	"income" text NOT NULL,
	"ip_address" text,
	"user_agent" text,
	"study_id" text,
	"consent_version" text
);
--> statement-breakpoint
CREATE TABLE IF NOT EXISTS "emotion_data" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" uuid NOT NULL,
	"assessment_id" uuid NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"stimulus_word" text NOT NULL,
	"response_word" text NOT NULL,
	"reaction_time_ms" text NOT NULL,
	"face_emotions" json,
	"voice_emotions" json,
	"timestamp" timestamp NOT NULL
);
--> statement-breakpoint
CREATE TABLE IF NOT EXISTS "face_emotion_data" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" uuid NOT NULL,
	"assessment_id" uuid NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"stimulus_word" text NOT NULL,
	"response_word" text NOT NULL,
	"reaction_time_ms" text NOT NULL,
	"emotions" json,
	"timestamp" timestamp NOT NULL
);
