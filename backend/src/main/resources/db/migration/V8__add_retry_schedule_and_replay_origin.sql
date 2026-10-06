ALTER TABLE jobs ADD COLUMN next_attempt_at TIMESTAMPTZ;
ALTER TABLE jobs ADD COLUMN replayed_from_job_id BIGINT REFERENCES jobs(id) ON DELETE SET NULL;
CREATE INDEX idx_jobs_replay_origin ON jobs(replayed_from_job_id) WHERE replayed_from_job_id IS NOT NULL;
