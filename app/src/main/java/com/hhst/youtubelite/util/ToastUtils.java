package com.hhst.youtubelite.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

/**
 * Toast helpers for short-lived user feedback.
 */
public final class ToastUtils {

	private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
	private static final Object TOAST_LOCK = new Object();
	@Nullable
	private static Toast toast;
	private static long toastId;
	private static long nextId = 1L;

	private ToastUtils() {
	}

	public static long show(@NonNull Context context, @StringRes int resId) {
		return show(context, resId, Toast.LENGTH_SHORT);
	}

	public static long show(@NonNull Context context, @StringRes int resId, int duration) {
		Context appContext = getAppContext(context);
		long id = next();
		runOnMain(() -> {
			synchronized (TOAST_LOCK) {
				cancelLocked();
				toastId = id;
				toast = Toast.makeText(appContext, resId, duration);
				toast.show();
			}
		});
		return id;
	}

	public static long show(@NonNull Context context, @Nullable CharSequence text) {
		return show(context, text, Toast.LENGTH_SHORT);
	}

	public static long show(@NonNull Context context, @Nullable CharSequence text, int duration) {
		if (text == null) return -1L;
		Context appContext = getAppContext(context);
		long id = next();
		runOnMain(() -> {
			synchronized (TOAST_LOCK) {
				cancelLocked();
				toastId = id;
				toast = Toast.makeText(appContext, text, duration);
				toast.show();
			}
		});
		return id;
	}

	public static void cancel(long id) {
		if (id < 0) return;
		runOnMain(() -> {
			synchronized (TOAST_LOCK) {
				if (toast != null && toastId == id) {
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
		if (toast == null) return;
		toast.cancel();
		toast = null;
		toastId = 0L;
	}

	private static void runOnMain(@NonNull Runnable action) {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			action.run();
			return;
		}
		MAIN_HANDLER.post(action);
	}

	@NonNull
	private static Context getAppContext(@NonNull Context context) {
		Context appContext = context.getApplicationContext();
		return appContext != null ? appContext : context;
	}
}
