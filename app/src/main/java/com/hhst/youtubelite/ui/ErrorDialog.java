package com.hhst.youtubelite.ui;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.util.DeviceUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class ErrorDialog {

	private static final String TAG = "ErrorDialog";
	private static final String DEBUG_INFO_LABEL = "Debug Info";

	private ErrorDialog() {
	}

	public static void show(Context context, String title, String stack) {
		show(context, title, stack, null);
	}

	public static void show(Context context, String title, String stack, DialogInterface.OnDismissListener onDismissListener) {
		String displayTitle = (title == null) ? context.getString(R.string.error_title) : title;

		// Avoid showing dialog in PIP mode
		if (context instanceof Activity && DeviceUtils.isInPictureInPictureMode((Activity) context)) return;

		View view = LayoutInflater.from(context).inflate(R.layout.dialog_error, null);
		TextView titleView = view.findViewById(R.id.error_title);
		TextView stackView = view.findViewById(R.id.error_stack);

		titleView.setText(displayTitle);
		stackView.setText(stack);

		new MaterialAlertDialogBuilder(context).setView(view).setCancelable(true).setOnDismissListener(onDismissListener).setPositiveButton(R.string.copy, (dialog, which) -> copyDebugInfo(context, displayTitle, stack)).setNegativeButton(R.string.close, (dialog, which) -> dialog.dismiss()).show();
	}

	private static void copyDebugInfo(Context context, String title, String stack) {
		try {
			PackageManager pm = context.getPackageManager();
			PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
			String version = pi.versionName;
			String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

			String sb = "App Version: " + version + "\n" + "Date: " + date + "\n" + "Error Message: " + title + "\n" + "Stack Trace:\n" + stack;

			DeviceUtils.copyToClipboard(context, DEBUG_INFO_LABEL, sb);
			Toast.makeText(context, R.string.debug_info_copied, Toast.LENGTH_SHORT).show();
		} catch (Exception e) {
			Log.e(TAG, "Failed to copy debug info", e);
		}
	}
}
