package com.hhst.youtubelite.extension;

import android.content.Context;
import android.content.Intent;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hhst.youtubelite.R;

public class RestartDialog {

  public static void show(Context context) {
    new MaterialAlertDialogBuilder(context)
        .setMessage(R.string.request_restart)
        .setCancelable(false)
        .setPositiveButton(R.string.restart, (dialog, id) -> restartApp(context))
        .setNegativeButton(context.getString(R.string.cancel), (dialog, id) -> dialog.dismiss())
        .create()
        .show();
  }

  private static void restartApp(Context context) {
    Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
    if (intent != null) {
      intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
      context.startActivity(intent);
    }
  }
}
