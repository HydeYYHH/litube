package com.hhst.youtubelite.gallery;

import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;

import com.github.chrisbanes.photoview.PhotoView;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.util.ImageUtils;
import com.hhst.youtubelite.util.ToastUtils;
import com.squareup.picasso.Callback;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Fragment that displays a downloaded image.
 */
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
		GestureContainer root = new GestureContainer(requireContext());
		PhotoView photoView = new PhotoView(requireContext());
		root.setImage(photoView);
		root.setOnTapListener(() -> {
			if (getActivity() instanceof GalleryActivity activity) activity.finish();
		});
		root.setOnLongPressListener(() -> showContextMenu(photoView, root.getTouchX(), root.getTouchY()));

		if (url == null || url.isEmpty()) {
			ImageUtils.showThumb(photoView);
			ToastUtils.show(requireContext(), "Image URL is empty");
			return root;
		}

		ImageUtils.loadThumb(photoView, url, new Callback() {
			@Override
			public void onSuccess() {
			}

			@Override
			public void onError(Exception e) {
				Log.e("ImageFragment", "Failed to load image: " + url, e);
				if (isAdded() && getContext() != null) {
					ToastUtils.show(requireContext(), R.string.failed_to_load_image);
				}
			}
		});

		return root;
	}

	private void showContextMenu(@NonNull View target, float touchX, float touchY) {
		if (getActivity() instanceof GalleryActivity activity) {
			ViewGroup root = requireActivity().findViewById(android.R.id.content);
			View anchor = new View(requireContext());
			FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1, 1);
			int[] loc = new int[2];
			target.getLocationOnScreen(loc);
			int[] rootLoc = new int[2];
			root.getLocationOnScreen(rootLoc);
			params.leftMargin = Math.round(loc[0] - rootLoc[0] + touchX);
			params.topMargin = Math.round(loc[1] - rootLoc[1] + touchY);
			root.addView(anchor, params);

			PopupMenu menu = new PopupMenu(requireContext(), anchor);
			menu.getMenuInflater().inflate(R.menu.gallery_context_menu, menu.getMenu());
			menu.setOnMenuItemClickListener(item -> {
				int id = item.getItemId();
				if (id == R.id.gallery_save) {
					activity.onContextMenuClicked(0);
					return true;
				}
				if (id == R.id.gallery_share) {
					activity.onContextMenuClicked(1);
					return true;
				}
				return false;
			});
			menu.setOnDismissListener(dialog -> root.removeView(anchor));
			menu.show();
		}
	}

/**
 * Component that handles app logic.
 */
	private static final class GestureContainer extends FrameLayout {
		private final GestureDetector detector;
		private Runnable tap;
		private Runnable longPress;
		private float touchX;
		private float touchY;

		GestureContainer(@NonNull android.content.Context context) {
			super(context);
			detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
				@Override
				public boolean onDown(@NonNull MotionEvent e) {
					return true;
				}

				@Override
				public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
					if (tap != null) tap.run();
					return true;
				}

				@Override
				public void onLongPress(@NonNull MotionEvent e) {
					if (longPress != null) longPress.run();
				}
			});
		}

		void setImage(@NonNull View view) {
			addView(view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
		}

		void setOnTapListener(@Nullable Runnable action) {
			tap = action;
		}

		void setOnLongPressListener(@Nullable Runnable action) {
			longPress = action;
		}

		float getTouchX() {
			return touchX;
		}

		float getTouchY() {
			return touchY;
		}

		@Override
		public boolean dispatchTouchEvent(MotionEvent ev) {
			touchX = ev.getX();
			touchY = ev.getY();
			detector.onTouchEvent(ev);
			return super.dispatchTouchEvent(ev);
		}
	}
}
