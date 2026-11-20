CREATE VIRTUAL TABLE IF NOT EXISTS animes_fts USING fts5(
  title,
  title_alt,
  content='animes',
  content_rowid='id',
  tokenize='unicode61'
);

CREATE TRIGGER IF NOT EXISTS animes_ai AFTER INSERT ON animes BEGIN
  INSERT INTO animes_fts(rowid, title, title_alt) VALUES (new.id, new.title, COALESCE(new.title_alt, ''));
END;

CREATE TRIGGER IF NOT EXISTS animes_ad AFTER DELETE ON animes BEGIN
  INSERT INTO animes_fts(animes_fts, rowid, title, title_alt) VALUES ('delete', old.id, old.title, COALESCE(old.title_alt, ''));
END;

CREATE TRIGGER IF NOT EXISTS animes_au AFTER UPDATE ON animes BEGIN
  INSERT INTO animes_fts(animes_fts, rowid, title, title_alt) VALUES ('delete', old.id, old.title, COALESCE(old.title_alt, ''));
  INSERT INTO animes_fts(rowid, title, title_alt) VALUES (new.id, new.title, COALESCE(new.title_alt, ''));
END;

INSERT INTO animes_fts(animes_fts) VALUES('rebuild');

CREATE TABLE IF NOT EXISTS anime_genres (
  genre TEXT NOT NULL,
  anime_id INTEGER NOT NULL,
  PRIMARY KEY (genre, anime_id)
);
CREATE INDEX IF NOT EXISTS idx_anime_genres_anime ON anime_genres(anime_id);
