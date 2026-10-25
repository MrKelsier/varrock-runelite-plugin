package app.varrock.plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * A thin client for Supabase's own REST API (PostgREST for data, GoTrue for auth). No Supabase
 * SDK dependency: two endpoints and three verbs is not worth pulling in a library for, and it
 * keeps this plugin's dependency footprint small, which matters for something that runs inside
 * someone else's game client.
 *
 * Every call here only ever touches the signed-in player's own rows: the tables this plugin
 * writes to (trades, offers) are row-level-security scoped to auth.uid() = user_id on the
 * Supabase project, the same policies the Varrock website itself relies on. See
 * ge-edge/docs/supabase-schema.sql.
 */
@Slf4j
class VarrockApiClient
{
	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(8))
		.build();

	private final String baseUrl;
	private final String anonKey;

	private volatile String accessToken;
	private volatile String refreshToken;
	private volatile String userId;

	VarrockApiClient(String baseUrl, String anonKey)
	{
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

		HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/v1/token?grant_type=password"))
			.header("apikey", anonKey)
			.header("Content-Type", "application/json")
			.timeout(Duration.ofSeconds(10))
			.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
			.build();

		HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() != 200)
		{
			throw new VarrockApiException(describeAuthError(res));
		}

		JsonObject json = new JsonParser().parse(res.body()).getAsJsonObject();
		accessToken = json.get("access_token").getAsString();
		refreshToken = json.has("refresh_token") ? json.get("refresh_token").getAsString() : null;
		userId = json.getAsJsonObject("user").get("id").getAsString();
		log.info("Varrock: signed in");
	}

	void signOut()
	{
		accessToken = null;
		refreshToken = null;
		userId = null;
	}

	private String describeAuthError(HttpResponse<String> res)
	{
		try
		{
			JsonObject json = new JsonParser().parse(res.body()).getAsJsonObject();
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
		return "Sign-in failed (HTTP " + res.statusCode() + ")";
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
			HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/v1/token?grant_type=refresh_token"))
				.header("apikey", anonKey)
				.header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(10))
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200)
			{
				return false;
			}
			JsonObject json = new JsonParser().parse(res.body()).getAsJsonObject();
			accessToken = json.get("access_token").getAsString();
			if (json.has("refresh_token"))
			{
				refreshToken = json.get("refresh_token").getAsString();
			}
			return true;
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
		call(() -> HttpRequest.newBuilder(URI.create(baseUrl + "/rest/v1/" + table))
			.header("apikey", anonKey)
			.header("Authorization", "Bearer " + accessToken)
			.header("Content-Type", "application/json")
			.header("Prefer", "resolution=merge-duplicates,return=minimal")
			.timeout(Duration.ofSeconds(10))
			.POST(HttpRequest.BodyPublishers.ofString(row.toString()))
			.build(), onError);
	}

	/** Deletes one row by its own id (always further scoped to this user by RLS). */
	void deleteById(String table, String id, Consumer<Exception> onError)
	{
		call(() -> HttpRequest.newBuilder(URI.create(baseUrl + "/rest/v1/" + table + "?id=eq." + id))
			.header("apikey", anonKey)
			.header("Authorization", "Bearer " + accessToken)
			.timeout(Duration.ofSeconds(10))
			.DELETE()
			.build(), onError);
	}

	private interface RequestBuilder
	{
		HttpRequest build();
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
			HttpResponse<String> res = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() == 401 && tryRefresh())
			{
				res = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			}
			if (res.statusCode() >= 300)
			{
				onError.accept(new VarrockApiException("Varrock sync failed (HTTP " + res.statusCode() + "): " + res.body()));
			}
		}
		catch (Exception e)
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
