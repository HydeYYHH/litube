package com.hhst.youtubelite.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

public final class ToastUtils {

	private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
	private static final Object TOAST_LOCK = new Object();
	@Nullable
	private static Toast currentToast;
	private static long currentId;
	private static long nextId = 1L;

	private ToastUtils() {
	}

	public static long show(@NonNull final Context context, @StringRes final int resId) {
		return show(context, resId, Toast.LENGTH_SHORT);
	}

	public static long show(@NonNull final Context context, @StringRes final int resId, final int duration) {
		final Context appContext = resolveAppContext(context);
		final long id = next();
		runOnMain(() -> {
			synchronized (TOAST_LOCK) {
				cancelLocked();
				currentId = id;
				currentToast = Toast.makeText(appContext, resId, duration);
				currentToast.show();
			}
		});
		return id;
	}

	public static long show(@NonNull final Context context, @Nullable final CharSequence text) {
		return show(context, text, Toast.LENGTH_SHORT);
	}

	public static long show(@NonNull final Context context, @Nullable final CharSequence text, final int duration) {
		if (text == null) return -1L;
		final Context appContext = resolveAppContext(context);
		final long id = next();
		runOnMain(() -> {
			synchronized (TOAST_LOCK) {
				cancelLocked();
				currentId = id;
				currentToast = Toast.makeText(appContext, text, duration);
				currentToast.show();
			}
		});
		return id;
	}

	public static void cancel(final long id) {
		if (id < 0) return;
		runOnMain(() -> {
			synchronized (TOAST_LOCK) {
				if (currentToast != null && currentId == id) {
					cancelLocked();
				}
			}
		});
	}

	private static long next() {
		synchronized (TOAST_LOCK) {
			return nextId++;
		}
	}

	private static void cancelLocked() {
		if (currentToast == null) return;
		currentToast.cancel();
		currentToast = null;
		currentId = 0L;
	}

	private static void runOnMain(@NonNull final Runnable action) {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			action.run();
			return;
		}
		MAIN_HANDLER.post(action);
	}

	@NonNull
	private static Context resolveAppContext(@NonNull final Context context) {
		final Context appContext = context.getApplicationContext();
		return appContext != null ? appContext : context;
	}
}
