package com.hhst.youtubelite;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ErrorDialog {

	public static void show(Context context, String title, String stack) {
		show(context, title, stack, null);
	}

	public static void show(Context context, String title, String stack, DialogInterface.OnDismissListener onDismissListener) {
		if (title == null) title = context.getString(R.string.error_title);

		// Avoid showing dialog in PIP mode
		if (((Activity) context).isInPictureInPictureMode()) return;

		View view = LayoutInflater.from(context).inflate(R.layout.dialog_error, null);
		TextView titleView = view.findViewById(R.id.error_title);
		TextView stackView = view.findViewById(R.id.error_stack);

		titleView.setText(title);
		stackView.setText(stack);

		String finalTitle = title;
		new MaterialAlertDialogBuilder(context).setView(view).setCancelable(true).setOnDismissListener(onDismissListener).setPositiveButton(R.string.copy, (dialog, which) -> {
			copyDebugInfo(context, finalTitle, stack);
			if (onDismissListener != null) onDismissListener.onDismiss(dialog);
		}).setNegativeButton(R.string.close, (dialog, which) -> dialog.dismiss()).show();
	}

	private static void copyDebugInfo(Context context, String title, String stack) {
		try {
			PackageManager pm = context.getPackageManager();
			PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
			String version = pi.versionName;
			String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

			String sb = "App Version: " + version + "\n" + "Date: " + date + "\n" + "Error Message: " + title + "\n" + "Stack Trace:\n" + stack;

			ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
			ClipData clip = ClipData.newPlainText("Debug Info", sb);
			clipboard.setPrimaryClip(clip);

			Toast.makeText(context, R.string.debug_info_copied, Toast.LENGTH_SHORT).show();
		} catch (Exception ignored) {
		}
	}
}
