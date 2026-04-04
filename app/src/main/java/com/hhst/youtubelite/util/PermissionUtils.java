package com.hhst.youtubelite.util;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

/**
 * Permission helpers for storage and notifications.
 */
public final class PermissionUtils {
	public static final int REQUEST_POST_NOTIFICATIONS = 100;
	public static final int REQUEST_STORAGE_PERMISSION = 2001;

	private PermissionUtils() {
	}

	public static boolean needsPostNotificationsPermission() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
	}

	public static boolean hasPostNotificationsPermission(@NonNull Context context) {
		if (!needsPostNotificationsPermission()) return true;
		return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
						== PackageManager.PERMISSION_GRANTED;
	}

	@SuppressLint("InlinedApi")
	@NonNull
	public static String[] postNotificationsPermission() {
		return new String[]{Manifest.permission.POST_NOTIFICATIONS};
	}

	public static boolean needsLegacyStoragePermission() {
		return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P;
	}

	public static boolean hasDownloadStoragePermission(@NonNull Context context) {
		if (!needsLegacyStoragePermission()) return true;
		return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
						== PackageManager.PERMISSION_GRANTED;
	}

	@NonNull
	public static String[] downloadStoragePermissions() {
		return new String[]{
						Manifest.permission.READ_EXTERNAL_STORAGE,
						Manifest.permission.WRITE_EXTERNAL_STORAGE
		};
	}
}
