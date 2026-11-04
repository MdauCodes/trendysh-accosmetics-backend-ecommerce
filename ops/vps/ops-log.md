# Ops log: NBH on the Contabo server 158.220.99.163

Every change made on the server for Nairobi Beauty Hub, newest last. No secrets are written here (they live in root-only files on the server).
The server is shared with the Moments Packaging projects: only additions, nothing of theirs is touched, the proxy is never restarted.

## 26 Oct 2026

**08:39 (EAT) read-only pre-flight.** 24 GB RAM (2.4 GB used), 276 GB disk free, Docker 29.9 / Compose 5.6, `coolify-proxy` healthy with 0 restarts,
containers: the Coolify set, `moments-core-backend`, `moments-core-db`, `moments-store-db`. No `/opt/nbh`, no `nbh_*` network. DNS for `img.` and
`s3.trendyshaccosmetic.com` did not exist yet.

**08:40 photo stack installed** in `/opt/nbh/media` (`docker-compose.yml`, `install.sh`, `nginx/`), Compose project `nbh-media`:
- network `nbh_media` (private); only `nbh-edge` also joins Coolify's `coolify` network
- `nbh-garage` (dxflrs/garage:v2.5.0; S3 store, SQLite metadata, fsync on, a metadata snapshot every 6 h; 1 GB RAM / 1 CPU limit)
- `nbh-imgproxy` (darthsim/imgproxy:v4.0.19; presets only: `o` = original, `w64..w1600` = WebP at that width; read-only key; 2 GB / 3 CPU limit)
- `nbh-edge` (nginx:1.27-alpine; disk cache up to 10 GB in `/opt/nbh/media/data/cache`; 512 MB / 1 CPU limit)
- data in `/opt/nbh/media/data/{meta,blocks,cache}`; secrets in `/opt/nbh/media/.env`, `garage.toml`, `imgproxy.env` (all mode 600)
- Garage made the bucket `nbh-media` and a read/write key (in `.env`); `install.sh` made a read-only key `nbh-imgproxy-ro` for imgproxy
- the edge is NOT announced to the proxy (`EDGE_PUBLIC=false` in `.env`): no Traefik labels, no certificate requests, nothing reachable from outside

**08:42 to 08:47 smoke tests** (`scripts/smoke.sh`): one real PNG (2.2 MB) and one real JPG (44 KB) copied from Cloudinary into the bucket through the
`s3` host (nginx to Garage), read back through the `img` host: byte-identical (sha256 equal). WebP copies work (PNG 2.2 MB to 1.5 / 9.8 / 52 KB at 64 / 320 / 960 px),
MISS then HIT in the cache, a width not in the list, a missing photo and another host name all answer 404. Test objects deleted afterwards.
Memory use with traffic: garage 7 MB, imgproxy 30 MB, edge 8 MB.

Found and fixed while testing: (1) two `Cache-Control` headers (imgproxy's and ours) and ours on errors too: now one, on successful answers only;
(2) a single-file bind mount keeps pointing at the old file after the file is replaced on the host, so the nginx configuration folder is now mounted as a whole
(`/opt/nbh/media/nginx` at `/etc/nginx/nbh`, started with `-c /etc/nginx/nbh/nginx.conf`); edge recreated and its (test-only) cache emptied.
Images pulled on the server: dxflrs/garage:v2.5.0, darthsim/imgproxy:v4.0.19, nginx:1.27-alpine, rclone/rclone:1.69.3 (smoke test only).

**08:57 to 09:07 verified copy of the shop's photos from Cloudinary** (`copy/crawl-photo-urls.mjs` on this PC lists the Cloudinary addresses the PUBLIC shop API shows:
948 variants, their gallery pages, categories, the 3 packages = 2,311 distinct addresses, 730 JPG under `trendysh/products/`, 1,581 PNG under `products/`,
1 under `kits/`; then `copy/copy_photos.py`, run on the server by `copy/run-copy.sh` in a throwaway container `python:3.12-slim` with 512 MB / 2 CPU limits on the private network).
Cloudinary is only read (GET/HEAD); nothing there was written or deleted. Per photo: size equals what Cloudinary announced, the bytes are an image, stored under the same
path (`<folder>/<name>.<ext>`, the `v123` version segment dropped), the store's checksum equals ours, read back and sha256 equal; one line per photo in `copy/run1/mapping.jsonl`
(a copy is kept on this PC). Result: 2,311 photos, 2.19 GB, 0 failed, 232 s. Re-run skips everything already there. Then a deep check that re-reads all 2,311 objects and
compares sha256 (0 problems) and a decode check that asks imgproxy for a 64 px WebP of every photo (0 problems). The bucket holds 2,311 objects / 2.2 GB; disk 18 GB of 290 GB used.
Not covered by this list (the public API does not show them, the backend job must): image drafts, old orders' picture snapshots, hidden/inactive/deleted products, brand logos, profession pictures.

**26 Oct, midday: ON HOLD.** The owner asked to hold the move to the VPS and verify and test the rename/moves release first. Nothing more
was changed on the server after 09:07; the photo stack runs idle (not public, about 45 MB of memory).

### To undo all of it
`cd /opt/nbh/media && docker compose down` (the photos stay in `data/` until deleted by hand), then `docker network rm nbh_media` if it is left, then `rm -rf /opt/nbh`.

### Still to do on the server (26 Oct list; items 1 to 3 done 1 Nov)
1. The owner adds DNS A records `img` and `s3` (trendyshaccosmetic.com) to 158.220.99.163 at Namecheap (Advanced DNS). Not there yet (checked 26 Oct 10:00).
2. Set `EDGE_PUBLIC=true` in `/opt/nbh/media/.env`, `docker compose --env-file .env up -d edge`: Traefik then gets the two certificates (HTTP challenge).
3. Check https from outside (certificate, CORS, headers).
4. ~~Copy the photos from Cloudinary~~ done (above); what the public API does not show is for the backend job. Then an offsite backup of the bucket
   before Cloudinary is ever emptied (the owner decides where, later).

## 1 Nov 2026 (step 0 of the move: photos served from the server, Cloudinary as fallback; owner approved in chat)

The server's files had changed since 26 Oct (a Moments session, 29 Oct): `img.momentspackaging.com` is served by this same edge from a
`moments` bucket, imgproxy reads full `s3://` addresses (`IMGPROXY_BASE_URL` empty, `ALLOWED_SOURCES` lists both buckets), and `nbh-garage`
also joins the `coolify` network as `media-s3`. **The server is the source of truth: pull its files before changing them.** This repo's
`media/` now holds the server's copies (pulled 1 Nov) plus today's changes.

**16:09 EAT read-only look.** All containers healthy, proxy 0 restarts, bucket 2,311 objects, `EDGE_PUBLIC=false`. More projects run here now
(steve-richard-safaris-web and several Coolify apps).

**16:10 to 16:14 copy refresh** (`copy/run2`): the 2,692 addresses the public shop API shows today, run on the server as on 26 Oct (Cloudinary
GET/HEAD only): 410 copied, 2,282 already there, 0 failed, 2,622 MB, 220 s. Decode check of all 2,692 through imgproxy: 0 problems.

**16:15 imgproxy presets** w1080, w1440, w1920 added (the banners ask for them); backup `imgproxy.env.bak-20261101`; only `nbh-imgproxy` was
recreated (`--no-deps`). install.sh's WIDTHS updated to match.

**16:16:50 to 16:19:31 MISTAKE, put right.** The repo's (stale) nginx.conf was copied over the server's: the Moments host block was gone and the
NBH paths lacked `s3://nbh-media/`. 5 Moments photo requests (one page view, 16:19:02) got a 404 before the server's own file was put back
from `nginx.bak-20261101/` and reloaded; all five checked 200 afterwards, also publicly through Cloudflare. Note: reload is
`nginx -c /etc/nginx/nbh/nginx.conf -s reload` (without `-c` nginx looks for the wrong pid file and does nothing).

**16:25 Cloudinary fallback on the edge** (NBH host only; the Moments block is byte-identical): a photo not in the bucket is a 302 to the same
photo on Cloudinary (`w_<n>,c_limit,q_auto,f_auto` for a width, the original for `/o/`), `Cache-Control: no-store`; a width without a preset
is a plain 404. Tested first in a throwaway container `nbh-edge-trial` (both hosts, MISS/HIT, missing photo, bad width), then put live
(previous file kept as `nginx.bak-20261101/nginx.conf.before-fallback`) and checked again on both hosts.

### Undo today's changes
`cp -p nginx.bak-20261101/nginx.conf.before-fallback nginx/nginx.conf` + reload; `cp -p imgproxy.env.bak-20261101 imgproxy.env` +
`docker compose --env-file .env up -d --force-recreate --no-deps imgproxy`. The extra 410 photos in the bucket do no harm.

**21:17 EAT made public.** The owner added the DNS A records `img` and `s3` -> 158.220.99.163 at Namecheap (TTL 1 min); seen by Namecheap's,
Google's and Cloudflare's resolvers. `EDGE_PUBLIC=true` in `.env` (backup `.env.bak-20261101`), `docker compose --env-file .env up -d --no-deps edge`
(only `nbh-edge` recreated, healthy after 10 s; img.momentspackaging.com answered 200 right after). Traefik got Let's Encrypt certificates for
both names (valid to 30 Jan 2027). From outside: a photo is a WebP with `Cache-Control: public, max-age=31536000, immutable`, MISS then HIT;
a missing photo is a 302 to Cloudinary; an unknown width 404; http redirects to https; anonymous S3 listing 403.
To take it off the internet again: `EDGE_PUBLIC=false` in `.env` and the same `up -d --no-deps edge` (the frontend then falls back to Cloudinary per photo).

**22:30 EAT bucket keys (owner approved in chat).** Garage key `nbh-backend` (read/write on `nbh-media` only) for the backend's photo
storage, and a test bucket `nbh-test` with its own key `nbh-backend-test` (read/write on `nbh-test` only) for end-to-end tests that must
never touch the shop's photos. Both secrets are only in `/opt/nbh/keys/*.env` (mode 600, root). The owner reads the backend key with
`cat /opt/nbh/keys/nbh-backend.env` over SSH and pastes it into Admin > Settings > Integrations > Photo storage.
To take them away: `docker exec nbh-garage /garage key delete --yes <name>`; `docker exec nbh-garage /garage bucket delete --yes nbh-test`.

## 2 Nov 2026 (backend and database, owner: "we are going to do it right now")

**00:58 to 01:41 EAT rehearsal.** The owner enabled Coolify's API and left a token in `/opt/nbh/keys/coolify-api-token` (root only; never printed).
Coolify project `Trendysh Accosmetic` (pn3vcmppklgq0qnp3r80a4xn), database `trendysh-db` (bvhyc8vznoob4roxtw1npyyf, postgres:18-alpine, 2g, not public),
application `trendysh-backend` (hfebfbsrgppsitkqaptcqoyi, branch main of lezisign-star/trendysh-accosmetics-backend-ecommerce through the GitHub app
moments-coolify, 3g, https://api.trendyshaccosmetic.com, auto-deploy off). Dump of Railway (Postgres 18.6, migration 88) in `/opt/nbh/backend/`, restored
with `--rehearsal` (5 saved keys removed from the copy), rehearsal-only environment: random JWT and secrets keys, no mail, payment, AI or photo keys.
Built commit 227bafe, healthy, 0 errors in the log, 1.3 GB of 3 GB. Public catalogue, categories, packages, banners and shop mode byte-for-byte the same size as Railway's,
same first product and total (1,044). Moments Core and VunjaBei answered 200 throughout. Railway untouched and still the live backend.

**02:02 to 04:10 EAT the switch.** The owner stopped Railway's backend (Postgres left running) and copied Railway's variables into Coolify; `DATABASE_URL`, `POSTGRES_*` and `MPESA_*` were taken out again (`DATABASE_URL` would have pointed the new backend at Railway's database) and the new `DB_*` and `PORT` put back. Final dump with no other connection to Railway's database, restored without `--rehearsal`: all 77 tables equal in row count. Deployed 227bafe: healthy, 0 errors, 1.1 GB. The owner set `VITE_API_URL=https://api.trendyshaccosmetic.com/api/v1` on the Render sites and rebuilt them; signed in on trendyaccosmetics.site, Integrations shows the saved keys (so `JWT_SECRET` is Railway's) and a test upload reached the bucket from the new backend.
Backups: nightly 03:00 EAT to the existing `backblaze-b2` storage (bucket `moments-db-backups-pkihara`, shared with other projects), 7 local, 30 days in the cloud. First run 03:00 on 2 Nov: 1.87 MB, Coolify records it uploaded. Restored the local copy in a throwaway `postgres:18-alpine`: 77 tables, row counts equal to the live database. The copy in B2 itself was not downloaded.
Removed from the server: the dumps, the restore script and the Coolify token file. The owner's dumps stay on the PC in `~/trendysh-db-dumps`.

### Still for the owner
1. Change or remove Railway's database password (it was pasted in a chat) and, after the rollback window, shut Railway down.
2. Coolify: delete the token `trendysh-deployment-api-token` and switch API access off.
3. `gmcrimsoncutsltd.com` is in `CORS_ALLOWED_ORIGINS`, copied from Railway: remove it unless it is needed.
4. Later: the unused test bucket `nbh-test` and key `nbh-backend-test`; an offsite copy of the photo bucket with a restore test, then the Cloudinary keys; clearing the edge cache when a photo is deleted.

**Deploys:** auto-deploy is off. A push to `main` reaches the live backend only after Deploy on `trendysh-backend` in Coolify.

**2 Nov, 08:10 to 08:15 EAT backend deploy (owner's word in chat, by SSH).** `trendysh-backend` deployed at `3f94abc`, started from inside Coolify with `queue_application_deployment` because the API token had been deleted. Flyway applied V89 (more Metros). Healthy, 0 restarts. Copy of the database from just before it: `/opt/nbh/predeploy/trendysh-before-3f94abc.dump` (root only, customer data; delete once no longer needed). The repo has moved to `MdauCodes/...`; Coolify's address still reads `lezisign-star/...` and the fetch worked through GitHub's redirect.

**2 Nov, 08:45 EAT automatic switch to online ordering (owner's word in chat).** New `OnlineOrderingLaunch` job, deployed at the commit below: at 12:00 EAT it sets the ordering switch to FULL, once, only if nobody has ever flipped it (`changed_by` still empty). Flipping the switch by hand before noon (even back to WHATSAPP) cancels it, and later settings are left alone.

**2 Nov, 12:15 EAT delivery terms deploy (owner's word in chat).** V90 adds how an agreed delivery fee gets paid, and replaces the TumaBoda-only fee rule with "not negative". The automatic switch to online ordering ran at 12:00:00 EAT as planned. Copies from before today's deploys are in `/opt/nbh/predeploy/` (root only, customer data; delete when no longer needed).

**2 Nov, 15:25 EAT delivery priced by provider and place (owner's word in chat, after tests).** V91 adds 583 estimated place prices across riders, Metros, buses and couriers, charged with the order; staff correct them in Settings > Delivery options. Sources and limits are in the migration's header. It was first applied to a throwaway copy of the live data on this server. Also: payer's name instead of an M-PESA code, next-day received list. Copy from before: `/opt/nbh/predeploy/trendysh-before-priced-delivery.dump`.

**3 Nov, 05:00 EAT more delivery places, and the test order clear tool.** V93 adds estimated place prices across all providers (about 2,090 active in all), tried first on a throwaway copy of the live data. New developer-only tool in Settings > Demo data clears orders from before 04:40 EAT on 3 Nov, pressed by the owner, never by an assistant. Copy from before: `/opt/nbh/predeploy/trendysh-before-more-places.dump`. Remove the tool once it has been used.

**3 Nov test orders cleared and the tool removed.** The owner pressed the clear tool; its code is gone from the backend and dashboard. Copy from before: `/opt/nbh/predeploy/trendysh-before-more-places.dump` (customer data).

**3 Nov live order notices and sourced delivery lists.** New order changes (delivery fee, transporter, expiry, returns, a turned-down payment) now push a live notice. V94 hides far towns under riders and Metro stops not named in that Metro's own entry; V95 hides guessed bus routes and adds regional providers from public sources. All tried first on a throwaway copy of the live data. Copy from before: `/opt/nbh/predeploy/trendysh-before-live-updates.dump`.

**4 Nov, 10:03 to 10:08 EAT campaigns deploy (owner's word in chat: "Yes, deploy and stage").** `trendysh-backend` deployed at `1b6e117`, started from inside Coolify with `queue_application_deployment` (deployment `vvqncx1gl32dxfw6rqctz465`). V96 adds campaign deals under builder banners, free basket lines for buy-X-get-Y, the deal on order lines, the wholesale on/off switch (on by default) and the owner's photos and poses. All additive; first applied to a throwaway copy of the live data on this server (7 banners intact), the copy removed after. Healthy, 0 restarts, no errors in the log. Copy from before: `/opt/nbh/predeploy/trendysh-before-campaigns.dump` (root only, customer data).

**4 Nov, 11:00 EAT owner photo uploads fix (owner's word in chat: "I allowed it, deploy").** `trendysh-backend` deployed at `b5eb126`, started from inside Coolify with `queue_application_deployment` (deployment `kgtbmf8fiq0l8qwfuo8lrd9s`). `owner-photos` was missing from the allowed upload folders, so every upload on Owner poses got a 422. No database change. Copy from before: `/opt/nbh/predeploy/trendysh-before-owner-photos.dump` (root only, customer data; delete once no longer needed).

**4 Nov, 12:06 to 12:14 EAT owner pose variants and the banner's own pose (owner's word in chat: "I allowed it, deploy").** `trendysh-backend` deployed at `a1d47b0`, started from inside Coolify with `queue_application_deployment` (deployment `y3rd2xvalbpx3targeooi190`); frontend `95ae1cb` on main, built by Render. Each picture of a request takes a different direction, the shop's note replaces the mood's pose, poses use `gemini-3-pro-image` at 2K (`GEMINI_POSE_MODEL` overrides), a wrong backdrop is retried once, and a home banner with a pose needs no separate picture. No database change. Healthy, 0 restarts, no errors in the log at start. Copy from before: `/opt/nbh/predeploy/trendysh-before-pose-variants.dump` (root only, customer data; delete once no longer needed).
