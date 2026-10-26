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
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * The plugin's sidebar panel: the same free, unpersonalized top flip picks the website's own
 * Dashboard shows (see app/varrock/plugin's VarrockApiClient sibling, VarrockPicksClient, and
 * ge-edge's api/picks.js), so there is a reason to glance at RuneLite instead of alt-tabbing to a
 * browser. No account needed to see it, same principle as the website: nothing here is reserved
 * for signed-in or paying users, it is identical for every install.
 */
class VarrockPanel extends PluginPanel
{
	private final JPanel listPanel = new JPanel();
	private final JLabel status = new JLabel("Loading top picks...");

	VarrockPanel()
	{
		super();
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel title = new JLabel("Varrock top picks");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		header.add(title, BorderLayout.WEST);
		JButton openSite = new JButton("Open site");
		openSite.addActionListener(e -> LinkBrowser.browse("https://www.varrock.app/flips"));
		header.add(openSite, BorderLayout.EAST);
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		add(header, BorderLayout.NORTH);

		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));

		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		listPanel.add(status);

		JScrollPane scroll = new JScrollPane(listPanel);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);
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
}
