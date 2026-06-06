-- AI chat conversations and messages tables.
-- conversations: one row per chat session per user
-- chat_messages: each turn in the conversation (role = user | assistant)

CREATE TABLE IF NOT EXISTS conversations (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    title       TEXT        NOT NULL DEFAULT 'New conversation',
    is_archived BOOLEAN     NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS chat_messages (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    role            TEXT        NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content         TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for the common query patterns used by AiRoutes
CREATE INDEX IF NOT EXISTS idx_conversations_user
    ON conversations (user_id, is_archived, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_chat_messages_conversation
    ON chat_messages (conversation_id, created_at ASC);

-- Auto-update conversations.updated_at whenever a new message is inserted
CREATE OR REPLACE FUNCTION update_conversation_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE conversations SET updated_at = NOW() WHERE id = NEW.conversation_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_chat_messages_update_conversation ON chat_messages;
CREATE TRIGGER trg_chat_messages_update_conversation
    AFTER INSERT ON chat_messages
    FOR EACH ROW EXECUTE FUNCTION update_conversation_timestamp();

-- RLS: users can only see and modify their own conversations and messages
ALTER TABLE conversations  ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_messages  ENABLE ROW LEVEL SECURITY;

CREATE POLICY IF NOT EXISTS "conversations_owner" ON conversations
    FOR ALL USING (auth.uid() = user_id);

CREATE POLICY IF NOT EXISTS "chat_messages_owner" ON chat_messages
    FOR ALL USING (auth.uid() = user_id);
