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

**4 Nov, 13:03 to 13:10 EAT cut-out poses and arranged banners (owner's word in chat: "Yes, deploy").** `trendysh-backend` deployed at `1af7208`, started from inside Coolify with `queue_application_deployment` (deployment `s6gj8lygj6junhvdrl3wt3pm`); frontend `6c0d6eb` on main, built by Render. V97 adds `owner_pose.cutout_url` and `promo_banner.arrangement`, both empty to start; applied at start-up, not tried on a copy first (additive). New poses are made on a green screen (`PoseCutout`) and stored twice, as made and cut out. Healthy, 0 restarts, no errors in the log at start. Copy from before: `/opt/nbh/predeploy/trendysh-before-cutouts.dump` (root only, customer data; delete once no longer needed).

**4 Nov, 13:46 to 13:55 EAT more pose options and badge looks (owner's word in chat: "Yes, deploy").** `trendysh-backend` deployed at `1c7ee7e`, started from inside Coolify with `queue_application_deployment` (deployment `vgq1a6juoo2mu09jpd2w5udf`); frontend on main, built by Render. No database change. Also: the public shop's pose data no longer carries staff names, prompts or the green-screen original (`OwnerPoseService.forShop`). Healthy, 0 restarts, no errors in the log at start. Copy from before: `/opt/nbh/predeploy/trendysh-before-pose-options.dump` (root only, customer data; delete once no longer needed).

**4 Nov, 21:01 EAT delete for old site-wide campaigns (owner's word in chat: "Yes, deploy").** `trendysh-backend` deployed at `e45eb1b`, started from inside Coolify with `queue_application_deployment` (deployment `q69bbmfrhb8tdrysv089u7e2`); frontend on main, built by Render. A campaign running now can't be deleted. No database change. Healthy, 0 restarts, no errors in the log at start. Copy from before, which still holds the old campaigns: `/opt/nbh/predeploy/trendysh-before-campaign-delete.dump` (root only, customer data; delete once no longer needed).

**5 Nov director poses and banner text deploys (owner's word in chat).** 14:25 EAT `f0e056b`: staff can upload their own picture as a director pose (approved at once; used as it is when already see-through, cut out when on a green screen, else the banner matches its background). 14:53 EAT `b81364e`: banner headline size up to XL, six fonts, weight and capitals. No migrations. Healthy, 0 restarts, no errors. Copies from before: `/opt/nbh/predeploy/trendysh-before-pose-upload.dump`, `/opt/nbh/predeploy/trendysh-before-banner-type.dump` (root only, customer data).

**5 Nov, 15:21 to 15:26 EAT banner words each on their own and Flash deals (owner's word in chat: "Yes, deploy and ship").** `trendysh-backend` deployed at `9632766`, started from inside Coolify with `queue_application_deployment` (deployment `f46a0e3dvvv0ti69wcltm2j4`); frontend `3b07ad1` on main and `8823aca` on staging, built by Render. A banner's style can now carry `parts` (second line, campaign name, offer, prices), each with its own font, size, weight, capitals and colour; product cards carry `dealSource`, so Flash deals shows only staff flash deals or deals ending within three days. No migrations. Healthy, 0 restarts; the only log lines are browsers leaving early (broken pipe). Copy from before: `/opt/nbh/predeploy/trendysh-before-text-groups.dump` (root only, customer data).

**6 Nov, 01:55 EAT picture optional on most banners (owner's word in chat: "Yes, deploy").** `trendysh-backend` deployed at `6178fd2`, started from inside Coolify with `queue_application_deployment` (deployment `ru71gfdzzl8t7p58972kux8o`); frontend `b36575c` on main, built by Render. Only the offers-row card, grid tile and category circle need a picture to be approved; a campaign with no pose now draws its own picture behind the words. No database change. Copy from before: `/opt/nbh/predeploy/trendysh-before-optional-picture.dump` (root only, customer data; delete once no longer needed).

**6 Nov, 02:30 EAT Merchant Center product feed (owner's word in chat: "Yes, deploy").** `trendysh-backend` deployed at `78f3ad4`, started from inside Coolify with `queue_application_deployment` (deployment `pvqfu3v5qf4fnz4owfgta7t0`). Feed at `https://api.trendyshaccosmetic.com/api/v1/catalog/merchant-feed.xml`: 1,022 items on first fetch (34 options left out for having no photo), 10 with a sale price, built in about 2 s and kept 10 minutes. No database change. Healthy, 0 restarts, no errors. Copy from before: `/opt/nbh/predeploy/trendysh-before-merchant-feed.dump` (root only, customer data; delete once no longer needed).

**6 Nov, 09:14 EAT the picture in use can be removed (owner's word in chat: "Yes, deploy").** `trendysh-backend` deployed at `68db3f9`, started from inside Coolify with `queue_application_deployment` (deployment `qycwnpazsd5xh32uxowumxlv`); frontend `080e32e` on main, built by Render. Removing a banner's picture in use clears it and takes the banner back to a draft. Also earlier today, frontend `0a8fd11`: every name the shop goes by and its Facebook pages in the search data. No database change. Copy from before: `/opt/nbh/predeploy/trendysh-before-picture-remove.dump` (root only, customer data; delete once no longer needed).

**6 Nov, 09:39 EAT banner pictures from the whole banner (owner's word in chat: "Yes, deploy").** `trendysh-backend` deployed at `ee8b118`, started from inside Coolify with `queue_application_deployment` (deployment `vgnrjz8qkf2mgxupumqkhaq9`); frontend on main, built by Render. AI banner pictures now use the deal and featured products' photos and categories, the banner's colour and the picture's place beside the words and cards, five concepts, the staff's whole prompt (up to 1,500 characters, text requests removed), on `GEMINI_POSE_MODEL` at 2K. Banner editor: a background colour picker. No database change. Copy from before: `/opt/nbh/predeploy/trendysh-before-banner-pictures.dump` (root only, customer data; delete once no longer needed).

**6 Nov, 12:39 to 12:44 EAT banner looks for phone and laptop, blended colours (owner's word in chat: "Yes, deploy").** `trendysh-backend` deployed at `eaf497c`, started from inside Coolify with `queue_application_deployment` (deployment `txoay745uwkkn99mgmebs93q`); frontend `2ce375a` (banner editor) and `7df0c87` (Google Ads conversions and cookie choice) on main, built by Render. A banner style can carry a blend of two or three colours and a `phone` section with what phones draw differently and where the picture is kept in view. No database change. Healthy, 0 restarts, no errors. Copy from before: `/opt/nbh/predeploy/trendysh-before-banner-looks.dump` (root only, customer data; delete once no longer needed).

**6 Nov, 14:35 to 14:50 EAT delivery sections, category order, banner pictures at 4K (owner's word in chat: "Yes, do all four").** `trendysh-backend` deployed at `618c7f1`, started from inside Coolify with `queue_application_deployment` (deployment `k7p50jb8exsy8yattxff8wq8`); frontend `042c7f7` on main, built by Render. V98: a delivery option can be in more than one section (`transport_operator.kinds`, `kind` kept as the first) and each place has an area (`transport_stop.area`), filled from where each option was. V99: top-level categories in the owner's order (nails, lashes, makeup, skin care, PMU, DIY, spa packages). V100: `promo_banner_picture.subject_side`. Banner pictures are now asked for at 4K (`GEMINI_BANNER_IMAGE_SIZE`). Healthy, 0 restarts, no errors. Copy from before: `/opt/nbh/predeploy/trendysh-before-delivery-sections.dump` (root only, customer data; delete once no longer needed).

**6 Nov, 14:49 EAT photo server: 2560 and 3840 wide copies (same word).** `add-wide-copies.sh` added the presets to `/opt/nbh/media/imgproxy.env`, recreated `nbh-imgproxy` and reloaded `nbh-edge` with the new `nginx.conf` (only the width list changed). Copies of the files from before: `imgproxy.env.before-wide-copies` and `nginx/nginx.conf.before-wide-copies` in `/opt/nbh/media`. Checked: `/w2560/` and `/w3840/` serve WebP, the old widths unchanged.

**6 Nov, 18:54 to 19:02 EAT checkout towns outside Nairobi (owner's word in chat: "Yes, deploy and push").** `trendysh-backend` deployed at `7b9e199`, started from inside Coolify with `queue_application_deployment` (deployment `l115hde5b627dirxh27x2sxw`); frontend `dd2715d` on main, built by Render. New public `GET /api/v1/public/delivery/area-places?area=OUTSIDE`: every town outside Nairobi with each offered courier and its price, cheapest first (105 towns, 245 options on live). No database change, so no copy taken. Healthy, 0 restarts, no errors. Frontend: checkout asks for the town first outside Nairobi, highlights missing fields, lists every detail on review; the cookie choice no longer names what the cookies are for.

**7 Nov, 01:16 to 01:21 EAT sell regardless of stock (owner's word in chat: "Yes, finish and ship"; "we can ship then test afterwards this one time").** `trendysh-backend` deployed at `2b8f272`, started from inside Coolify with `queue_application_deployment` (deployment `wyhh3lg8mfrmsbcnso7pbjaj`); frontend `151ce80` on main, built by Render. V101 adds `shop_settings.sell_regardless_of_stock` and turns it on: customers see no stock figures or sold out and nothing is refused for stock (ShopperStock); checkout holds stock where the records have it and orders without a hold where they do not; staff keep the real figures. Turned off in Stock > Set up. Checked on live: 1,056 options, none out of stock or showing a small number to customers. Full suite before: 1,214 tests, the one known date-dependent campaign test failing (also on main) and a flaky test of ours fixed. Copy from before: `/opt/nbh/predeploy/trendysh-before-sell-regardless.dump` (root only, customer data; delete once no longer needed).

**8 Nov, 21:00 to 21:50 EAT the website moves from Render to this server (owner's word in chat: "let us take the frontend to contabo coolify server"; DNS changed by the owner at Namecheap).** New Coolify app `trendysh-shop` (`elvhyuodzaer8eae32leslfb`, project Trendysh Accosmetic) builds `MdauCodes/beauty-shop-frontend` main from its `Dockerfile` (nginx serving the prerendered site, rules in `deploy/nginx.conf`), auto-deploy on push. Build variables: `SITE_URL=https://trendyshaccosmetic.com`, `INDEXNOW=true`; the live site is built without `VITE_HONOUR_ORDERING_SWITCH`, as on Render. Domains `trendyshaccosmetic.com` and `www` (www forwards with a 301; `custom_labels` edited for that). Before the switch, 133 sampled addresses were identical to Render's; after it, 163 (the one difference: `/shops` without the slash now gets the Shops page itself). **Outage, my mistakes:** about 25 minutes (from about 21:19) for visitors already sent to the server: an environment variable written with a bulk update skipped Coolify's encryption (deploys failed on DecryptException), and the proxy labels saved at creation still named only the test address (fixed with `generateLabelsApplication`). Lesson: change Coolify env vars through the model or API, and regenerate labels when domains change. Not done yet: the nightly rebuild timer (needs the owner's word), the staff "Rebuild website" button still accepts only Render hooks, Render's `cosmetics-254` and the test site `trendyaccosmetics.site` still run on Render.

**8 Nov, 21:56 EAT nightly website rebuilds (owner's word in chat: "Yes, add it").** Root cron `0 0,4 * * *` runs `/opt/nbh/rebuild-trendysh-shop.sh` (log `/var/log/trendysh-shop-rebuild.log`): queues a Coolify deployment of `trendysh-shop` with `force_rebuild:true`, since a plain redeploy of an unchanged commit reuses the image and would not read the catalogue again. Tried once at 21:56: built in about 2 minutes, pages stamped 21:59, the site answered 200 throughout.

**9 Nov, 07:10 to 07:25 EAT the website rebuild button and midnight rebuild call Coolify (owner's word in chat: "You create it" for the key, "Yes, deploy").** Coolify API key `trendysh-backend-site-rebuild` (ability `deploy` only; listing apps answers 403), held in the backend's runtime variables `STOREFRONT_COOLIFY_DEPLOY_URL` (`http://coolify:8080/api/v1/deploy?uuid=elvhyuodzaer8eae32leslfb&force=true`) and `STOREFRONT_COOLIFY_TOKEN`; set through the model, so encrypted. `trendysh-backend` deployed at `53b3eed` (deployment `nbo334w1w537pgdb82d1cjid`), then at `b7e33e8` (deployment `vxxa4nuoul598k2lfaahre2g`) because Coolify 4.6 answers the deploy API only to POST (405 to GET). Checked from inside the backend container with its own variables: a POST queued a website build. Frontend `0bd5019`: the integrations screen says the site rebuilds on this server. Root cron now `0 4 * * *` only (the backend does midnight). No database change. Healthy, no errors. Copy from before: `/opt/nbh/predeploy/trendysh-before-site-rebuild-coolify.dump` (root only, customer data; delete once no longer needed).

**9 Nov the test site moves from Render to this server (owner's word in chat: "move this too"; DNS changed by the owner at Namecheap).** New Coolify app `trendysh-test-site` (`tpmrkatnprxixi6adzr8jwwf`) builds the frontend's `staging` branch (hosting files added there in `4850a02`), auto-deploy on push, no build variables (the build's defaults make it the unlisted test copy). Before the switch, 113 sampled addresses matched Render's test copy. Domains `trendyaccosmetics.site` and `www` (www forwards with a 301; proxy labels regenerated with the domains this time, so no gap). Live at about 11:10 EAT with a Let's Encrypt certificate; every page still sends visitors on to the same page of trendyshaccosmetic.com (owner's choice, 3 Nov). Render's `staging-trendysh-accosmetic` and `cosmetics-254` can now be stopped.

**9 Nov, 13:28 to 13:33 EAT where shoppers come from (owner's word in chat: "work on this", then "Yes, deploy").** `trendysh-backend` deployed at `36390ed`, started from inside Coolify with `queue_application_deployment` (deployment `mbfnbqfsitrsqpfxu6q548up`). V102 adds `site_visit`, `site_event`, `ad_spend` and the source columns on `customer_order` (applied in 0.2 s). New public `POST /api/v1/public/visits` (bots and floods dropped), staff `GET /admin/analytics/sources` and `/spend`, `PUT /spend` (Settings permission). Merchant feed now carries `custom_label_0` deal/regular and `custom_label_1` price band, and fills empty descriptions from the category. Frontend `dac48dd` on main, built by Coolify (`trendysh-shop`). Healthy, migration logged, no errors. Copy from before: `/opt/nbh/predeploy/trendysh-before-traffic-sources.dump` (root only, customer data; delete once no longer needed).
