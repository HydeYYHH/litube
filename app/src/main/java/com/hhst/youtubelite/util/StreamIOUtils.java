package com.hhst.youtubelite.util;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import okio.Okio;
import okio.BufferedSource;

public final class StreamIOUtils {

	/**
	 * Reads an input stream into a string using UTF-8 encoding.
	 *
	 * @param inputStream The input stream to read.
	 * @return The string content, or null if an error occurred.
	 */
	@Nullable
	public static String readInputStream(@NonNull final InputStream inputStream) {
		try (BufferedSource source = Okio.buffer(Okio.source(inputStream))) {
			return source.readString(StandardCharsets.UTF_8);
		} catch (final IOException e) {
			Log.e("StreamIOUtils", "Error reading input stream", e);
			return null;
		}
	}

	/**
	 * Reads an input stream into a byte array.
	 *
	 * @param inputStream The input stream to read.
	 * @return The byte array content, or an empty array if an error occurred.
	 */
	@NonNull
	public static byte[] readInputStreamToBytes(@NonNull final InputStream inputStream) {
		try (BufferedSource source = Okio.buffer(Okio.source(inputStream))) {
			return source.readByteArray();
		} catch (final IOException e) {
			Log.e("StreamIOUtils", "Error reading input stream to bytes", e);
			return new byte[0];
		}
	}
}
