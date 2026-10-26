package app.varrock.plugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks this session's own Grand Exchange purchases per item over OSRS's rolling 4-hour buy
 * limit window, purely from events this plugin already observes (GrandExchangeOfferChanged).
 * RuneLite's Client API has no way to query a player's true remaining buy limit: the game only
 * enforces it server-side and surfaces it as an in-context warning when exceeded. This is
 * therefore an estimate seeded only from purchases made while the plugin was running this
 * session, not the player's full account-wide usage (a purchase made just before RuneLite was
 * started won't be counted here even though it still counts against the real in-game limit).
 */
class BuyLimitTracker
{
	private static final long WINDOW_SECONDS = 4 * 60 * 60;

	private final Map<Integer, Deque<long[]>> purchases = new HashMap<>();

	synchronized void record(int itemId, int quantity)
	{
		if (quantity <= 0)
		{
			return;
		}
		Deque<long[]> entries = purchases.computeIfAbsent(itemId, k -> new ArrayDeque<>());
		entries.addLast(new long[] { System.currentTimeMillis() / 1000, quantity });
		prune(entries);
	}

	synchronized long boughtInWindow(int itemId)
	{
		Deque<long[]> entries = purchases.get(itemId);
		if (entries == null)
		{
			return 0;
		}
		prune(entries);
		long total = 0;
		for (long[] entry : entries)
		{
			total += entry[1];
		}
		return total;
	}

	/** Seconds until the oldest tracked purchase ages out of the 4-hour window, i.e. until the
	 * tracked total next decreases. 0 if nothing is currently tracked for this item. */
	synchronized long secondsUntilNextFree(int itemId)
	{
		Deque<long[]> entries = purchases.get(itemId);
		if (entries == null || entries.isEmpty())
		{
			return 0;
		}
		prune(entries);
		if (entries.isEmpty())
		{
			return 0;
		}
		long freesAt = entries.peekFirst()[0] + WINDOW_SECONDS;
		return Math.max(0, freesAt - System.currentTimeMillis() / 1000);
	}

	private void prune(Deque<long[]> entries)
	{
		long cutoff = System.currentTimeMillis() / 1000 - WINDOW_SECONDS;
		while (!entries.isEmpty() && entries.peekFirst()[0] < cutoff)
		{
			entries.pollFirst();
		}
	}
}
