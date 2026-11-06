# Where shoppers come from, and what they buy: the sources dashboard

Owner's question (6 Nov 2026): how much traffic and revenue comes from each of the socials, AI assistants, Google Ads and
organic search, in a dashboard. Today the shop records none of it: Admin > Analytics shows paid revenue, orders and stock;
no visit and no order says where the shopper came from. The only outside tag is the Google Ads one (conversions only).

Status: planned, not built. Needs the owner's go-ahead (it is a database change: back up first).

## 1. What a visit is, and what it records

A **visit** starts when a browser opens the shop with no visit in the last 30 minutes. On its first page the shop works out
where it came from and sends one small record to the backend:

| Field | Example | Notes |
|---|---|---|
| `visitor` | random id | Made on the shopper's browser and kept there (localStorage). Not a cookie, not personal, never sent to Google or anyone else. A new browser is a new visitor. |
| `channel` | `GOOGLE_ADS` | One of the channels below. |
| `source` | `instagram` | The site or tag (`utm_source`), tidied. |
| `campaign` | `simara` | `utm_campaign`, or the Google Ads campaign id. |
| `landing` | `/shop/lashes/` | The first page, path only (no query string, so nothing personal leaks in). |
| `device` | `phone` | Phone or laptop, from the screen width. |
| `started_at` | | |

Bots are not counted: requests whose user agent says bot, crawler, spider or preview (WhatsApp and Facebook link previews
fetch pages too) are dropped by the backend.

## 2. Telling the channels apart (first page of the visit, first rule that matches)

| # | Channel | Rule |
|---|---|---|
| 1 | **Google Ads** | `gclid`, `gbraid` or `wbraid` in the address, or `utm_source=google` with `utm_medium` = `cpc`/`paid`. |
| 2 | **ChatGPT ads** | `utm_source=chatgpt` with `utm_medium=paid` (we put these tags on the ad's link in Ads Manager). |
| 3 | **Other paid** | Any `utm_medium` of `cpc`, `paid`, `ads`. |
| 4 | **AI assistants** | `utm_source=chatgpt.com` (ChatGPT adds it to the links in its answers), or came from `chatgpt.com`, `chat.openai.com`, `perplexity.ai`, `gemini.google.com`, `copilot.microsoft.com`, `claude.ai`, `meta.ai`. Source = which one. |
| 5 | **Socials** | `utm_medium=social`, or came from `instagram.com`, `l.instagram.com`, `facebook.com`, `m.facebook.com`, `lm.facebook.com`, `tiktok.com`, `x.com`, `t.co`, `youtube.com`, `pinterest.com`. Source = the network; campaign = the shop (see 3). |
| 6 | **WhatsApp** | `utm_source=whatsapp`, or came from `wa.me`/`whatsapp.com` (rare: WhatsApp usually hides it, so the shop's own shared links carry the tag). |
| 7 | **Google Business Profile** | `utm_source=gbp` (the tag on each shop's website link in its profile). |
| 8 | **Organic search** | Came from `google.*`, `bing.com`, `duckduckgo.com`, `yahoo.com`, `yandex.*`, `ecosia.org`, with none of the above. |
| 9 | **Other sites** | Came from any other site. Source = that site. |
| 10 | **Direct / unknown** | Nothing to go on: typed, a bookmark, or an app that hides where it came from. |

The rules live in one small file in the frontend (`lib/trafficSource.ts`) with tests for every row, so a new AI assistant or
network is one line.

## 3. Tagged links the owner puts in each place (once)

Without tags, TikTok, WhatsApp and some Instagram taps arrive as "direct", and nothing tells the three shops apart.

| Where | Link |
|---|---|
| Instagram bio, Superior | `https://trendyshaccosmetic.com/?utm_source=instagram&utm_medium=social&utm_campaign=superior` |
| Instagram bio, Simara | `…?utm_source=instagram&utm_medium=social&utm_campaign=simara` |
| Instagram bio, Junction | `…?utm_source=instagram&utm_medium=social&utm_campaign=junction` |
| TikTok bios (3) | the same with `utm_source=tiktok` |
| Facebook page(s) | `utm_source=facebook` |
| Google Business Profiles (3) | `utm_source=gbp&utm_medium=organic&utm_campaign=<shop>` |
| WhatsApp status and broadcasts | `utm_source=whatsapp&utm_medium=social&utm_campaign=<what it is>` |
| ChatGPT ads | `utm_source=chatgpt&utm_medium=paid&utm_campaign=<ad group>` |
| Google Ads | nothing: auto-tagging adds `gclid` (check it is on in the account) |

The dashboard has a "Make a link" box that builds these for any page, so staff never type them by hand. The shop strips the
tags from the address bar after reading them (history.replaceState), so they are not copied into shares.

## 4. Tying sales to the source

- The visit's id travels with the shopper's basket (the cart session gets `visit_id`), and an order placed from that basket
  stores two things: **first touch** (the channel of the visitor's first ever visit) and **last touch** (the visit the order
  was placed in). Default view: last touch, with a switch to first touch. Both are needed: a shopper who found the shop on
  TikTok and came back from Google to buy counts for TikTok on first touch, Google on last touch.
- **Revenue** counts only paid orders, as the Analytics page does today (delivery fee left out: it goes to whoever delivers).
- **WhatsApp baskets** ("Send order on WhatsApp", while the shop takes orders there) are recorded as events with their value
  and source, shown as their own column, since they are orders the shop takes by hand.
- An order entered by staff (phone, WhatsApp, walk-in) has no visit: it shows as "Staff entry", so the totals still add up to
  the shop's revenue.

## 5. Database (one migration, additive)

- `site_visit` (id, visitor, channel, source, campaign, landing, device, started_at). Indexed on started_at and channel.
  About 300 bytes a visit: 100,000 visits is 30 MB. Kept 13 months, then removed by a nightly job (the order keeps its
  channel for good).
- `site_event` (visit_id, kind, value, at): `add_to_cart`, `whatsapp_basket`, `checkout_started`. Lets the dashboard show
  where each channel's shoppers stop.
- `cart_session.visit_id`, `customer_order.first_channel`, `first_source`, `last_channel`, `last_source`, `last_campaign`.
- `ad_spend` (day, channel, amount_kes, note): typed in by staff until the Google Ads API is connected.

Endpoints: `POST /api/v1/public/visits` (rate-limited per address, no sign-in), `POST /api/v1/public/visits/{id}/events`,
`GET /api/v1/admin/analytics/sources?from&to&touch=last|first`, `PUT /api/v1/admin/analytics/spend`.

## 6. The dashboard (Admin > Analytics > Sources)

- Period: today, 7 days, 30 days, this month, custom; compare with the period before.
- **Channel table**, one row each: visits, share of visits, add-to-carts, WhatsApp baskets, orders, paid revenue, orders per
  100 visits, average order, and for paid channels: spend, cost per order, revenue per KSh 1 spent.
- **Drill-down**: a channel into its sources and campaigns (Instagram → Superior / Simara / Junction; Google Ads → each
  campaign; AI → ChatGPT / Perplexity / Gemini).
- **Trend chart**: visits and revenue by channel over the period.
- **Where shoppers stop**: visit → add to cart → checkout started → paid, per channel.
- **Top landing pages** per channel (what each channel's shoppers came to see).
- **Spend box**: type the day's Google Ads and ChatGPT spend (later read from Google automatically).
- Export as CSV.

## 7. Privacy

- No personal data in a visit; the visitor id is random and stays on the shopper's browser; nothing leaves the shop's
  servers. It does not depend on the cookie choice, because it sets no cookie and shares nothing; the privacy policy gets a
  paragraph saying exactly this.
- Staff and the admin pages are never counted.

## 8. Limits to say plainly

- Counts start the day it goes live; earlier orders are "unknown".
- A shopper who sees a post and later types the address is "direct" (no tool can see that).
- A visitor on two devices is two visitors.
- Ad blockers rarely block a shop's own first-party request, so the numbers should be close to complete.

## 9. Build order and checks

1. Frontend: `lib/trafficSource.ts` and its tests (every channel rule), the visit record on first page, tags stripped after
   reading. Visible in the browser's network panel only.
2. Backend: migration, the two public endpoints with bot filtering and rate limit, order stamping at checkout, WhatsApp basket
   events. Integration tests: a visit from each channel reaches an order with the right first/last touch; staff orders are
   "Staff entry"; bots are dropped.
3. Dashboard tab, spend box, link maker. Checked against a test copy with seeded visits and orders.
4. Deploy (database backup first), then put the tagged links in the 6 bios and 3 Business Profiles.
5. After a week: compare the dashboard's paid revenue with Analytics' total (they must match) and Google Ads' conversions with
   the Google Ads channel's orders (close, not equal: Google counts by click date and its own window).

Effort: about two days. Later, optional: Google Ads API for spend (needs a developer token), and Google Search Console's
queries next to the organic channel.
