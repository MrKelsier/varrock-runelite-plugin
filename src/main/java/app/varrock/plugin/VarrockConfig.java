package app.varrock.plugin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * Nothing here ever changes what the plugin does: every field is only used to sign in and sync.
 * There is no "premium" setting, no license key, no feature this config can lock. See README.md.
 */
@ConfigGroup("varrock")
public interface VarrockConfig extends Config
{
	@ConfigSection(
		name = "Varrock account",
		description = "Sign in to sync your GE slots and trades to varrock.app. Entirely optional: the plugin's own GE tracking works without this.",
		position = 0
	)
	String accountSection = "account";

	@ConfigItem(
		keyName = "supabaseUrl",
		name = "Supabase project URL",
		description = "Varrock's own project. Only change this if you are running your own fork against a different backend.",
		section = accountSection,
		position = 0
	)
	default String supabaseUrl()
	{
		return "https://tqcqniymzktldxeebxmg.supabase.co";
	}

	@ConfigItem(
		keyName = "supabaseAnonKey",
		name = "Supabase anon key",
		description = "Varrock's own public key. Safe to ship here: it only ever acts as your own signed-in account thanks to row level security.",
		section = accountSection,
		position = 1
	)
	default String supabaseAnonKey()
	{
		return "sb_publishable_gs5p23XTyBWbTXN5zxtLsw_ym8Eya_C";
	}

	@ConfigItem(
		keyName = "email",
		name = "Email",
		description = "Your Varrock account email.",
		section = accountSection,
		position = 2
	)
	default String email()
	{
		return "";
	}

	@ConfigItem(
		keyName = "password",
		name = "Password",
		description = "Your Varrock account password. Stored in RuneLite's plain config file, the same as most RuneLite plugin credentials: use a password unique to Varrock, not one reused elsewhere.",
		section = accountSection,
		position = 3,
		secret = true
	)
	default String password()
	{
		return "";
	}

	@ConfigSection(
		name = "Grand Exchange overlay",
		description = "A panel next to the GE offer screen with live flip stats and your buy-limit usage for whichever item you've selected. No account needed.",
		position = 1
	)
	String overlaySection = "overlay";

	@ConfigItem(
		keyName = "showGeOverlay",
		name = "Show flip overlay",
		description = "Shows margin, ROI, score, daily volume and your tracked buy-limit usage next to the Grand Exchange offer screen.",
		section = overlaySection,
		position = 0
	)
	default boolean showGeOverlay()
	{
		return true;
	}

	@ConfigSection(
		name = "Watchlist alerts",
		description = "Notifications for the sidebar watchlist's price alerts. The watchlist itself is always local to this client, account or not.",
		position = 2
	)
	String alertsSection = "alerts";

	@ConfigItem(
		keyName = "notifyOnAlerts",
		name = "Notify on alerts",
		description = "Shows a RuneLite notification when a watched item's price crosses the target you set for it.",
		section = alertsSection,
		position = 0
	)
	default boolean notifyOnAlerts()
	{
		return true;
	}

	@ConfigSection(
		name = "Sync",
		description = "What gets sent to your Varrock account.",
		position = 3
	)
	String syncSection = "sync";

	@ConfigItem(
		keyName = "syncGeSlots",
		name = "Sync GE slots",
		description = "Mirror your 8 Grand Exchange slots live, so they show up on varrock.app on any device.",
		section = syncSection,
		position = 0
	)
	default boolean syncGeSlots()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncTrades",
		name = "Log completed trades",
		description = "When an offer fills (fully or partially), log it to your Varrock Profit tracker automatically.",
		section = syncSection,
		position = 1
	)
	default boolean syncTrades()
	{
		return true;
	}
}
