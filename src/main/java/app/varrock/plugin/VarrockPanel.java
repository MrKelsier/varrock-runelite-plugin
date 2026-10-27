package app.varrock.plugin;

import com.google.gson.annotations.SerializedName;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * The plugin's sidebar panel: live top flip picks and a local watchlist/session profit tracker,
 * all free and working with no account at all (see docs/PLAN.md's "never sell flipping power" in
 * the ge-edge project). Signing in only turns on cross-device sync, built elsewhere in the plugin.
 */
class VarrockPanel extends PluginPanel
{
	private final VarrockPlugin plugin;
	private final JPanel listPanel = new JPanel();
	private final JLabel status = new JLabel("Loading top picks...");
	private final JLabel profitLabel = new JLabel("Session profit: 0 gp (0 trades)");
	private final JPanel watchlistPanel = new JPanel();

	VarrockPanel(VarrockPlugin plugin)
	{
		super();
		this.plugin = plugin;
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel title = new JLabel("Varrock");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		header.add(title, BorderLayout.WEST);
		JButton openSite = new JButton("Open site");
		openSite.addActionListener(e -> LinkBrowser.browse("https://www.varrock.app/flips"));
		header.add(openSite, BorderLayout.EAST);
		top.add(header);

		JPanel profitRow = new JPanel(new BorderLayout());
		profitRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		profitRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 8, 0));
		profitLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		profitLabel.setFont(FontManager.getRunescapeSmallFont());
		profitRow.add(profitLabel, BorderLayout.CENTER);
		JButton reset = new JButton("Reset");
		reset.setFont(FontManager.getRunescapeSmallFont());
		reset.addActionListener(e -> plugin.resetSessionProfit());
		profitRow.add(reset, BorderLayout.EAST);
		top.add(profitRow);

		add(top, BorderLayout.NORTH);

		JTabbedPane tabs = new JTabbedPane();
		tabs.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tabs.addTab("Picks", buildPicksTab());
		tabs.addTab("Watchlist", buildWatchlistTab());
		add(tabs, BorderLayout.CENTER);
	}

	private static final String[][] PRESETS = {
		{ "best", "Best" },
		{ "volume", "Volume" },
		{ "big", "Big ticket" },
		{ "under100k", "Under 100k" },
		{ "roi", "High ROI" },
		{ "gems", "Hidden gems" },
	};

	private JScrollPane buildPicksTab()
	{
		JPanel container = new JPanel(new BorderLayout());
		container.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel presetRow = new JPanel();
		presetRow.setLayout(new java.awt.GridLayout(0, 2, 4, 4));
		presetRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		presetRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		for (String[] preset : PRESETS)
		{
			JButton chip = new JButton(preset[1]);
			chip.setFont(FontManager.getRunescapeSmallFont());
			chip.addActionListener(e -> {
				plugin.setPreset(preset[0]);
				status.setText("Loading " + preset[1].toLowerCase() + "...");
				listPanel.removeAll();
				listPanel.add(status);
				listPanel.revalidate();
				listPanel.repaint();
			});
			presetRow.add(chip);
		}
		container.add(presetRow, BorderLayout.NORTH);

		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));

		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		listPanel.add(status);
		container.add(listPanel, BorderLayout.CENTER);

		JScrollPane scroll = new JScrollPane(container);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		return scroll;
	}

	private JScrollPane buildWatchlistTab()
	{
		JPanel container = new JPanel(new BorderLayout());
		container.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JButton watchCurrent = new JButton("+ Watch the item currently open in the GE");
		watchCurrent.setFont(FontManager.getRunescapeSmallFont());
		watchCurrent.addActionListener(e -> watchCurrentItem());
		container.add(watchCurrent, BorderLayout.NORTH);

		watchlistPanel.setLayout(new BoxLayout(watchlistPanel, BoxLayout.Y_AXIS));
		watchlistPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		container.add(watchlistPanel, BorderLayout.CENTER);

		JScrollPane scroll = new JScrollPane(container);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		refreshWatchlist();
		return scroll;
	}

	private void watchCurrentItem()
	{
		int itemId = plugin.lastViewedItemId();
		if (itemId <= 0)
		{
			JOptionPane.showMessageDialog(this, "Open the Grand Exchange and select an item first.");
			return;
		}
		VarrockPlugin.ItemStats stats = plugin.statsFor(itemId);
		String name = stats != null ? stats.name : ("Item " + itemId);
		plugin.watchlist().add(itemId, name);
		refreshWatchlist();
	}

	void showError(String message)
	{
		SwingUtilities.invokeLater(() -> {
			listPanel.removeAll();
			status.setText(message);
			listPanel.add(status);
			listPanel.revalidate();
			listPanel.repaint();
		});
	}

	void showPicks(List<Pick> picks)
	{
		SwingUtilities.invokeLater(() -> {
			listPanel.removeAll();
			if (picks.isEmpty())
			{
				status.setText("Nothing scores well right now.");
				listPanel.add(status);
			}
			else
			{
				for (Pick p : picks)
				{
					listPanel.add(row(p));
				}
			}
			listPanel.revalidate();
			listPanel.repaint();
		});
	}

	void updateProfit(long profit, int trades)
	{
		SwingUtilities.invokeLater(() -> profitLabel.setText(String.format("Session profit: %s gp (%d trade%s)", fmt(profit), trades, trades == 1 ? "" : "s")));
	}

	/** Rebuilds the watchlist list from scratch against the plugin's current data. Called after
	 * any add/remove/alert edit, and periodically from VarrockPlugin so live prices stay fresh. */
	void refreshWatchlist()
	{
		SwingUtilities.invokeLater(() -> {
			watchlistPanel.removeAll();
			List<VarrockWatchlist.Entry> entries = plugin.watchlist().entries();
			if (entries.isEmpty())
			{
				JLabel empty = new JLabel("Nothing watched yet.");
				empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				empty.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
				watchlistPanel.add(empty);
			}
			else
			{
				for (VarrockWatchlist.Entry entry : entries)
				{
					watchlistPanel.add(watchRow(entry));
				}
			}
			watchlistPanel.revalidate();
			watchlistPanel.repaint();
		});
	}

	private JPanel watchRow(VarrockWatchlist.Entry entry)
	{
		VarrockPlugin.ItemStats stats = plugin.statsFor(entry.itemId);

		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createEmptyBorder(0, 0, 6, 0),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));

		JPanel top = new JPanel(new BorderLayout());
		top.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel name = new JLabel(entry.itemName);
		name.setForeground(Color.WHITE);
		name.setFont(FontManager.getRunescapeSmallFont());
		top.add(name, BorderLayout.WEST);
		JLabel price = new JLabel(stats != null ? String.format("Buy %s / Sell %s", fmt(stats.low), fmt(stats.high)) : "Loading...");
		price.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		price.setFont(FontManager.getRunescapeSmallFont());
		top.add(price, BorderLayout.EAST);
		row.add(top);

		JLabel alertLine = new JLabel(entry.hasAlert()
			? String.format("Alert: %s %s %s%s", entry.alertField, entry.alertOp == VarrockWatchlist.Op.ABOVE ? "above" : "below", formatAlertValue(entry), entry.firing ? " (firing)" : "")
			: "No alert set");
		alertLine.setForeground(entry.firing ? Color.ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		alertLine.setFont(FontManager.getRunescapeSmallFont());
		row.add(alertLine);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
		buttons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JButton editAlert = new JButton(entry.hasAlert() ? "Edit alert" : "Set alert");
		editAlert.setFont(FontManager.getRunescapeSmallFont());
		editAlert.addActionListener(e -> editAlert(entry));
		buttons.add(editAlert);
		JButton remove = new JButton("Remove");
		remove.setFont(FontManager.getRunescapeSmallFont());
		remove.addActionListener(e -> {
			plugin.watchlist().remove(entry.itemId);
			refreshWatchlist();
		});
		buttons.add(remove);
		row.add(buttons);

		return row;
	}

	private void editAlert(VarrockWatchlist.Entry entry)
	{
		String[] fields = { "Buy price (low)", "Sell price (high)", "Profit after tax", "ROI %" };
		VarrockWatchlist.Field[] fieldValues = { VarrockWatchlist.Field.LOW, VarrockWatchlist.Field.HIGH, VarrockWatchlist.Field.PROFIT, VarrockWatchlist.Field.ROI };
		String field = (String) JOptionPane.showInputDialog(this, "Alert on which value?", "Set alert", JOptionPane.PLAIN_MESSAGE, null, fields, fields[0]);
		if (field == null)
		{
			return;
		}
		String[] ops = { "Above", "Below" };
		String op = (String) JOptionPane.showInputDialog(this, "Notify when the value goes:", "Set alert", JOptionPane.PLAIN_MESSAGE, null, ops, ops[0]);
		if (op == null)
		{
			return;
		}
		String valueStr = JOptionPane.showInputDialog(this, "Target value (plain number, e.g. 1000000 or 5 for 5% ROI):");
		if (valueStr == null || valueStr.trim().isEmpty())
		{
			return;
		}
		double value;
		try
		{
			value = Double.parseDouble(valueStr.trim().replace(",", ""));
		}
		catch (NumberFormatException ex)
		{
			JOptionPane.showMessageDialog(this, "That doesn't look like a number.");
			return;
		}
		VarrockWatchlist.Field fieldValue = fieldValues[java.util.Arrays.asList(fields).indexOf(field)];
		VarrockWatchlist.Op opValue = op.equals("Above") ? VarrockWatchlist.Op.ABOVE : VarrockWatchlist.Op.BELOW;
		plugin.watchlist().setAlert(entry.itemId, fieldValue, opValue, value);
		refreshWatchlist();
	}

	private static String formatAlertValue(VarrockWatchlist.Entry e)
	{
		return e.alertField == VarrockWatchlist.Field.ROI ? String.format("%.2f%%", e.alertValue) : fmt((long) e.alertValue);
	}

	private JPanel row(Pick p)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createEmptyBorder(0, 0, 6, 0),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));

		JLabel name = new JLabel(p.name);
		name.setForeground(Color.WHITE);
		name.setFont(FontManager.getRunescapeSmallFont());

		JLabel detail = new JLabel(String.format("Buy %s / Sell %s", fmt(p.low), fmt(p.high)));
		detail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		detail.setFont(FontManager.getRunescapeSmallFont());

		JPanel left = new JPanel();
		left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
		left.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		left.add(name);
		left.add(detail);

		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		right.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel profit = new JLabel(fmt(p.profit) + " (" + p.score + ")");
		profit.setForeground(p.profit >= 0 ? Color.GREEN.darker() : Color.RED);
		profit.setFont(FontManager.getRunescapeSmallFont());
		right.add(profit);

		row.add(left, BorderLayout.WEST);
		row.add(right, BorderLayout.EAST);
		row.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				LinkBrowser.browse("https://www.varrock.app/item/" + p.id);
			}
		});
		row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		return row;
	}

	private static String fmt(long n)
	{
		return String.format("%,d", n);
	}

	/** Mirrors the JSON shape returned by ge-edge's api/picks.js exactly. */
	static class Pick
	{
		int id;
		String name;
		long low;
		long high;
		long profit;
		double roi;
		@SerializedName("d1Vol")
		long d1Vol;
		int score;
	}

	static class PicksResponse
	{
		long updatedAt;
		List<Pick> picks;
	}

	/** Mirrors the JSON shape returned by ge-edge's api/flips.js exactly. */
	static class FlipsResponse
	{
		long updatedAt;
		String preset;
		List<Pick> flips;
	}
}
