#!/usr/bin/env bash
# Adds the 2560 and 3840 wide copies to the live photo server (owner, 6 Nov 2026: banner pictures are made at 4K, and a phone
# draws a wide picture about three and a half screens across, so it needs a copy wider than 1920 to stay sharp).
# install.sh writes imgproxy.env only on a first install, so a server that already runs needs this once. Safe to run again.
# Run as root on the server, from /opt/nbh/media, after copying the updated nginx/nginx.conf there.
set -euo pipefail
BASE=/opt/nbh/media
cd "$BASE"
grep -q '2560|3840' nginx/nginx.conf || { echo "copy the updated nginx/nginx.conf to $BASE/nginx first"; exit 1; }

for w in 2560 3840; do
  if ! grep -q "w$w=" imgproxy.env; then
    sed -i "s/^IMGPROXY_PRESETS=.*/&,w$w=rs:fit:$w:0/" imgproxy.env
    echo "added preset w$w"
  fi
done

# imgproxy reads its settings at start; nginx takes the new address list on a reload after a check.
docker compose up -d --force-recreate imgproxy
docker exec nbh-edge nginx -t -c /etc/nginx/nbh/nginx.conf
docker exec nbh-edge nginx -c /etc/nginx/nbh/nginx.conf -s reload
echo "done: /w2560/ and /w3840/ copies are served"
