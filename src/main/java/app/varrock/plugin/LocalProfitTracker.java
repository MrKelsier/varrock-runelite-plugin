package app.varrock.plugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Realized flip profit for this RuneLite session, computed purely from completed buy/sell fills
 * this plugin observes itself. No account needed: this works identically signed in or not, same
 * as every other free feature on Varrock. Resets when RuneLite restarts, since there is nowhere
 * sensible to persist an ever-growing trade history locally without either a real database or an
 * unbounded RuneLite config value; signing in and enabling sync is what gives a permanent,
 * cross-device history on the website instead (see VarrockPlugin's own trade sync).
 *
 * Matches sells against the oldest open buy lots for that item first (FIFO), the standard way
 * flip profit is attributed when lots were bought at different prices.
 */
class LocalProfitTracker
{
	private static final double TAX_RATE = 0.02;
	private static final long TAX_CAP = 5_000_000;

	private static class Lot
	{
		int quantity;
		final long pricePerUnit;

		Lot(int quantity, long pricePerUnit)
		{
			this.quantity = quantity;
			this.pricePerUnit = pricePerUnit;
		}
	}

	private final Map<Integer, Deque<Lot>> openLots = new HashMap<>();
	private long realizedProfit = 0;
	private int tradeCount = 0;

	synchronized void record(int itemId, boolean isBuy, int quantity, long pricePerUnit)
	{
		if (quantity <= 0)
		{
			return;
		}
		tradeCount++;
		if (isBuy)
		{
			openLots.computeIfAbsent(itemId, k -> new ArrayDeque<>()).addLast(new Lot(quantity, pricePerUnit));
			return;
		}

		Deque<Lot> lots = openLots.get(itemId);
		int remaining = quantity;
		long tax = Math.min((long) Math.floor(pricePerUnit * TAX_RATE), TAX_CAP);
		long netSellPerUnit = pricePerUnit - tax;
		while (remaining > 0 && lots != null && !lots.isEmpty())
		{
			Lot lot = lots.peekFirst();
			int matched = Math.min(remaining, lot.quantity);
			realizedProfit += (netSellPerUnit - lot.pricePerUnit) * matched;
			lot.quantity -= matched;
			remaining -= matched;
			if (lot.quantity <= 0)
			{
				lots.pollFirst();
			}
		}
		// Selling more than was ever bought this session (stock from before RuneLite started, or
		// acquired another way) has no lot to match against, so that portion is left uncounted
		// rather than guessed at.
	}

	synchronized long realizedProfit()
	{
		return realizedProfit;
	}

	synchronized int tradeCount()
	{
		return tradeCount;
	}

	synchronized void reset()
	{
		openLots.clear();
		realizedProfit = 0;
		tradeCount = 0;
	}
}
