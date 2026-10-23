-- Persist the deadline returned by signing-key rotation so cleanup survives restarts and configuration changes.
ALTER TABLE scheduler.team_keysets
    ADD COLUMN previous_key_valid_until timestamptz,
    ADD CONSTRAINT team_keysets_overlap_purpose_ck
        CHECK (purpose = 'SIGNING' OR previous_key_valid_until IS NULL);

CREATE INDEX team_keysets_signing_expiry_ix ON scheduler.team_keysets (previous_key_valid_until)
    WHERE purpose = 'SIGNING' AND previous_key_valid_until IS NOT NULL;
