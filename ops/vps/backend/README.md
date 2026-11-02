# Moving the backend and database from Railway to the Contabo server

**Done 2 Nov 2026: the live backend runs here (see ../ops-log.md); Railway is stopped, not deleted.** This is how it was done, and how to do it again.

**Names:** project `Trendysh Accosmetic`, environment `production`, database `trendysh-db` (database name `trendysh`), application
`trendysh-backend`, address `https://api.trendyshaccosmetic.com`.

## A copy is not harmless

Started beside the live backend, a copy runs every scheduled job: order expiry and payment reminders, TumaBoda booking retries and
reconciliation, stock digests, AI banner, deal and variant jobs, and the midnight site rebuild. So:
- **Until cutover the copy starts only with the rehearsal environment below**, on a database restored with `--rehearsal`.
- Never test deleting photos on the copy with real keys: it shares the real bucket.
- Railway stays the only live backend until the cutover step.

## 1. Set up (nothing live changes)

1. DNS at Namecheap: A record `api` to `158.220.99.163`, TTL 1 min.
2. Coolify: new project `Trendysh Accosmetic`.
3. In it, a PostgreSQL 18 database: image `postgres:18-alpine`, name `trendysh-db`, initial database `trendysh`, memory limit `2g`, port **not** public.
   Start it. Scheduled backups to the existing `backblaze-b2` storage, once the owner agrees it may hold this shop's data.
4. In it, an application from the repo `lezisign-star/trendysh-accosmetics-backend-ecommerce`, branch `main`, build pack Dockerfile, port `8081`,
   domain `https://api.trendyshaccosmetic.com`, memory limit `3g` (the Dockerfile's JVM takes 75% of what it sees; without a limit that is about 17 GB),
   health check `/actuator/health`, auto-deploy off. **Do not deploy yet.**
   Coolify's GitHub connection is on the MdauCodes side and this repo is under another account, so it needs that account's access (install the GitHub app there, or a deploy key).

## 2. Rehearsal

1. Owner on the PC: `bash ops/vps/backend/dump-railway.sh`. It reads the server version too; the target must be that major version or newer.
2. Send the dump to the server (`/opt/nbh/backend/`, root only) and run `restore-dump.sh <db container> <dump> --rehearsal`.
3. Environment: paste Railway's raw variables into Coolify's developer view yourself (they never go through a chat), then change as in the table.
4. Deploy, check `/actuator/health`, the Flyway log, `/api/v1/catalog/variants`. Point a local frontend at it (`VITE_API_URL`) and run the flows. Time everything.

| Variable | Rehearsal | Cutover |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://<db container>:5432/trendysh` | same |
| `DB_USERNAME`, `DB_PASSWORD` | from the Coolify database | same |
| `JWT_SECRET`, `APP_SECRETS_KEY` | **same as Railway**: they decrypt the keys saved in the database | same |
| `BREVO_API_KEY`, `MPESA_*`, `COOP_INS_*`, `APP_TUMABODA_*`, `STOREFRONT_DEPLOY_HOOK_URL`, `GEMINI_API_KEY`, `CLOUDINARY_*`, `PHOTO_S3_*`, `PHOTO_STORAGE` | **remove** | as on Railway |
| `MPESA_CALLBACK_URL` | remove | the new `api.` address |
| `CORS_ALLOWED_ORIGINS`, `FRONTEND_URL` | add `http://localhost:5174` for the test | as on Railway |

## 3. Cutover (a quiet hour; the owner's word)

1. Shop to WhatsApp-only ordering; tell staff; stop Railway's backend so no order lands in the old database.
2. Fresh dump. Delete and recreate `trendysh-db`, restore **without** `--rehearsal`. Compare row counts with Railway.
3. Set the Cutover column, deploy, check health, the Flyway log and that Integrations shows the saved keys.
4. **Outside services still call Railway's address:** M-Pesa callback, Co-op Bank INS, TumaBoda webhook. Change each one.
5. Render: `VITE_API_URL=https://api.trendyshaccosmetic.com/api/v1` on both sites, rebuild.
6. Check orders, the dashboard, live updates, an email, a photo upload, an AI photo.

**Way back:** point `VITE_API_URL` and the outside services at Railway again and start it. Orders taken on the server in between must be copied over by hand, so decide quickly.

## 4. After the rollback window (owner's word)

Keep the last dump, shut Railway down. Separately: restore test of the Coolify backups, offsite copy of the photo bucket, and only then remove the Cloudinary keys.
