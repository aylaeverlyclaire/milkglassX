-- Source-first catalog binding: simpan URL series langsung per provider agar player bypass pencarian judul saat Play.
ALTER TABLE animes ADD COLUMN source_urls_json TEXT;
