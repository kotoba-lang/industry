-- Create schema
CREATE SCHEMA IF NOT EXISTS spirit_in_physics;

-- Create consent records table
CREATE TABLE IF NOT EXISTS spirit_in_physics.consent_records (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,
  consent_given BOOLEAN NOT NULL,
  consent_version TEXT NOT NULL,
  consent_text TEXT,
  ip_address TEXT,
  user_agent TEXT,
  study_id TEXT,
  researcher_note TEXT
);

-- Add unique constraint to consent_records.user_id
ALTER TABLE spirit_in_physics.consent_records
  ADD CONSTRAINT consent_records_user_id_key UNIQUE (user_id);

-- Create demographic data table
CREATE TABLE IF NOT EXISTS spirit_in_physics.demographic_data (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,
  age_group TEXT NOT NULL,
  gender TEXT NOT NULL,
  ethnicity TEXT NOT NULL,
  income TEXT NOT NULL,
  ip_address TEXT,
  user_agent TEXT,
  study_id TEXT,
  consent_version TEXT
);

-- Create foreign key relationships
ALTER TABLE spirit_in_physics.demographic_data
  ADD CONSTRAINT fk_demographic_consent
  FOREIGN KEY (user_id)
  REFERENCES spirit_in_physics.consent_records(user_id)
  ON DELETE CASCADE;

-- Add Row Level Security (RLS) policies
ALTER TABLE spirit_in_physics.consent_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE spirit_in_physics.demographic_data ENABLE ROW LEVEL SECURITY;

-- Create policies for authenticated users only
CREATE POLICY "Authenticated users can insert consent records"
  ON spirit_in_physics.consent_records
  FOR INSERT
  TO authenticated
  WITH CHECK (true);

CREATE POLICY "Authenticated users can insert demographic data"
  ON spirit_in_physics.demographic_data
  FOR INSERT
  TO authenticated
  WITH CHECK (true);

-- Create policies for service role (admin access)
CREATE POLICY "Service role can manage all consent records"
  ON spirit_in_physics.consent_records
  FOR ALL
  TO service_role
  USING (true);

CREATE POLICY "Service role can manage all demographic data"
  ON spirit_in_physics.demographic_data
  FOR ALL
  TO service_role
  USING (true);

-- Temporarily enable anon access for initial setup (can be restricted later)
CREATE POLICY "Allow anonymous consent submission"
  ON spirit_in_physics.consent_records
  FOR INSERT
  TO anon
  WITH CHECK (true);

CREATE POLICY "Allow anonymous demographic submission"
  ON spirit_in_physics.demographic_data
  FOR INSERT
  TO anon
  WITH CHECK (true); 