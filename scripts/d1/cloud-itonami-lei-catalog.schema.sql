-- cloud-itonami-lei-catalog — Cloudflare D1 schema for the worldwide
-- GLEIF-verified corporate catalog assembled from the cloud-itonami-lei-*
-- repos (one repo per real LEI, blueprint.edn + 80-data/public/tos.journal.edn
-- as the git-authoritative source).
--
-- WHY D1 AND NOT kotobase (2026-07-25, owner direction): the kotobase graph
-- plane is not stable enough to depend on yet. Its head record lives on B2,
-- which has NO conditional-write primitive, so the write path cannot do a real
-- compare-and-swap -- a background auto-fold can and did silently drop a
-- transact that had already returned ok to the client (ADR-2607252000 ledger
-- seq 65). D1 is SQLite with real transactions and strong consistency, so
-- "the write returned success" and "the row is durably there" are the same
-- statement. Git stays the source of truth either way; both D1 and kotobase
-- are derived, rebuildable projections of the same repos.
--
-- Idempotent by construction: every load is an upsert keyed by the natural
-- key (LEI for a company, LEI+source_url for a document), so re-running the
-- ingest converges instead of duplicating -- the same property the kotobase
-- ingest relied on, kept deliberately so the two can be cross-checked.

CREATE TABLE IF NOT EXISTS company (
  lei                 TEXT PRIMARY KEY,          -- ISO 17442, uppercase
  legal_name          TEXT NOT NULL,
  jurisdiction        TEXT,                      -- ISO 3166-1 alpha-2
  website             TEXT,
  ticker              TEXT,
  isic_rev5           TEXT,
  sector              TEXT,
  reg_status          TEXT,                      -- GLEIF registration status
  contact_email       TEXT,
  contact_email_note  TEXT,
  inquiry_form_url    TEXT,
  -- ORGANISATION-level contact facts only (ADR-2608043000). A switchboard
  -- number and a registered address identify the company, not a natural
  -- person, so they belong in this public projection alongside the role inbox.
  -- Person-level facts (a representative's name and title) are NOT here and
  -- must never be added: they live age-encrypted in the private
  -- cloud-itonami-contact-pii dataset. Adding a `representative` column to this
  -- table would move personal data into a public, world-rebuildable projection
  -- in one ALTER.
  phone               TEXT,                      -- E.164 or as published
  postal_address      TEXT,                      -- registered/HQ address, as published
  registration_number TEXT,                      -- commercial register no. (HRB, 法人番号, ...)
  country             TEXT,                      -- ISO 3166-1 alpha-2, normalised from jurisdiction
  repo                TEXT NOT NULL,             -- github.com/cloud-itonami/<repo>
  blueprint_sha256    TEXT,                      -- of the fetched blueprint.edn
  ingested_at         TEXT NOT NULL              -- ISO-8601 UTC
);

CREATE INDEX IF NOT EXISTS company_jurisdiction_idx ON company (jurisdiction);
CREATE INDEX IF NOT EXISTS company_legal_name_idx   ON company (legal_name);
CREATE INDEX IF NOT EXISTS company_sector_idx       ON company (sector);
-- Partial indexes: the outbound-contact working set is the rows that actually
-- have a reachable contact, and it is a small minority of the table today
-- (14/161 at the time of writing), so a partial index keeps those lookups
-- cheap without carrying ~150 NULL entries.
CREATE INDEX IF NOT EXISTS company_has_email_idx    ON company (lei) WHERE contact_email    IS NOT NULL;
CREATE INDEX IF NOT EXISTS company_has_form_idx     ON company (lei) WHERE inquiry_form_url IS NOT NULL;

-- One row per legal document actually fetched from the company's own site
-- (ToS / privacy policy / etc), with the provenance the journal records.
CREATE TABLE IF NOT EXISTS tos_doc (
  lei           TEXT NOT NULL REFERENCES company (lei) ON DELETE CASCADE,
  source_url    TEXT NOT NULL,
  doc_type      TEXT,                            -- :terms-of-service | :privacy-policy | ...
  retrieved_at  TEXT,
  sha256        TEXT,
  text_chars    INTEGER,
  lang          TEXT,
  ingested_at   TEXT NOT NULL,
  PRIMARY KEY (lei, source_url)
);

CREATE INDEX IF NOT EXISTS tos_doc_lei_idx      ON tos_doc (lei);
CREATE INDEX IF NOT EXISTS tos_doc_doc_type_idx ON tos_doc (doc_type);

-- Append-only record of each ingest run, so "is this catalog current, and did
-- the last load actually cover everything?" is answerable from the data itself
-- rather than from a terminal scrollback. Completeness is what caught the
-- kotobase lost-update bug; keeping the check inside the system makes it
-- routine instead of something someone has to remember to do.
CREATE TABLE IF NOT EXISTS ingest_run (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  started_at     TEXT NOT NULL,
  finished_at    TEXT,
  repos_seen     INTEGER,                        -- cloud-itonami-lei-* repos on GitHub
  companies_ok   INTEGER,
  companies_fail INTEGER,
  docs_ok        INTEGER,
  note           TEXT
);
