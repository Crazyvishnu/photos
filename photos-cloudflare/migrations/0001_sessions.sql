CREATE TABLE IF NOT EXISTS sessions (
 id TEXT PRIMARY KEY,
 expires INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS accounts (
 owner TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
 email TEXT NOT NULL,
 profile TEXT NOT NULL,
 refresh TEXT NOT NULL,
 access TEXT NOT NULL,
 expires INTEGER NOT NULL,
 needs_reconnect INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(owner,email)
);
CREATE TABLE IF NOT EXISTS auth_states (
 id TEXT PRIMARY KEY,
 owner TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
 verifier TEXT NOT NULL,
 hint TEXT NOT NULL,
 expires INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires);
CREATE INDEX IF NOT EXISTS auth_states_expiry ON auth_states(expires);
