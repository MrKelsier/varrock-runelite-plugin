package app.varrock.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * A small, local watchlist with an optional price alert per item, stored in RuneLite's own config
 * (not shown as a settings field) rather than Varrock's Supabase project: no account needed, same
 * as the rest of this plugin's free functionality. Simpler than the website's watchlist/alerts
 * model (one alert per watched item here, instead of separate watch and multi-alert lists), which
 * is enough for glancing at prices in-client; the website remains the richer place to manage many
 * alerts across devices once a player chooses to sign in.
 */
@Slf4j
class VarrockWatchlist
{
	private static final String CONFIG_GROUP = "varrock";
	private static final String CONFIG_KEY = "watchlistV1";

	enum Field
	{
		LOW("Buy price (low)"), HIGH("Sell price (high)"), PROFIT("Profit after tax"), ROI("ROI %");

		final String label;

		Field(String label)
		{
			this.label = label;
		}
	}

	enum Op
	{
		ABOVE, BELOW
	}

	static class Entry
	{
		int itemId;
		String itemName;
		/** Null means "just watching", no alert configured for this item. */
		Field alertField;
		Op alertOp;
		double alertValue;
		/** True while the alert condition holds, so a crossing notifies only once; mirrors the
		 * website's own PriceAlert#firing semantics exactly (src/lib/prefs.ts). */
		transient boolean firing;

		Entry(int itemId, String itemName)
		{
			this.itemId = itemId;
			this.itemName = itemName;
		}

		boolean hasAlert()
		{
			return alertField != null && alertOp != null;
		}
	}

	private final ConfigManager configManager;
	private final Gson gson;
	private final List<Entry> entries;

	@Inject
	VarrockWatchlist(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
		this.entries = load();
	}

	private List<Entry> load()
	{
		String json = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY);
		if (json == null || json.isEmpty())
		{
			return new ArrayList<>();
		}
		try
		{
			List<Entry> parsed = gson.fromJson(json, new TypeToken<List<Entry>>() {}.getType());
			return parsed != null ? parsed : new ArrayList<>();
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Varrock: couldn't read saved watchlist, starting empty: {}", e.getMessage());
			return new ArrayList<>();
		}
	}

	private void save()
	{
		configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY, gson.toJson(entries));
	}

	synchronized List<Entry> entries()
	{
		return new ArrayList<>(entries);
	}

	/** Plenty for genuine watching, and keeps the panel's periodic refresh loop (one HTTP request
	 * per watched item) from quietly growing into a real amount of traffic. */
	private static final int MAX_ENTRIES = 50;

	synchronized boolean isWatched(int itemId)
	{
		return entries.stream().anyMatch(e -> e.itemId == itemId);
	}

	/** Returns false (and adds nothing) once at the cap, so the caller can tell the player why. */
	synchronized boolean add(int itemId, String itemName)
	{
		if (isWatched(itemId))
		{
			return true;
		}
		if (entries.size() >= MAX_ENTRIES)
		{
			return false;
		}
		entries.add(new Entry(itemId, itemName));
		save();
		return true;
	}

	synchronized void remove(int itemId)
	{
		entries.removeIf(e -> e.itemId == itemId);
		save();
	}

	synchronized void setAlert(int itemId, Field field, Op op, double value)
	{
		for (Entry e : entries)
		{
			if (e.itemId == itemId)
			{
				e.alertField = field;
				e.alertOp = op;
				e.alertValue = value;
				e.firing = false;
				save();
				return;
			}
		}
	}

	synchronized void clearAlert(int itemId)
	{
		for (Entry e : entries)
		{
			if (e.itemId == itemId)
			{
				e.alertField = null;
				e.alertOp = null;
				e.firing = false;
				save();
				return;
			}
		}
	}

	private static double fieldValue(Field field, VarrockPlugin.ItemStats stats)
	{
		switch (field)
		{
			case LOW: return stats.low;
			case HIGH: return stats.high;
			case PROFIT: return stats.profit;
			case ROI: return stats.roi * 100;
			default: throw new IllegalArgumentException("Unknown field " + field);
		}
	}

	/** Evaluates every entry's alert against freshly-fetched stats, returning the ones that just
	 * crossed their threshold (newly firing) so the caller can notify for exactly those. Also
	 * rearms an alert once the condition stops holding, same crossing-based behaviour as the
	 * website's own alert checker. */
	synchronized List<Entry> checkAlerts(java.util.function.Function<Integer, VarrockPlugin.ItemStats> statsLookup)
	{
		List<Entry> justFired = new ArrayList<>();
		boolean changed = false;
		for (Entry e : entries)
		{
			if (!e.hasAlert())
			{
				continue;
			}
			VarrockPlugin.ItemStats stats = statsLookup.apply(e.itemId);
			if (stats == null)
			{
				continue;
			}
			double v = fieldValue(e.alertField, stats);
			boolean hit = e.alertOp == Op.ABOVE ? v > e.alertValue : v < e.alertValue;
			if (hit && !e.firing)
			{
				e.firing = true;
				justFired.add(e);
				changed = true;
			}
			else if (!hit && e.firing)
			{
				e.firing = false;
				changed = true;
			}
		}
		if (changed)
		{
			save();
		}
		return justFired;
	}
}
