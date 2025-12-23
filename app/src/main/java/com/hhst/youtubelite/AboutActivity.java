package com.hhst.youtubelite;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AboutActivity extends AppCompatActivity {
	private static final String TAG = "AboutActivity";
	private static final String GITHUB_RELEASE_API = "https://api.github.com/repos/HydeYYHH/litube/releases/latest";
	private final OkHttpClient client = new OkHttpClient();
	private final Gson gson = new Gson();
	private TextView checkUpdateText;
	private View checkUpdateLayout;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdge.enable(this);
		setContentView(R.layout.activity_about);

		View mainView = findViewById(android.R.id.content);
		ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
			Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
			v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
			return insets;
		});

		ImageView iconView = findViewById(R.id.app_icon);
		TextView nameView = findViewById(R.id.app_name);
		TextView versionView = findViewById(R.id.app_version);
		TextView descriptionView = findViewById(R.id.app_description);
		View sourceCodeLayout = findViewById(R.id.source_code_layout);
		checkUpdateLayout = findViewById(R.id.check_update_layout);
		checkUpdateText = findViewById(R.id.check_update_text);
		View clearCacheLayout = findViewById(R.id.clear_cache_layout);
		View exportLogLayout = findViewById(R.id.export_log_layout);

		try {
			PackageManager pm = getPackageManager();
			PackageInfo pi = pm.getPackageInfo(getPackageName(), 0);
			iconView.setImageDrawable(pi.applicationInfo.loadIcon(pm));
			nameView.setText(R.string.app_name);
			versionView.setText(getString(R.string.version, pi.versionName));
		} catch (Exception e) {
			Log.e(TAG, "Failed to load app info", e);
		}

		descriptionView.setText(R.string.app_description);
		sourceCodeLayout.setOnClickListener(v -> {
			Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.source_link)));
			startActivity(intent);
		});

		checkUpdateLayout.setOnClickListener(v -> checkForUpdates());
		clearCacheLayout.setOnClickListener(v -> showClearCacheDialog());
		exportLogLayout.setOnClickListener(v -> exportLogs());
	}

	@Override
	public boolean onSupportNavigateUp() {
		finish();
		return true;
	}

	private void showClearCacheDialog() {
		new MaterialAlertDialogBuilder(this).setTitle(R.string.clear_cache).setMessage(R.string.clear_cache_confirmation).setPositiveButton(R.string.clear, (dialog, which) -> clearAppCache()).setNegativeButton(android.R.string.cancel, null).show();
	}

	private void checkForUpdates() {
		checkUpdateLayout.setEnabled(false);
		checkUpdateText.setText(R.string.checking_for_updates);

		Request request = new Request.Builder().url(GITHUB_RELEASE_API).build();

		client.newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(@NonNull Call call, @NonNull java.io.IOException e) {
				runOnUiThread(() -> {
					checkUpdateLayout.setEnabled(true);
					checkUpdateText.setText(R.string.check_for_updates);
					Toast.makeText(AboutActivity.this, R.string.failed_to_check_for_updates, Toast.LENGTH_SHORT).show();
				});
			}

			@Override
			public void onResponse(@NonNull Call call, @NonNull Response response) {
				try (response) {
					if (!response.isSuccessful())
						throw new java.io.IOException("Unexpected code " + response);

					String body = Objects.requireNonNull(response.body()).string();
					JsonObject json = gson.fromJson(body, JsonObject.class);
					String latest = json.get("tag_name").getAsString();
					String url = json.get("html_url").getAsString();

					String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
					if (isNewerVersion(version, latest)) {
						runOnUiThread(() -> {
							checkUpdateLayout.setEnabled(true);
							checkUpdateText.setText(getString(R.string.update_available, latest));
							checkUpdateLayout.setOnClickListener(v -> {
								Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
								startActivity(intent);
							});
						});
					} else {
						runOnUiThread(() -> {
							checkUpdateLayout.setEnabled(true);
							checkUpdateText.setText(R.string.check_for_updates);
							Toast.makeText(AboutActivity.this, R.string.no_updates_available, Toast.LENGTH_SHORT).show();
						});
					}
				} catch (Exception e) {
					Log.e(TAG, "Update check error", e);
					runOnUiThread(() -> {
						checkUpdateLayout.setEnabled(true);
						checkUpdateText.setText(R.string.check_for_updates);
						Toast.makeText(AboutActivity.this, R.string.failed_to_check_for_updates, Toast.LENGTH_SHORT).show();
					});
				}
			}
		});
	}

	private void clearAppCache() {
		new Thread(() -> {
			try {
				// Clear WebView cache
				runOnUiThread(() -> {
					WebView webView = new WebView(AboutActivity.this);
					webView.clearCache(true);
					WebStorage.getInstance().deleteAllData();
				});

				// Clear app cache directories
				deleteDir(getCacheDir());
				deleteDir(getExternalCacheDir());

				runOnUiThread(() -> Toast.makeText(AboutActivity.this, R.string.cache_cleared, Toast.LENGTH_SHORT).show());
			} catch (Exception e) {
				Log.e(TAG, "Failed to clear cache", e);
			}
		}).start();
	}

	private boolean deleteDir(File dir) {
		if (dir != null && dir.isDirectory()) {
			String[] children = dir.list();
			if (children != null) {
				for (String child : children) {
					boolean success = deleteDir(new File(dir, child));
					if (!success) return false;
				}
			}
			return dir.delete();
		} else if (dir != null && dir.isFile()) return dir.delete();
		else return false;
	}

	private void exportLogs() {
		new Thread(() -> {
			try {
				int pid = android.os.Process.myPid();
				Process process = Runtime.getRuntime().exec("logcat -d -v time --pid " + pid + " *:E");
				BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));

				StringBuilder log = new StringBuilder();
				String line;
				while ((line = bufferedReader.readLine()) != null) {
					log.append(line).append("\n");
				}

				String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
				String fileName = "litube_error_log_" + timeStamp + ".txt";
				File logFile = new File(getExternalCacheDir(), fileName);

				try (FileOutputStream fos = new FileOutputStream(logFile)) {
					fos.write(log.toString().getBytes(StandardCharsets.UTF_8));
				}

				Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", logFile);
				Intent intent = new Intent(Intent.ACTION_SEND);
				intent.setType("text/plain");
				intent.putExtra(Intent.EXTRA_STREAM, contentUri);
				intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

				runOnUiThread(() -> startActivity(Intent.createChooser(intent, getString(R.string.export_error_log))));

			} catch (Exception e) {
				Log.e(TAG, "Log export error", e);
				runOnUiThread(() -> Toast.makeText(this, R.string.failed_to_export_log, Toast.LENGTH_SHORT).show());
			}
		}).start();
	}

	private boolean isNewerVersion(String current, String latest) {
		if (current == null || latest == null) return false;
		
		// Remove 'v' prefix if exists
		String c = current.startsWith("v") ? current.substring(1) : current;
		String l = latest.startsWith("v") ? latest.substring(1) : latest;
		
		String[] currentParts = c.split("\\.");
		String[] latestParts = l.split("\\.");
		int length = Math.max(currentParts.length, latestParts.length);
		
		for (int i = 0; i < length; i++) {
			int cPart = i < currentParts.length ? Integer.parseInt(currentParts[i].replaceAll("\\D", "")) : 0;
			int lPart = i < latestParts.length ? Integer.parseInt(latestParts[i].replaceAll("\\D", "")) : 0;
			if (lPart > cPart) return true;
			if (lPart < cPart) return false;
		}
		return false;
	}
}
