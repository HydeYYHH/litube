package com.hhst.youtubelite.extractor;

import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.extractor.potoken.PoTokenContextStore;
import com.hhst.youtubelite.extractor.potoken.PoTokenWebViewContext;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Factory that builds extractor auth snapshots.
 */
@Singleton
public final class AuthContextFactory {

	@NonNull
	private final PoTokenContextStore store;

	@Inject
	public AuthContextFactory(@NonNull PoTokenContextStore store) {
		this.store = store;
	}

	@NonNull
	public AuthContext create(@NonNull String url) {
		PoTokenWebViewContext page = store.getSnapshot();
		String cookieUrl = page != null ? page.url() : url;
		String cookies = normalize(CookieManager.getInstance().getCookie(cookieUrl));
		return new AuthContext(
						"webview",
						cookies,
						firstNonBlank(page != null ? page.visitorData() : null, getCookieValue(cookies, "VISITOR_INFO1_LIVE")),
						page != null ? page.dataSyncId() : null,
						page != null ? page.clientVersion() : null,
						page != null && page.loggedIn(),
						page != null && page.premium(),
						System.currentTimeMillis());
	}

	@Nullable
	private String firstNonBlank(@Nullable String first,
	                             @Nullable String second) {
		return first != null ? first : second;
	}

	@Nullable
	private String getCookieValue(@Nullable String cookies,
	                              @NonNull String name) {
		if (cookies == null || cookies.isEmpty()) {
			return null;
		}
		String prefix = name + "=";
		for (String part : cookies.split(";")) {
			String trimmed = part.trim();
			if (trimmed.startsWith(prefix) && trimmed.length() > prefix.length()) {
				return trimmed.substring(prefix.length());
			}
		}
		return null;
	}

	@Nullable
	private String normalize(@Nullable String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
