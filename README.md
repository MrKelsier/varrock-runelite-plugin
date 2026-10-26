# Varrock: GE Flipping Companion

A GE flipping utility for RuneLite. Shows live top flip picks right in a RuneLite sidebar panel,
free, no account needed, and optionally mirrors your Grand Exchange slots and logs completed
trades to your free Varrock account (https://www.varrock.app), where flip scores, signals and a
full profit tracker fill in automatically instead of by hand.

## This plugin is always free

Every feature here works fully with no account at all. Signing in only turns sync on; it is
never required, never checked for "premium" status, and never gated. This is a deliberate,
permanent rule for this project (see `docs/PLAN.md` in the `ge-edge` repo), because RuneLite's
Plugin Hub banned exactly the opposite pattern (plugins paywalling their own functionality)
after the October 2026 "paid plugin" backlash. If a future version of this plugin were ever
changed to check a license or restrict functionality by payment, that would be a bug, not a
feature, against this project's own stated principles.

## What it does

- **A sidebar panel with live top flip picks**: the same free, unpersonalized top 12 picks the
  website's own Dashboard shows (buy price, sell price, profit, score), fetched from
  varrock.app's own API every 5 minutes. No account needed to see it, works the moment you enable
  the plugin. Click a pick to open it on the website for the full picture.
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

- `src/main/java/app/varrock/plugin/VarrockPlugin.java`: the plugin itself, event handling,
  per-slot fill tracking, and the picks panel's fetch/refresh loop.
- `src/main/java/app/varrock/plugin/VarrockPanel.java`: the sidebar panel showing live top picks.
- `src/main/java/app/varrock/plugin/VarrockConfig.java`: the settings panel fields.
- `src/main/java/app/varrock/plugin/VarrockApiClient.java`: a small hand-rolled client for
  Supabase's own REST APIs (GoTrue for sign-in, PostgREST for the `trades`/`offers` tables). No
  Supabase SDK dependency; it's two endpoints and three HTTP verbs, not worth the extra weight
  inside a game client.

## What this does not do, and why

- No autocomplete/full catalogue of RS3 or OSRS items beyond what the Grand Exchange interface
  itself already gives RuneLite; this plugin only reports what the client already knows about
  your own offers.
- No flip scoring or recommendation logic computed in the plugin itself. The picks panel displays
  numbers the website's own public API already computed (`ge-edge/api/picks.js`, which calls the
  same `buildRows`/`flipScore` the Dashboard uses); duplicating that formula in Java would let it
  drift out of sync with the website over time. It is the same free, unpersonalized list every
  visitor and every install sees, never a better or different feed for anyone (see `docs/PLAN.md`,
  "never sell flipping power").
- No telemetry beyond what you explicitly sync to your own Varrock account. The picks panel is a
  plain, unauthenticated read with no player data in the request; GE slot/trade sync still needs
  you to fill in the settings panel, and sends nothing if you don't.
