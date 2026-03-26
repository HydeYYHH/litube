package com.hhst.youtubelite.gallery;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.github.chrisbanes.photoview.PhotoView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hhst.youtubelite.R;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class ImageFragment extends Fragment {
	private static final String ARG_IMAGE_URL = "image_url";

	private String url;

	public static ImageFragment newInstance(String url) {
		ImageFragment fragment = new ImageFragment();
		Bundle args = new Bundle();
		args.putString(ARG_IMAGE_URL, url);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (getArguments() != null) url = getArguments().getString(ARG_IMAGE_URL);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		PhotoView photoView = new PhotoView(requireContext());

		if (url == null || url.isEmpty()) {
			Toast.makeText(requireContext(), "Image URL is empty", Toast.LENGTH_SHORT).show();
			return photoView;
		}

		Glide.with(this)
				.load(url)
				.diskCacheStrategy(DiskCacheStrategy.ALL)
				.into(photoView);

		// Set long click listener to show context menu
		photoView.setOnLongClickListener(view -> {
			showContextMenu();
			return true;
		});

		return photoView;
	}

	private void showContextMenu() {
		if (getActivity() instanceof GalleryActivity activity) {
			AlertDialog menu = new MaterialAlertDialogBuilder(requireContext()).setCancelable(true).setItems(new CharSequence[]{getString(R.string.save), getString(R.string.share)}, (dialog, which) -> activity.onContextMenuClicked(which)).create();
			menu.show();
		}
	}
}