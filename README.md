# milkglassX

Monorepo lintas 3 plane untuk aplikasi streaming anime **Milkglass**:

| Plane | Path Aktif | Stack | Peran |
|---|---|---|---|
| **Client Android & Engine** | `frontend/app/`, `frontend/engine/` | Kotlin 2.4, Jetpack Compose, Media3, Gradle 9.9, AGP 9.5 | UI Compose, pemutar adaptif, ekstraksi hoster & cascade resolver lokal di perangkat |
| **Backend Cloudflare** | `frontend/milkglass-backend/`, `frontend/scripts/` | Cloudflare Workers, Hono 4, D1, Zod 4, TypeScript 6, Node.js | Katalog D1-first, auth/session proof, edge cache, konfigurasi sumber dinamis, & sinkronisasi katalog/hoster |
| **Asal Ekstrak (`cf-handler`)** | `cf-handler/` | Python 3.14, FastAPI, DrissionPage, `curl_cffi` | Penyelesaian tantangan Cloudflare, mirror request, dan split-plane resolver (`/resolve`, `/search/animasu`) |

## Prioritas Sumber Kanonik (Embed-First)

Seluruh plane menggunakan urutan default yang sama (dapat dioverride secara dinamis lewat `app_config.source_cascade_order` di D1):

`samehadaku` -> `oploverz` -> `nontonanimeid` -> `otakudesu` -> `animasu` -> `kusonime`

## Catatan Manajemen Proyek
* **Arsip snapshot lama**: Snapshot historis `backend-cloudflare/` telah diarsipkan pada branch `archive/backend-cloudflare-snapshot` dan tidak lagi dirujuk oleh workflow CI/CD.
* **Keystore rilis Android**: `frontend/app/milkglass.keystore` digunakan oleh `frontend/app/build.gradle.kts` untuk konsistensi tanda tangan pemasangan/pembaruan APK. Jangan diganti tanpa rencana migrasi tanda tangan aplikasi.
* **CI/CD**: Seluruh PR diverifikasi otomatis oleh `.github/workflows/verify-milkglass.yml` (`backend-typecheck`, `cf-handler-unit-tests`, dan `android-compile-and-test`).
