package app.varrock.plugin;

import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * Mirrors the player's Grand Exchange slots and logs completed trades to their Varrock account
 * (varrock.app), so the website's GE slots and Profit tracker fill in automatically instead of
 * by hand. 100% free and fully functional with no account at all; signing in only turns sync on.
 *
 * Never gates, license-checks, or reduces its own functionality based on account or payment
 * status, by design: see docs/PLAN.md in the ge-edge project and this plugin's own README.md.
 */
@Slf4j
@PluginDescriptor(
	name = "Varrock",
	description = "Syncs your GE slots and trades to your free Varrock (varrock.app) account",
	tags = {"grandexchange", "ge", "flipping", "trading", "varrock", "sync"}
)
public class VarrockPlugin extends Plugin
{
	@Inject
	private VarrockConfig config;

	@Inject
	private ConfigManager configManager;

	private ExecutorService executor;
	private VarrockApiClient api;

	/** Per-slot running totals, so a GE offer that fills in several steps is logged as several
	 * small trades (one per actual fill event) instead of one trade per tick or, worse, the same
	 * fill logged again every time the offer object is reported. Cleared when a slot goes EMPTY. */
	private final Map<Integer, SlotProgress> progress = new HashMap<>();

	private static class SlotProgress
	{
		int lastQuantitySold;
		long lastSpent;
		int itemId;
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
		progress.clear();
		trySignIn();
	}

	@Override
	protected void shutDown()
	{
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}
		api = null;
		progress.clear();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!"varrock".equals(event.getGroup()))
		{
			return;
		}
		// Any credential or project field changing means the old session (if any) is stale.
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

		VarrockApiClient client = new VarrockApiClient(url, key);
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

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (api == null || !api.isSignedIn())
		{
			return;
		}

		int slot = event.getSlot();
		GrandExchangeOffer offer = event.getOffer();
		GrandExchangeOfferState state = offer.getState();

		if (state == GrandExchangeOfferState.EMPTY)
		{
			progress.remove(slot);
			if (config.syncGeSlots())
			{
				deleteOfferRow(slot);
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

		if (config.syncTrades())
		{
			logFillDelta(slot, offer, isBuy);
		}

		if (!config.syncGeSlots())
		{
			return;
		}

		int remaining = offer.getTotalQuantity() - offer.getQuantitySold();
		if (finished || remaining <= 0)
		{
			deleteOfferRow(slot);
		}
		else
		{
			upsertOfferRow(slot, offer, isBuy, remaining);
		}
	}

	/** Logs only the newly-filled portion since the last event for this slot, using the actual
	 * average fill price (spent / quantitySold), which can differ slightly from the offer's own
	 * listed price. */
	private void logFillDelta(int slot, GrandExchangeOffer offer, boolean isBuy)
	{
		SlotProgress p = progress.computeIfAbsent(slot, k -> new SlotProgress());
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
			return;
		}

		long pricePerUnit = Math.round(deltaSpent / (double) deltaQty);
		JsonObject row = new JsonObject();
		row.addProperty("user_id", api.userId());
		row.addProperty("id", UUID.randomUUID().toString());
		row.addProperty("item_id", offer.getItemId());
		row.addProperty("side", isBuy ? "buy" : "sell");
		row.addProperty("qty", deltaQty);
		row.addProperty("price", pricePerUnit);
		row.addProperty("time", System.currentTimeMillis() / 1000);
		row.addProperty("note", "Logged by the Varrock RuneLite plugin");

		executor.submit(() -> api.upsert("trades", row, e -> log.warn("Varrock: trade log failed: {}", e.getMessage())));
	}

	private void upsertOfferRow(int slot, GrandExchangeOffer offer, boolean isBuy, int remaining)
	{
		JsonObject row = new JsonObject();
		row.addProperty("user_id", api.userId());
		row.addProperty("id", offerRowId(slot));
		row.addProperty("item_id", offer.getItemId());
		row.addProperty("side", isBuy ? "buy" : "sell");
		row.addProperty("price", offer.getPrice());
		row.addProperty("qty", remaining);
		row.addProperty("placed_at", System.currentTimeMillis() / 1000);

		executor.submit(() -> api.upsert("offers", row, e -> log.warn("Varrock: GE slot sync failed: {}", e.getMessage())));
	}

	private void deleteOfferRow(int slot)
	{
		if (api == null || !api.isSignedIn())
		{
			return;
		}
		executor.submit(() -> api.deleteById("offers", offerRowId(slot), e -> log.warn("Varrock: GE slot clear failed: {}", e.getMessage())));
	}

	/** Stable per slot so repeated updates upsert the same row instead of piling up duplicates;
	 * safe to reuse across accounts since every table is additionally scoped by user_id via RLS. */
	private static String offerRowId(int slot)
	{
		return "slot-" + slot;
	}
}
