-- 1. Narrow FTS5 update trigger so non-title updates never rewrite animes_fts
DROP TRIGGER IF EXISTS animes_au;
CREATE TRIGGER animes_au AFTER UPDATE OF title, title_alt ON animes
WHEN old.title IS NOT new.title OR COALESCE(old.title_alt, '') != COALESCE(new.title_alt, '')
BEGIN
  INSERT INTO animes_fts(animes_fts, rowid, title, title_alt)
  VALUES ('delete', old.id, old.title, COALESCE(old.title_alt, ''));
  INSERT INTO animes_fts(rowid, title, title_alt)
  VALUES (new.id, new.title, COALESCE(new.title_alt, ''));
END;

-- 2. Keep anime_genres synchronized automatically (normalized lowercase)
DROP TRIGGER IF EXISTS animes_genres_ai;
CREATE TRIGGER animes_genres_ai AFTER INSERT ON animes
WHEN new.genres_json IS NOT NULL
  AND json_valid(new.genres_json)
  AND json_type(new.genres_json) = 'array'
BEGIN
  INSERT OR IGNORE INTO anime_genres(genre, anime_id)
  SELECT LOWER(TRIM(value)), new.id
  FROM json_each(
    CASE
      WHEN json_valid(new.genres_json) AND json_type(new.genres_json) = 'array'
      THEN new.genres_json
      ELSE '[]'
    END
  )
  WHERE type = 'text' AND TRIM(value) != '';
END;

DROP TRIGGER IF EXISTS animes_genres_au;
CREATE TRIGGER animes_genres_au AFTER UPDATE OF genres_json ON animes
WHEN COALESCE(old.genres_json, '') != COALESCE(new.genres_json, '')
BEGIN
  DELETE FROM anime_genres WHERE anime_id = old.id;
  INSERT OR IGNORE INTO anime_genres(genre, anime_id)
  SELECT LOWER(TRIM(value)), new.id
  FROM json_each(
    CASE
      WHEN new.genres_json IS NOT NULL
        AND json_valid(new.genres_json)
        AND json_type(new.genres_json) = 'array'
      THEN new.genres_json
      ELSE '[]'
    END
  )
  WHERE type = 'text' AND TRIM(value) != '';
END;

DROP TRIGGER IF EXISTS animes_genres_ad;
CREATE TRIGGER animes_genres_ad AFTER DELETE ON animes
BEGIN
  DELETE FROM anime_genres WHERE anime_id = old.id;
END;

-- 3. Idempotent backfill of existing catalog rows into anime_genres
DELETE FROM anime_genres;
INSERT OR IGNORE INTO anime_genres(genre, anime_id)
SELECT LOWER(TRIM(j.value)), a.id
FROM animes a,
     json_each(
       CASE
         WHEN a.genres_json IS NOT NULL
           AND json_valid(a.genres_json)
           AND json_type(a.genres_json) = 'array'
         THEN a.genres_json
         ELSE '[]'
       END
     ) j
WHERE j.type = 'text' AND TRIM(j.value) != '';

-- 4. Drop duplicate popularity index (duplicates idx_animes_popularity from 0004)
DROP INDEX IF EXISTS idx_animes_pop;
