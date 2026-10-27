package app.varrock.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Player;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mirrors the player's Grand Exchange slots and logs completed trades to their Varrock account
 * (varrock.app), so the website's GE slots and Profit tracker fill in automatically instead of
 * by hand. 100% free and fully functional with no account at all; signing in only turns sync on.
 *
 * Every synced row is tagged with the currently logged-in character's display name
 * (character_name), and GE slot rows are namespaced by it too, so one Varrock account can hold
 * several characters' data (common for OSRS players with an ironman/main/skiller split) without
 * one character's open slot 3 overwriting another's. A row is never written without a known
 * character name: if the client is not logged in, the event is skipped entirely rather than
 * guessing whose data it is.
 *
 * Never gates, license-checks, or reduces its own functionality based on account or payment
 * status, by design: see docs/PLAN.md in the ge-edge project and this plugin's own README.md.
 */
@Slf4j
@PluginDescriptor(
	name = "Varrock: GE Flipping Companion",
	description = "GE flipping utility: a live margin/ROI/score overlay with a buy-limit tracker on the GE screen, a full filterable flip finder and watchlist with price alerts in the sidebar, a local session profit tracker, plus optional sync of your Grand Exchange slots and trades to your free Varrock (varrock.app) account",
	tags = {"grandexchange", "ge", "flipping", "flipping utilities", "merchanting", "trading", "varrock", "sync", "alerts", "watchlist"}
)
public class VarrockPlugin extends Plugin
{
	@Inject
	private VarrockConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private Gson gson;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private VarrockGeOverlay geOverlay;

	@Inject
	private VarrockWatchlist watchlist;

	@Inject
	private Notifier notifier;

	private static final String FLIPS_URL_BASE = "https://www.varrock.app/api/flips?limit=25&preset=";
	private static final String ITEM_URL_BASE = "https://www.varrock.app/api/item?id=";
	private static final long ITEM_STATS_TTL_MILLIS = 20_000;

	private volatile String currentPreset = "best";

	private ExecutorService executor;
	private ScheduledExecutorService picksExecutor;
	private VarrockApiClient api;
	private VarrockPanel panel;
	private NavigationButton navButton;

	private final BuyLimitTracker buyLimitTracker = new BuyLimitTracker();
	private final LocalProfitTracker profitTracker = new LocalProfitTracker();
	private final Map<Integer, ItemStats> itemStatsCache = new ConcurrentHashMap<>();
	private final Set<Integer> fetchingItemStats = ConcurrentHashMap.newKeySet();
	private volatile int lastViewedItemId = 0;

	/** Mirrors the JSON shape returned by ge-edge's api/item.js exactly. */
	static class ItemStats
	{
		int id;
		String name;
		Integer limit;
		long low;
		long high;
		long profit;
		double roi;
		long d1Vol;
		int score;
		transient long fetchedAtMillis;
	}

	/** Per-slot running totals, so a GE offer that fills in several steps is reported as several
	 * small fills (one per actual fill event) instead of one fill per tick or, worse, the same
	 * fill reported again every time the offer object is reported. Cleared when a slot goes EMPTY.
	 * Shared by every feature that cares about fills (buy-limit tracking, the local profit
	 * tracker, and account sync below), computed once per event rather than three separate times. */
	private final Map<Integer, SlotProgress> fillProgress = new HashMap<>();

	private static class SlotProgress
	{
		int lastQuantitySold;
		long lastSpent;
		int itemId;
	}

	/** One observed fill: the portion of an offer that newly completed since the last event for
	 * that slot, with its actual average fill price (spent / quantitySold, which can differ
	 * slightly from the offer's own listed price). */
	private static class Fill
	{
		final int itemId;
		final boolean isBuy;
		final int quantity;
		final long pricePerUnit;

		Fill(int itemId, boolean isBuy, int quantity, long pricePerUnit)
		{
			this.itemId = itemId;
			this.isBuy = isBuy;
			this.quantity = quantity;
			this.pricePerUnit = pricePerUnit;
		}
	}

	@Provides
	VarrockConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(VarrockConfig.class);
	}

	@Override
	protected void startUp()
	{
		executor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "varrock-sync");
			t.setDaemon(true);
			return t;
		});
		trySignIn();

		panel = new VarrockPanel(this);
		panel.updateProfit(profitTracker.realizedProfit(), profitTracker.tradeCount());
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Varrock")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		picksExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "varrock-picks");
			t.setDaemon(true);
			return t;
		});
		picksExecutor.scheduleWithFixedDelay(() -> fetchFlips(currentPreset), 0, 5, TimeUnit.MINUTES);
		picksExecutor.scheduleWithFixedDelay(this::checkWatchlistAlerts, 15, 60, TimeUnit.SECONDS);
		picksExecutor.scheduleWithFixedDelay(() -> { if (panel != null) panel.refreshWatchlist(); }, 5, 30, TimeUnit.SECONDS);

		fillProgress.clear();
		itemStatsCache.clear();
		overlayManager.add(geOverlay);
	}

	@Override
	protected void shutDown()
	{
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}
		if (picksExecutor != null)
		{
			picksExecutor.shutdownNow();
			picksExecutor = null;
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		overlayManager.remove(geOverlay);
		panel = null;
		api = null;
		fillProgress.clear();
		itemStatsCache.clear();
	}

	/** Returns cached flip stats for this item if we have a fresh-enough copy, kicking off a
	 * background refresh (free, unauthenticated, same endpoint the website's own pages use) if
	 * the cache is missing or stale. May return a stale or null value on the very first call for
	 * an item; the overlay simply skips rendering until a result arrives. */
	ItemStats statsFor(int itemId)
	{
		ItemStats cached = itemStatsCache.get(itemId);
		boolean stale = cached == null || System.currentTimeMillis() - cached.fetchedAtMillis > ITEM_STATS_TTL_MILLIS;
		if (stale && fetchingItemStats.add(itemId))
		{
			executor.submit(() -> fetchItemStats(itemId));
		}
		return cached;
	}

	private void fetchItemStats(int itemId)
	{
		try
		{
			Request req = new Request.Builder().url(ITEM_URL_BASE + itemId).build();
			try (Response res = okHttpClient.newCall(req).execute())
			{
				if (res.isSuccessful() && res.body() != null)
				{
					String body = res.body().string();
					ItemStats stats = gson.fromJson(body, ItemStats.class);
					if (stats != null)
					{
						stats.fetchedAtMillis = System.currentTimeMillis();
						itemStatsCache.put(itemId, stats);
					}
				}
			}
		}
		catch (IOException | JsonSyntaxException e)
		{
			log.debug("Varrock: failed to load item stats for {}: {}", itemId, e.getMessage());
		}
		finally
		{
			fetchingItemStats.remove(itemId);
		}
	}

	long boughtInWindow(int itemId)
	{
		return buyLimitTracker.boughtInWindow(itemId);
	}

	long secondsUntilLimitFrees(int itemId)
	{
		return buyLimitTracker.secondsUntilNextFree(itemId);
	}

	long sessionProfit()
	{
		return profitTracker.realizedProfit();
	}

	int sessionTradeCount()
	{
		return profitTracker.tradeCount();
	}

	void resetSessionProfit()
	{
		profitTracker.reset();
		if (panel != null)
		{
			panel.updateProfit(0, 0);
		}
	}

	/** Called by the GE overlay each time it draws for a known item, so the panel's "watch this
	 * item" button always refers to whatever the player currently has open in the GE, without the
	 * panel needing its own item search UI. */
	void noteViewedItem(int itemId)
	{
		lastViewedItemId = itemId;
	}

	int lastViewedItemId()
	{
		return lastViewedItemId;
	}

	VarrockWatchlist watchlist()
	{
		return watchlist;
	}

	/** Polled on a schedule rather than only on-demand, so an alert can fire even while the
	 * player is off doing something else entirely (combat, skilling) with the GE screen closed. */
	private void checkWatchlistAlerts()
	{
		List<VarrockWatchlist.Entry> fired = watchlist.checkAlerts(this::statsFor);
		if (!config.notifyOnAlerts())
		{
			return;
		}
		for (VarrockWatchlist.Entry e : fired)
		{
			notifier.notify(String.format("Varrock: %s's %s went %s %s", e.itemName, e.alertField.label, e.alertOp == VarrockWatchlist.Op.ABOVE ? "above" : "below", formatAlertValue(e)));
		}
	}

	private static String formatAlertValue(VarrockWatchlist.Entry e)
	{
		return e.alertField == VarrockWatchlist.Field.ROI ? String.format("%.2f%%", e.alertValue) : String.format("%,d", (long) e.alertValue);
	}

	/** The newly-filled portion of this offer since the last event for its slot, or null if
	 * nothing new filled (most GE events are just UI state changes, not fills) or the slot just
	 * went empty. Always computed, regardless of sign-in: buy-limit tracking and the local profit
	 * tracker are free, local-only features with no account involved. */
	private Fill computeFill(int slot, GrandExchangeOffer offer, GrandExchangeOfferState state)
	{
		if (state == GrandExchangeOfferState.EMPTY)
		{
			fillProgress.remove(slot);
			return null;
		}
		SlotProgress p = fillProgress.computeIfAbsent(slot, k -> new SlotProgress());
		if (p.itemId != 0 && p.itemId != offer.getItemId())
		{
			// A new offer was placed in this slot before we saw it go EMPTY (e.g. after a
			// RuneLite restart); start this slot's tracking over rather than mislog a diff
			// across two unrelated offers.
			p.lastQuantitySold = 0;
			p.lastSpent = 0;
		}
		p.itemId = offer.getItemId();

		int deltaQty = offer.getQuantitySold() - p.lastQuantitySold;
		long deltaSpent = offer.getSpent() - p.lastSpent;
		p.lastQuantitySold = offer.getQuantitySold();
		p.lastSpent = offer.getSpent();
		if (deltaQty <= 0)
		{
			return null;
		}

		boolean isBuy = state == GrandExchangeOfferState.BUYING
			|| state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY;
		long pricePerUnit = Math.round(deltaSpent / (double) deltaQty);
		return new Fill(offer.getItemId(), isBuy, deltaQty, pricePerUnit);
	}

	/** Switches the flip list to a different preset (mirrors the website's own Flip Finder
	 * presets exactly, see ge-edge's src/pages/Flips.tsx and api/_picks.js's presetFilters) and
	 * fetches it immediately rather than waiting for the next scheduled refresh. */
	void setPreset(String preset)
	{
		currentPreset = preset;
		executor.submit(() -> fetchFlips(preset));
	}

	String currentPreset()
	{
		return currentPreset;
	}

	/** Pulls the same free, unpersonalized flip list the website's own Flip Finder shows for the
	 * given preset. No account or sign-in needed: this works identically whether or not the
	 * player has filled in the settings panel above, same as every other free feature on Varrock. */
	private void fetchFlips(String preset)
	{
		Request req = new Request.Builder().url(FLIPS_URL_BASE + preset).build();
		try (Response res = okHttpClient.newCall(req).execute())
		{
			if (!res.isSuccessful() || res.body() == null)
			{
				panel.showError("Couldn't load flips (HTTP " + res.code() + ").");
				return;
			}
			String body = res.body().string();
			VarrockPanel.FlipsResponse parsed = gson.fromJson(body, VarrockPanel.FlipsResponse.class);
			List<VarrockPanel.Pick> flips = parsed != null && parsed.flips != null ? parsed.flips : List.of();
			if (preset.equals(currentPreset))
			{
				panel.showPicks(flips);
			}
		}
		catch (IOException | JsonSyntaxException e)
		{
			log.warn("Varrock: failed to load flips: {}", e.getMessage());
			if (preset.equals(currentPreset))
			{
				panel.showError("Couldn't load flips right now.");
			}
		}
	}

	private static final Set<String> CREDENTIAL_KEYS = Set.of("supabaseUrl", "supabaseAnonKey", "email", "password");

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!"varrock".equals(event.getGroup()) || !CREDENTIAL_KEYS.contains(event.getKey()))
		{
			// The watchlist and other plugin state live in this same config group (see
			// VarrockWatchlist), so without this key check every watchlist edit would needlessly
			// re-authenticate against Supabase as if a credential had changed.
			return;
		}
		trySignIn();
	}

	private void trySignIn()
	{
		String url = emptyToNull(config.supabaseUrl());
		String key = emptyToNull(config.supabaseAnonKey());
		String email = emptyToNull(config.email());
		String password = emptyToNull(config.password());

		if (url == null || key == null || email == null || password == null)
		{
			api = null;
			return;
		}

		VarrockApiClient client = new VarrockApiClient(okHttpClient, url, key);
		executor.submit(() -> {
			try
			{
				client.signIn(email, password);
				api = client;
				log.info("Varrock: ready to sync");
			}
			catch (Exception e)
			{
				api = null;
				log.warn("Varrock: sign-in failed: {}", e.getMessage());
			}
		});
	}

	private static String emptyToNull(String s)
	{
		return (s == null || s.trim().isEmpty()) ? null : s.trim();
	}

	/** The logged-in character's display name, or null if not logged in (e.g. still on the login screen). */
	private String characterName()
	{
		Player local = client.getLocalPlayer();
		return local != null ? local.getName() : null;
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		int slot = event.getSlot();
		GrandExchangeOffer offer = event.getOffer();
		GrandExchangeOfferState state = offer.getState();

		Fill fill = computeFill(slot, offer, state);
		if (fill != null)
		{
			if (fill.isBuy)
			{
				buyLimitTracker.record(fill.itemId, fill.quantity);
			}
			profitTracker.record(fill.itemId, fill.isBuy, fill.quantity, fill.pricePerUnit);
			if (panel != null)
			{
				panel.updateProfit(profitTracker.realizedProfit(), profitTracker.tradeCount());
			}
		}

		if (api == null || !api.isSignedIn())
		{
			return;
		}

		String character = characterName();
		if (character == null)
		{
			// Can't safely tag or namespace a row without knowing whose data it is.
			return;
		}

		if (state == GrandExchangeOfferState.EMPTY)
		{
			if (config.syncGeSlots())
			{
				deleteOfferRow(character, slot);
			}
			return;
		}

		boolean isBuy = state == GrandExchangeOfferState.BUYING
			|| state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY;
		boolean finished = state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.SOLD
			|| state == GrandExchangeOfferState.CANCELLED_BUY
			|| state == GrandExchangeOfferState.CANCELLED_SELL;

		if (config.syncTrades() && fill != null)
		{
			logFill(character, fill);
		}

		if (!config.syncGeSlots())
		{
			return;
		}

		int remaining = offer.getTotalQuantity() - offer.getQuantitySold();
		if (finished || remaining <= 0)
		{
			deleteOfferRow(character, slot);
		}
		else
		{
			upsertOfferRow(character, slot, offer, isBuy, remaining);
		}
	}

	private void logFill(String character, Fill fill)
	{
		JsonObject row = new JsonObject();
		row.addProperty("user_id", api.userId());
		row.addProperty("id", UUID.randomUUID().toString());
		row.addProperty("item_id", fill.itemId);
		row.addProperty("side", fill.isBuy ? "buy" : "sell");
		row.addProperty("qty", fill.quantity);
		row.addProperty("price", fill.pricePerUnit);
		row.addProperty("time", System.currentTimeMillis() / 1000);
		row.addProperty("note", "Logged by the Varrock RuneLite plugin");
		row.addProperty("character_name", character);

		executor.submit(() -> api.upsert("trades", row, e -> log.warn("Varrock: trade log failed: {}", e.getMessage())));
	}

	private void upsertOfferRow(String character, int slot, GrandExchangeOffer offer, boolean isBuy, int remaining)
	{
		JsonObject row = new JsonObject();
		row.addProperty("user_id", api.userId());
		row.addProperty("id", offerRowId(character, slot));
		row.addProperty("item_id", offer.getItemId());
		row.addProperty("side", isBuy ? "buy" : "sell");
		row.addProperty("price", offer.getPrice());
		row.addProperty("qty", remaining);
		row.addProperty("placed_at", System.currentTimeMillis() / 1000);
		row.addProperty("character_name", character);

		executor.submit(() -> api.upsert("offers", row, e -> log.warn("Varrock: GE slot sync failed: {}", e.getMessage())));
	}

	private void deleteOfferRow(String character, int slot)
	{
		if (api == null || !api.isSignedIn())
		{
			return;
		}
		executor.submit(() -> api.deleteById("offers", offerRowId(character, slot), e -> log.warn("Varrock: GE slot clear failed: {}", e.getMessage())));
	}

	/** Stable per character and slot so repeated updates upsert the same row instead of piling up
	 * duplicates, and so two characters on the same account never collide on the same slot number
	 * (each OSRS character has its own independent 8 GE slots in-game). Safe to reuse across
	 * accounts since every table is additionally scoped by user_id via RLS. */
	private static String offerRowId(String character, int slot)
	{
		String safe = character.toLowerCase().replaceAll("[^a-z0-9]+", "-");
		return "slot-" + safe + "-" + slot;
	}
}
