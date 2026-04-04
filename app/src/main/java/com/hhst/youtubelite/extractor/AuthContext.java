package com.hhst.youtubelite.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Authentication snapshot used by the extractor.
 */
public record AuthContext(
				@NonNull String source,
				@Nullable String cookies,
				@Nullable String visitorData,
				@Nullable String dataSyncId,
				@Nullable String clientVersion,
				boolean loggedIn,
				boolean premium,
				long createdAtMs) {
}
