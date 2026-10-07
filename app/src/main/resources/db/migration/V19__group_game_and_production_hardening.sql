-- Phase 11A: preserve one opportunity -> one scheduled game semantics without
-- preventing additional accepted participants from joining an exact-time game.
ALTER TABLE scheduled_games DROP CONSTRAINT IF EXISTS scheduled_games_opportunity_id_key;
DROP INDEX IF EXISTS scheduled_games_opportunity_id_key;
CREATE INDEX IF NOT EXISTS idx_games_opportunity ON scheduled_games(opportunity_id);
