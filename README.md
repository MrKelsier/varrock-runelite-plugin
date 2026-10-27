# Varrock: GE Flipping Companion

A GE flipping utility for RuneLite, built to be useful entirely inside the client: a full
filterable flip finder, a live margin overlay on the Grand Exchange screen, a local watchlist with
price alerts and notifications, and a session profit tracker, all free and working with no account
at all. Signing in is purely optional, and only adds cross-device sync of your Grand Exchange slots
and trade history to your free Varrock account (https://www.varrock.app).

## This plugin is always free

Every feature here works fully with no account at all. Signing in only turns sync on; it is
never required, never checked for "premium" status, and never gated. This is a deliberate,
permanent rule for this project (see `docs/PLAN.md` in the `ge-edge` repo), because RuneLite's
Plugin Hub banned exactly the opposite pattern (plugins paywalling their own functionality)
after the October 2026 "paid plugin" backlash. If a future version of this plugin were ever
changed to check a license or restrict functionality by payment, that would be a bug, not a
feature, against this project's own stated principles.

## What it does

- **A full filterable flip finder in the sidebar**, not just a top-12 teaser: the same presets as
  the website's own Flip Finder (Best, Volume, Big ticket, Under 100k, High ROI, Hidden gems), each
  showing up to 25 results with buy/sell price, profit, score and daily volume. Switching presets
  refetches instantly; the list otherwise refreshes every 5 minutes. No account needed, and no need
  to alt-tab to the website to see past the first page.
- **A local watchlist with price alerts** (up to 50 items): add the item currently open in the GE
  with one click, set a target (buy price, sell price, profit or ROI, above or below), and get a
  RuneLite notification the moment it crosses, checked every 45 seconds even while you're off doing
  something else. The whole watchlist refreshes in a single batched request (`/api/item**s**`), not
  one request per item, so it stays light even with it full. Stored entirely in RuneLite's own
  config, no account or website visit needed; notifications can be turned off in settings.
- **A session profit tracker**: every completed buy and sell this plugin observes is matched
  FIFO per item to compute your realized profit live in the panel, with no account at all. Resets
  when RuneLite restarts; signing in and enabling sync (below) is what gives a permanent,
  cross-device trade history on the website instead.
- **A live flip overlay on the Grand Exchange screen itself**: whichever item you've selected in
  the GE's buy/sell setup screen gets a small panel next to it with its current buy/sell price,
  margin, ROI, flip score and daily volume, pulled from the same free API as the picks panel and
  the website. No alt-tabbing to a browser to check whether what you're about to buy is actually a
  good flip. Toggleable in settings; no account needed.
- **A locally-tracked GE buy-limit counter**, shown in that same overlay: every buy you make is
  recorded here and summed over OSRS's real rolling 4-hour buy-limit window, next to the item's
  real buy limit, with a countdown to when it frees up. RuneLite's client API has no way to read
  your true remaining limit from the game itself, so this is only an estimate seeded from
  purchases made since the plugin started this session, not a lifetime counter.
- **GE slot mirroring** (needs a free account): listens for `GrandExchangeOfferChanged` and
  upserts each open offer (item, side, price, remaining quantity) into your Varrock account's
  `offers` table, tagged with the character it came from. When a slot empties, the row is
  deleted. Your slots then show up on varrock.app on any device.
- **Automatic trade logging** (needs a free account): when an offer fills (fully or in steps),
  the actual filled quantity and real average fill price (from the offer's own
  `spent`/`quantitySold`, not just its listed price) is logged as a trade, tagged with the
  character it came from, so the website's Profit tracker builds itself up without manual entry.
  Play more than one character? They all sync to one account without mixing each other's profit
  together.
- GE slot mirroring and trade logging can each be turned off independently in the plugin's
  settings if you only want one. The picks panel has no setting to disable sync for, since it
  never touches your account at all.

## Setup

1. Create a free Varrock account at https://www.varrock.app (Settings > Account and sync).
2. In the plugin's RuneLite settings, enter your **email** and **password** (your Varrock account
   credentials; the Supabase project URL and anon key are already filled in with Varrock's own
   production project, no need to touch those). Credentials are stored in RuneLite's plain config
   file, the same as most RuneLite plugin credentials are, so use a password unique to Varrock
   rather than one reused elsewhere. A safer "app password" flow (separate from your main login)
   is a reasonable future improvement, not built yet.
3. Place a GE offer in game. It should appear on varrock.app's GE slots page within a few
   seconds, and a completed trade should appear in Profit tracker once it fills.

## Building it yourself

Requires a JDK (11 or newer; built and verified here against a Java 21 JDK). The Gradle wrapper
downloads the rest.

```
./gradlew build
```

To actually run it inside a real RuneLite client for testing, use the `run` task (this is the
same approach the official `runelite/example-plugin` template and the Plugin Hub's own
contribution guide use: open `build.gradle` and run the `run` task, or from the command line):

```
./gradlew run
```

This launches a developer-mode RuneLite client with the plugin already loaded. Log in and test
against a real account before relying on it.

An alternative for testing a built jar without a full dev environment: build it, then drop the
jar into RuneLite's `sideloaded-plugins` folder inside your RuneLite user directory (consult
RuneLite's current documentation for the exact folder name and location, since this is a
client-side mechanism that can change between versions).

## Submitting to the Plugin Hub

Submitted: https://github.com/runelite/plugin-hub/pull/19445 (three submissions in, the first two
were rejected over the companion website having a paid tier at all, not over the plugin itself;
fixed by removing every paid tier from the website, see `docs/PLAN.md` in the `ge-edge` repo).
Re-read the current README and contribution guide at https://github.com/runelite/plugin-hub
before any future resubmission, since this is actively maintained and rules can change.

## Project layout

- `src/main/java/app/varrock/plugin/VarrockPlugin.java`: the plugin itself, event handling, the
  single shared per-slot fill tracker everything else is built on, the flip finder's fetch/refresh
  loop, the GE overlay's per-item stats cache, and the watchlist alert check.
- `src/main/java/app/varrock/plugin/VarrockPanel.java`: the sidebar panel (flip finder presets,
  watchlist, session profit).
- `src/main/java/app/varrock/plugin/VarrockGeOverlay.java`: the live margin/score/buy-limit panel
  drawn next to the Grand Exchange offer screen.
- `src/main/java/app/varrock/plugin/BuyLimitTracker.java`: the local, session-only rolling
  4-hour buy-limit estimator backing the overlay's buy-limit line.
- `src/main/java/app/varrock/plugin/LocalProfitTracker.java`: the local, session-only FIFO
  buy/sell matcher backing the session profit tracker, no account involved.
- `src/main/java/app/varrock/plugin/VarrockWatchlist.java`: the local watchlist and price-alert
  store (RuneLite config, not Supabase) and the alert-crossing check.
- `src/main/java/app/varrock/plugin/VarrockConfig.java`: the settings panel fields.
- `src/main/java/app/varrock/plugin/VarrockApiClient.java`: a small hand-rolled client for
  Supabase's own REST APIs (GoTrue for sign-in, PostgREST for the `trades`/`offers` tables). No
  Supabase SDK dependency; it's two endpoints and three HTTP verbs, not worth the extra weight
  inside a game client.

## What this does not do, and why

- No autocomplete/full catalogue of RS3 or OSRS items beyond what the Grand Exchange interface
  itself already gives RuneLite; this plugin only reports what the client already knows about
  your own offers.
- No flip scoring or recommendation logic computed in the plugin itself. The flip finder, the
  watchlist's live prices and the GE overlay all display numbers the website's own public API
  already computed (`ge-edge/api/flips.js`, `api/picks.js`, `api/item.js` and `api/items.js`, the
  batched form the watchlist uses, all of which share the same
  `buildItemStats`/`flipScore` the Dashboard uses); duplicating that formula in Java would let it
  drift out of sync with the website over time. It is the same free, unpersonalized data every
  visitor and every install sees, never a better or different feed for anyone (see `docs/PLAN.md`,
  "never sell flipping power").
- No telemetry beyond what you explicitly sync to your own Varrock account. The flip finder,
  watchlist and overlay are plain, unauthenticated reads with no player data in the request; GE
  slot/trade sync still needs you to fill in the settings panel, and sends nothing if you don't.
  The watchlist and session profit tracker never leave your own machine at all, signed in or not.
