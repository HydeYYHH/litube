package com.hhst.youtubelite.util;

import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.R;
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;

public final class ImageUtils {

	private static final int THUMB = R.drawable.bg_thumbnail_placeholder;

	private ImageUtils() {
	}

	public static void loadThumb(@NonNull final ImageView view, @Nullable final String url) {
		loadThumb(view, url, null);
	}

	public static void loadThumb(@NonNull final ImageView view,
	                             @Nullable final String url,
	                             @Nullable final Callback callback) {
		if (url == null || url.isBlank()) {
			showThumb(view);
			return;
		}
		final var request = Picasso.get()
				.load(url)
				.placeholder(THUMB)
				.error(THUMB);
		if (callback == null) {
			request.into(view);
			return;
		}
		request.into(view, callback);
	}

	public static void showThumb(@NonNull final ImageView view) {
		view.setImageResource(THUMB);
	}
}
