package app.varrock.plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * A thin client for Supabase's own REST API (PostgREST for data, GoTrue for auth). No Supabase
 * SDK dependency: two endpoints and three verbs is not worth pulling in a library for, and it
 * keeps this plugin's dependency footprint small, which matters for something that runs inside
 * someone else's game client.
 *
 * Uses RuneLite's own shared {@link OkHttpClient} (injected by the plugin, never built here)
 * rather than java.net.http, since the Plugin Hub's own automated checks reject plugins that
 * build their own HTTP client instead of reusing RuneLite's (it respects the user's proxy and
 * connection settings, and avoids every plugin spinning up its own connection pool).
 *
 * Every call here only ever touches the signed-in player's own rows: the tables this plugin
 * writes to (trades, offers) are row-level-security scoped to auth.uid() = user_id on the
 * Supabase project, the same policies the Varrock website itself relies on. See
 * ge-edge/docs/supabase-schema.sql.
 */
@Slf4j
class VarrockApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final OkHttpClient http;
	private final String baseUrl;
	private final String anonKey;

	private volatile String accessToken;
	private volatile String refreshToken;
	private volatile String userId;

	VarrockApiClient(OkHttpClient http, String baseUrl, String anonKey)
	{
		this.http = http;
		// Trim a trailing slash so URL building below never produces a double slash.
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.anonKey = anonKey;
	}

	boolean isSignedIn()
	{
		return accessToken != null && userId != null;
	}

	String userId()
	{
		return userId;
	}

	/**
	 * Signs in with the player's Varrock email/password. Throws with a message safe to show in
	 * the UI on any failure (bad credentials, unreachable project, misconfigured URL/key).
	 */
	void signIn(String email, String password) throws Exception
	{
		JsonObject body = new JsonObject();
		body.addProperty("email", email);
		body.addProperty("password", password);

		Request req = new Request.Builder()
			.url(baseUrl + "/auth/v1/token?grant_type=password")
			.header("apikey", anonKey)
			.post(RequestBody.create(JSON, body.toString()))
			.build();

		try (Response res = http.newCall(req).execute())
		{
			String bodyText = res.body() != null ? res.body().string() : "";
			if (!res.isSuccessful())
			{
				throw new VarrockApiException(describeAuthError(res.code(), bodyText));
			}

			JsonObject json = new JsonParser().parse(bodyText).getAsJsonObject();
			accessToken = json.get("access_token").getAsString();
			refreshToken = json.has("refresh_token") ? json.get("refresh_token").getAsString() : null;
			userId = json.getAsJsonObject("user").get("id").getAsString();
			log.info("Varrock: signed in");
		}
	}

	void signOut()
	{
		accessToken = null;
		refreshToken = null;
		userId = null;
	}

	private String describeAuthError(int code, String bodyText)
	{
		try
		{
			JsonObject json = new JsonParser().parse(bodyText).getAsJsonObject();
			if (json.has("error_description"))
			{
				return json.get("error_description").getAsString();
			}
			if (json.has("msg"))
			{
				return json.get("msg").getAsString();
			}
		}
		catch (Exception ignored)
		{
			// fall through to the generic message below
		}
		return "Sign-in failed (HTTP " + code + ")";
	}

	/** Refreshes the session once, used when a data call comes back 401. Returns true on success. */
	private boolean tryRefresh()
	{
		if (refreshToken == null)
		{
			return false;
		}
		try
		{
			JsonObject body = new JsonObject();
			body.addProperty("refresh_token", refreshToken);
			Request req = new Request.Builder()
				.url(baseUrl + "/auth/v1/token?grant_type=refresh_token")
				.header("apikey", anonKey)
				.post(RequestBody.create(JSON, body.toString()))
				.build();
			try (Response res = http.newCall(req).execute())
			{
				if (!res.isSuccessful())
				{
					return false;
				}
				String bodyText = res.body() != null ? res.body().string() : "";
				JsonObject json = new JsonParser().parse(bodyText).getAsJsonObject();
				accessToken = json.get("access_token").getAsString();
				if (json.has("refresh_token"))
				{
					refreshToken = json.get("refresh_token").getAsString();
				}
				return true;
			}
		}
		catch (Exception e)
		{
			log.warn("Varrock: session refresh failed", e);
			return false;
		}
	}

	/** Upserts one row into a table. The table's primary key (user_id, id) makes this idempotent. */
	void upsert(String table, JsonObject row, Consumer<Exception> onError)
	{
		call(() -> new Request.Builder()
			.url(baseUrl + "/rest/v1/" + table)
			.header("apikey", anonKey)
			.header("Authorization", "Bearer " + accessToken)
			.header("Prefer", "resolution=merge-duplicates,return=minimal")
			.post(RequestBody.create(JSON, row.toString()))
			.build(), onError);
	}

	/** Deletes one row by its own id (always further scoped to this user by RLS). */
	void deleteById(String table, String id, Consumer<Exception> onError)
	{
		call(() -> new Request.Builder()
			.url(baseUrl + "/rest/v1/" + table + "?id=eq." + id)
			.header("apikey", anonKey)
			.header("Authorization", "Bearer " + accessToken)
			.delete()
			.build(), onError);
	}

	private interface RequestBuilder
	{
		Request build();
	}

	private void call(RequestBuilder builder, Consumer<Exception> onError)
	{
		if (!isSignedIn())
		{
			onError.accept(new VarrockApiException("Not signed in"));
			return;
		}
		try
		{
			int code;
			String bodyText;
			try (Response res = http.newCall(builder.build()).execute())
			{
				code = res.code();
				bodyText = res.body() != null ? res.body().string() : "";
			}
			if (code == 401 && tryRefresh())
			{
				try (Response res = http.newCall(builder.build()).execute())
				{
					code = res.code();
					bodyText = res.body() != null ? res.body().string() : "";
				}
			}
			if (code >= 300)
			{
				onError.accept(new VarrockApiException("Varrock sync failed (HTTP " + code + "): " + bodyText));
			}
		}
		catch (IOException e)
		{
			onError.accept(e);
		}
	}

	static class VarrockApiException extends Exception
	{
		VarrockApiException(String message)
		{
			super(message);
		}
	}
}
