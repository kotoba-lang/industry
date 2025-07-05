-- consent_records テーブルの作成時に user_id を PRIMARY KEY に設定する
-- または以下のように明示的にユニーク制約を追加する
ALTER TABLE spirit_in_physics.consent_records
  ADD CONSTRAINT consent_records_user_id_key UNIQUE (user_id);

-- Create foreign key relationships
ALTER TABLE spirit_in_physics.demographic_data
  ADD CONSTRAINT fk_demographic_consent
  FOREIGN KEY (user_id)
  REFERENCES spirit_in_physics.consent_records(user_id)
  ON DELETE CASCADE; 