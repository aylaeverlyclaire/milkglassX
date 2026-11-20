-- Multilingual search fields (Japanese native title, synonyms/aliases, space-insensitive compact key)
ALTER TABLE animes ADD COLUMN title_native TEXT;
ALTER TABLE animes ADD COLUMN synonyms_json TEXT;
ALTER TABLE animes ADD COLUMN norm_compact TEXT;

CREATE INDEX IF NOT EXISTS idx_animes_norm_compact ON animes(norm_compact);
