-- Operators opt a team into terminal run events. Existing teams remain unsubscribed.
ALTER TABLE scheduler.teams ADD COLUMN completed_events_enabled boolean NOT NULL DEFAULT false;
