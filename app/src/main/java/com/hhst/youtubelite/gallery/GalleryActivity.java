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
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.util.DownloadStorageUtils;

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
 * Show image in full screen mode.
 */
@AndroidEntryPoint
public class GalleryActivity extends AppCompatActivity {
	private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

	// thumbnail filenames, used for saving or caching
	private final List<String> filenames = new ArrayList<>();
	// thumbnail files to cache
	private final List<File> files = new ArrayList<>();
	// thumbnail resource urls
	private List<String> urls = new ArrayList<>();
	// current position in the pager
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

		// destroy this activity when click image or button
		findViewById(R.id.btnClose).setOnClickListener(view -> finish());

		// Get the list of URLs and filenames from intent
		List<String> urlList = getIntent().getStringArrayListExtra("thumbnails");
		String baseFilename = getIntent().getStringExtra("filename");

		urls = urlList;
		if (urls == null) urls = new ArrayList<>();
		// Generate filenames for each image
		for (int i = 0; i < urls.size(); i++) {
			filenames.add(baseFilename + "_" + i);
			files.add(null); // Initialize with null files
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
			case 0: // Save
				saveCurrentImage(url, filename);
				return;
			case 1: // Share
				File file = new File(getCacheDir(), filename + ".jpg");
				// download thumbnail to local cache directory and send it
				ioExecutor.execute(() -> {
					try {
						if (!file.exists()) FileUtils.copyURLToFile(new URL(url), file);
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
						Log.e(getString(R.string.failed_to_download_thumbnail), Log.getStackTraceString(e));
						runOnUiThread(() -> Toast.makeText(this, R.string.failed_to_download_thumbnail, Toast.LENGTH_SHORT).show());
					}
				});
		}
	}

	private void saveCurrentImage(@NonNull final String url, @Nullable final String filename) {
		ioExecutor.execute(() -> {
			try {
				final String displayName = sanitizeFileName(filename) + ".jpg";
				DownloadStorageUtils.saveUrlToDownloads(this, new URL(url), displayName);
				runOnUiThread(() -> Toast.makeText(this, getString(R.string.download_finished, displayName, DownloadStorageUtils.getDownloadsLocationLabel(this)), Toast.LENGTH_SHORT).show());
			} catch (Exception e) {
				Log.e(getString(R.string.failed_to_download_thumbnail), Log.getStackTraceString(e));
				runOnUiThread(() -> Toast.makeText(this, R.string.failed_to_download_thumbnail, Toast.LENGTH_SHORT).show());
			}
		});
	}

	@NonNull
	private String sanitizeFileName(@Nullable final String fileName) {
		final String safeName = fileName == null || fileName.isBlank() ? "thumbnail" : fileName;
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
