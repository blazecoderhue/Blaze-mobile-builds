CREATE TABLE memories(
		id INTEGER PRIMARY KEY,
		text TEXT NOT NULL,
		norm TEXT NOT NULL,
		category TEXT NOT NULL DEFAULT '',
		project TEXT NOT NULL DEFAULT '',
		chat TEXT NOT NULL DEFAULT '',
		created INTEGER NOT NULL,
		updated INTEGER NOT NULL,
		uses INTEGER NOT NULL DEFAULT 0,
		last_used INTEGER NOT NULL DEFAULT 0,
		pinned INTEGER NOT NULL DEFAULT 0
	);
	CREATE INDEX memories_norm ON memories(norm);
	CREATE VIRTUAL TABLE memories_fts USING fts5(text, content='memories', content_rowid='id', tokenize='porter unicode61');
	CREATE TRIGGER memories_ai AFTER INSERT ON memories BEGIN
		INSERT INTO memories_fts(rowid, text) VALUES (new.id, new.text);
	END;
	CREATE TRIGGER memories_ad AFTER DELETE ON memories BEGIN
		INSERT INTO memories_fts(memories_fts, rowid, text) VALUES ('delete', old.id, old.text);
	END;
	CREATE TRIGGER memories_au AFTER UPDATE OF text ON memories BEGIN
		INSERT INTO memories_fts(memories_fts, rowid, text) VALUES ('delete', old.id, old.text);
		INSERT INTO memories_fts(rowid, text) VALUES (new.id, new.text);
	END;
	CREATE TABLE skills(name TEXT NOT NULL, desc TEXT NOT NULL DEFAULT '', path TEXT PRIMARY KEY, body TEXT NOT NULL DEFAULT '', mtime INTEGER NOT NULL DEFAULT 0);
	CREATE VIRTUAL TABLE skills_fts USING fts5(path UNINDEXED, name, desc, body, tokenize='porter unicode61');
	CREATE TABLE tools(name TEXT PRIMARY KEY, desc TEXT NOT NULL DEFAULT '', grp TEXT NOT NULL DEFAULT '');
	CREATE VIRTUAL TABLE tools_fts USING fts5(key UNINDEXED, name, desc, grp, tokenize='porter unicode61');
	CREATE TABLE active_tools(conv TEXT NOT NULL, name TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(conv, name));
	CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);