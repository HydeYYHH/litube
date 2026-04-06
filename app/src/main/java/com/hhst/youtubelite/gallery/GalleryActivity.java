package com.hhst.youtubelite.gallery;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.media3.common.util.UnstableApi;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.downloader.service.DownloadService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;

/**
 * Show image in full screen mode.
 */
@AndroidEntryPoint
@UnstableApi
public class GalleryActivity extends AppCompatActivity {

	private static final String TAG = "GalleryActivity";

	@Inject OkHttpClient httpClient;
	@Inject Executor executor;

	private final List<String> filenames = new ArrayList<>();
	private final List<File> files = new ArrayList<>();
	private List<String> urls = new ArrayList<>();
	private int position = 0;

	private ViewPager2 viewPager;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdge.enable(this);
		setContentView(R.layout.activity_gallery);
		ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
			Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
			v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
			return insets;
		});

		viewPager = findViewById(R.id.viewPager);
		findViewById(R.id.btnClose).setOnClickListener(view -> finish());

		List<String> urlList = getIntent().getStringArrayListExtra("thumbnails");
		String baseFilename = getIntent().getStringExtra("filename");

		urls = urlList != null ? urlList : new ArrayList<>();
		for (int i = 0; i < urls.size(); i++) {
			filenames.add(baseFilename + "_" + i);
			files.add(null);
		}

		setupViewPager();
	}

	private void setupViewPager() {
		ImagePagerAdapter adapter = new ImagePagerAdapter(getSupportFragmentManager(), getLifecycle());
		viewPager.setAdapter(adapter);
		viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
			@Override
			public void onPageSelected(int p) {
				super.onPageSelected(p);
				position = p;
			}
		});
	}

	public void onContextMenuClicked(int index) {
		final int currentPosition = position;
		if (currentPosition >= urls.size()) return;

		String url = urls.get(currentPosition);
		String filename = filenames.get(currentPosition);

		switch (index) {
			case 0:
				Intent saveIntent = new Intent(this, DownloadService.class);
				saveIntent.setAction("DOWNLOAD_THUMBNAIL");
				saveIntent.putExtra("thumbnail", url);
				saveIntent.putExtra("filename", filename);
				startService(saveIntent);
				break;
			case 1:
				File file = new File(getCacheDir(), filename + ".jpg");
				executor.execute(() -> {
					try {
						if (!file.exists()) {
							Request request = new Request.Builder().url(url).build();
							try (Response response = httpClient.newCall(request).execute()) {
								if (!response.isSuccessful() || response.body() == null)
									throw new IOException("Failed to download image: " + response);
								try (BufferedSink sink = Okio.buffer(Okio.sink(file))) {
									sink.writeAll(response.body().source());
								}
							}
						}
						files.set(currentPosition, file);
						Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
						Intent shareIntent = new Intent(Intent.ACTION_SEND);
						shareIntent.setType("image/*");
						shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
						shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
						runOnUiThread(() -> {
							if (isFinishing() || isDestroyed()) return;
							startActivity(Intent.createChooser(shareIntent, getString(R.string.share_thumbnail)));
						});
					} catch (IOException e) {
						Log.e(TAG, "Failed to download thumbnail", e);
						runOnUiThread(() -> Toast.makeText(this, R.string.failed_to_download_thumbnail, Toast.LENGTH_SHORT).show());
					}
				});
				break;
		}
	}

	@Override
	public void finish() {
		super.finish();
		executor.execute(() -> {
			for (File file : files) {
				if (file != null && file.exists()) {
					if (!file.delete()) Log.w(TAG, "Failed to delete cache file: " + file.getPath());
				}
			}
		});
	}

	private class ImagePagerAdapter extends FragmentStateAdapter {
		public ImagePagerAdapter(FragmentManager fragmentManager, Lifecycle lifecycle) {
			super(fragmentManager, lifecycle);
		}

		@NonNull
		@Override
		public Fragment createFragment(int position) {
			return ImageFragment.newInstance(urls.get(position));
		}

		@Override
		public int getItemCount() {
			return urls.size();
		}
	}
}
