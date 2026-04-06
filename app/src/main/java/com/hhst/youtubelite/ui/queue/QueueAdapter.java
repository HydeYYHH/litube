package com.hhst.youtubelite.ui.queue;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.player.queue.QueueItem;
import com.hhst.youtubelite.util.ImageUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.ViewHolder> {
	@NonNull
	private final List<QueueItem> items = new ArrayList<>();
	@NonNull
	private final Actions actions;
	@Nullable
	private String playingId;

	public QueueAdapter(@NonNull final Actions actions) {
		this.actions = actions;
	}

	public void replaceItems(@NonNull final List<QueueItem> newItems, @Nullable final String playingId) {
		items.clear();
		for (final QueueItem item : newItems) {
			items.add(item.copy());
		}
		this.playingId = playingId;
		notifySafe(this::notifyDataSetChanged);
	}

	public int playingPos() {
		return find(items, playingId);
	}

	public boolean moveItem(final int from, final int to) {
		if (!isValidIndex(from) || !isValidIndex(to) || from == to) {
			return false;
		}
		final QueueItem moved = items.remove(from);
		items.add(to, moved);
		notifySafe(() -> notifyItemMoved(from, to));
		return true;
	}

	@Nullable
	public QueueItem removeItem(final int position) {
		if (!isValidIndex(position)) {
			return null;
		}
		final QueueItem removed = items.remove(position);
		notifySafe(() -> notifyItemRemoved(position));
		return removed.copy();
	}

	@NonNull
	public List<QueueItem> snapshotItems() {
		final List<QueueItem> snapshot = new ArrayList<>(items.size());
		for (final QueueItem item : items) {
			snapshot.add(item.copy());
		}
		return snapshot;
	}

	@NonNull
	@Override
	public ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
		final View itemView = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_queue_entry, parent, false);
		return new ViewHolder(itemView);
	}

	@Override
	public void onBindViewHolder(@NonNull final ViewHolder holder, final int position) {
		holder.bind(items.get(position), playingId, actions);
	}

	@Override
	public int getItemCount() {
		return items.size();
	}

	static boolean isPlaying(@Nullable final String itemId,
	                         @Nullable final String videoId) {
		return itemId != null && Objects.equals(itemId, videoId);
	}

	static int find(@NonNull final List<QueueItem> items, @Nullable final String videoId) {
		if (videoId == null) {
			return -1;
		}
		for (int i = 0; i < items.size(); i++) {
			if (Objects.equals(videoId, items.get(i).getVideoId())) {
				return i;
			}
		}
		return -1;
	}

	private boolean isValidIndex(final int index) {
		return index >= 0 && index < items.size();
	}

	private void notifySafe(@NonNull final Runnable task) {
		try {
			task.run();
		} catch (final NullPointerException ignored) {
			// The JVM unit-test stub for RecyclerView.Adapter has no observer list until attached.
		}
	}

	public interface Actions {
		void onPlayRequested(@NonNull QueueItem item);

		void onDeleteRequested(@NonNull QueueItem item);
	}

	static final class ViewHolder extends RecyclerView.ViewHolder {
		@NonNull
		private final ImageView thumbnailView;
		@NonNull
		private final TextView titleView;
		@NonNull
		private final TextView authorView;
		@NonNull
		private final TextView playingBadgeView;
		@NonNull
		private final ImageButton deleteButton;

		ViewHolder(@NonNull final View itemView) {
			super(itemView);
			thumbnailView = itemView.findViewById(R.id.queue_item_thumbnail);
			titleView = itemView.findViewById(R.id.queue_item_title);
			authorView = itemView.findViewById(R.id.queue_item_author);
			playingBadgeView = itemView.findViewById(R.id.queue_item_playing_badge);
			deleteButton = itemView.findViewById(R.id.queue_item_delete);
		}

		void bind(@NonNull final QueueItem item,
		          @Nullable final String playingId,
		          @NonNull final Actions actions) {
			titleView.setText(item.getTitle() == null || item.getTitle().isBlank()
					? item.getUrl()
					: item.getTitle());
			authorView.setText(item.getAuthor() == null || item.getAuthor().isBlank()
					? itemView.getContext().getString(R.string.queue_unknown_author)
					: item.getAuthor());
			
			ImageUtils.loadThumb(thumbnailView, item.getThumbnailUrl());

			final boolean playing = isPlaying(item.getVideoId(), playingId);
			itemView.setActivated(playing);
			itemView.setAlpha(1.0f);
			playingBadgeView.setVisibility(playing ? View.VISIBLE : View.GONE);
			itemView.setOnClickListener(v -> actions.onPlayRequested(item.copy()));
			deleteButton.setOnClickListener(v -> actions.onDeleteRequested(item.copy()));
		}
	}
}
