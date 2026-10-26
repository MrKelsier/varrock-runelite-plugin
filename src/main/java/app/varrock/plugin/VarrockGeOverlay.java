package app.varrock.plugin;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Draws the same free, unpersonalized flip stats the website's Dashboard shows (margin, ROI,
 * score, daily volume), plus this plugin's own locally-tracked buy-limit usage, next to the
 * Grand Exchange offer screen for whichever item the player currently has selected there. Works
 * with no account at all, identical for every install: see docs/PLAN.md's "never sell flipping
 * power" rule in the ge-edge project. Toggleable via VarrockConfig#showGeOverlay.
 */
class VarrockGeOverlay extends OverlayPanel
{
	private final Client client;
	private final VarrockConfig config;
	private final VarrockPlugin plugin;

	@Inject
	VarrockGeOverlay(Client client, VarrockConfig config, VarrockPlugin plugin)
	{
		this.client = client;
		this.config = config;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showGeOverlay())
		{
			return null;
		}

		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		Widget desc = client.getWidget(InterfaceID.GeOffers.SETUP_DESC);
		if (setup == null || desc == null || setup.isHidden() || desc.isHidden())
		{
			return null;
		}

		int itemId = desc.getItemId();
		if (itemId <= 0)
		{
			return null;
		}

		VarrockPlugin.ItemStats stats = plugin.statsFor(itemId);
		if (stats == null)
		{
			return null;
		}

		Rectangle bounds = setup.getBounds();
		setPreferredLocation(new Point(bounds.x + bounds.width + 8, bounds.y));

		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder().text("Varrock").color(new Color(255, 150, 50)).build());
		panelComponent.getChildren().add(LineComponent.builder().left("Buy / Sell").right(fmt(stats.low) + " / " + fmt(stats.high)).build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Margin")
			.right(fmt(stats.profit))
			.rightColor(stats.profit >= 0 ? Color.GREEN : Color.RED)
			.build());
		panelComponent.getChildren().add(LineComponent.builder().left("ROI").right(String.format("%.1f%%", stats.roi * 100)).build());
		panelComponent.getChildren().add(LineComponent.builder().left("Score").right(stats.score + " / 100").build());
		panelComponent.getChildren().add(LineComponent.builder().left("Day volume").right(fmt(stats.d1Vol)).build());

		if (stats.limit != null && stats.limit > 0)
		{
			long bought = plugin.boughtInWindow(itemId);
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Buy limit (tracked)")
				.right(fmt(bought) + " / " + fmt(stats.limit))
				.rightColor(bought >= stats.limit ? Color.RED : Color.WHITE)
				.build());
			long resetSeconds = plugin.secondsUntilLimitFrees(itemId);
			if (resetSeconds > 0)
			{
				panelComponent.getChildren().add(LineComponent.builder().left("Resets in").right(formatDuration(resetSeconds)).build());
			}
		}

		return super.render(graphics);
	}

	private static String fmt(long n)
	{
		return String.format("%,d", n);
	}

	private static String formatDuration(long seconds)
	{
		long h = seconds / 3600;
		long m = (seconds % 3600) / 60;
		return h > 0 ? h + "h " + m + "m" : m + "m";
	}
}
