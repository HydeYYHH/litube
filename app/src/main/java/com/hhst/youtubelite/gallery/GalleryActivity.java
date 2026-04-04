package com.hhst.youtubelite.gallery;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

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
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.util.DownloadStorageUtils;
import com.hhst.youtubelite.util.ToastUtils;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Full-screen image viewer.
 */
@AndroidEntryPoint
public class GalleryActivity extends AppCompatActivity {
	private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

	// Thumbnail names used for saving or caching.
	private final List<String> filenames = new ArrayList<>();
	// Cached thumbnail files.
	private final List<File> files = new ArrayList<>();
	// Thumbnail URLs.
	private List<String> urls = new ArrayList<>();
	// Current pager index.
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

		// Close the viewer when the user taps the image or the close button.
		findViewById(R.id.btnClose).setOnClickListener(view -> finish());

		// Read the thumbnail list from the intent.
		List<String> urlList = getIntent().getStringArrayListExtra("thumbnails");
		String baseName = getIntent().getStringExtra("filename");

		urls = urlList;
		if (urls == null) urls = new ArrayList<>();
		// Build a stable name for each image.
		for (int i = 0; i < urls.size(); i++) {
			filenames.add(baseName + "_" + i);
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
		int pos = position;
		if (pos >= urls.size()) return;

		String url = urls.get(pos);
		String filename = filenames.get(pos);

		switch (index) {
			case 0: // Save
				saveCurrentImage(url, filename);
				return;
			case 1: // Share
				File file = new File(getCacheDir(), filename + ".jpg");
				// Cache the thumbnail, then share the local file.
				ioExecutor.execute(() -> {
					try {
						if (!file.exists()) FileUtils.copyURLToFile(new URL(url), file);
						files.set(pos, file);
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
						Log.e(getString(R.string.failed_to_download_thumbnail), Log.getStackTraceString(e));
						ToastUtils.show(this, R.string.failed_to_download_thumbnail);
					}
				});
		}
	}

	private void saveCurrentImage(@NonNull String url, @Nullable String filename) {
		ioExecutor.execute(() -> {
			try {
				String displayName = sanitizeFileName(filename) + ".jpg";
				DownloadStorageUtils.saveUrlToDownloads(this, new URL(url), displayName);
				ToastUtils.show(this, getString(R.string.download_finished, displayName, DownloadStorageUtils.getDownloadsLocationLabel(this)));
			} catch (Exception e) {
				Log.e(getString(R.string.failed_to_download_thumbnail), Log.getStackTraceString(e));
				ToastUtils.show(this, R.string.failed_to_download_thumbnail);
			}
		});
	}

	@NonNull
	private String sanitizeFileName(@Nullable String fileName) {
		String safeName = fileName == null || fileName.isBlank() ? "thumbnail" : fileName;
		return safeName.replaceAll("[<>:\"/\\\\|?*]", "_");
	}

	@Override
	public void finish() {
		ioExecutor.execute(() -> {
			for (File file : files) if (file != null) FileUtils.deleteQuietly(file);
		});
		super.finish();
	}

	@Override
	protected void onDestroy() {
		ioExecutor.shutdown();
		super.onDestroy();
	}

/**
 * Component that handles app logic.
 */
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
