-- Four total attempts allow the 15s, 60s, and 300s retry levels.
-- Existing jobs retain their original attempt budget.
ALTER TABLE jobs ALTER COLUMN max_attempts SET DEFAULT 4;
