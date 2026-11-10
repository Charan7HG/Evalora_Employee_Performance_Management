-- ============================================================================
-- Evalora - Migration V7 : align activation-token helpers with token lifecycle
-- ============================================================================
--
-- Activation tokens are consumed by deleting their rows. Unlike password
-- reset tokens, activation_tokens has no used_at column.
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION invalidate_all_unused_activation_tokens_for_user(user_id BIGINT, now TIMESTAMPTZ)
RETURNS VOID AS $$
BEGIN
    DELETE FROM activation_tokens AS token
    WHERE token.user_id = $1
      AND token.expires_at > $2;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION has_active_activation_token(user_id BIGINT)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN EXISTS (
        SELECT 1
        FROM activation_tokens AS token
        WHERE token.user_id = $1
          AND token.expires_at > now()
    );
END;
$$ LANGUAGE plpgsql;
