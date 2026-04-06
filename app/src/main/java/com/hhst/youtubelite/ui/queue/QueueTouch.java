package com.hhst.youtubelite.ui.queue;

import android.view.View;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

public final class QueueTouch extends ItemTouchHelper.SimpleCallback {
	private static final float DRAGGED_SCALE = 1.02f;
	private static final float IDLE_SCALE = 1.0f;
	private static final float DRAGGED_ALPHA = 1.0f;
	private static final float IDLE_ALPHA = 1.0f;
	private static final long LIFT_ANIMATION_DURATION_MS = 110L;
	private static final long RELEASE_ANIMATION_DURATION_MS = 220L;
	private static final int MAX_EDGE_SCROLL_PX = 72;
	@NonNull
	private final MoveCallback moveCallback;
	@NonNull
	private final DragStateCallback dragStateCallback;

	public QueueTouch(@NonNull final MoveCallback moveCallback) {
		this(moveCallback, DragStateCallback.NO_OP);
	}

	public QueueTouch(@NonNull final MoveCallback moveCallback,
	                  @NonNull final DragStateCallback dragStateCallback) {
		super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
		this.moveCallback = moveCallback;
		this.dragStateCallback = dragStateCallback;
	}

	@Override
	public boolean onMove(@NonNull final RecyclerView recyclerView,
	                      @NonNull final RecyclerView.ViewHolder viewHolder,
	                      @NonNull final RecyclerView.ViewHolder target) {
		return moveCallback.onMove(
				viewHolder.getBindingAdapterPosition(),
				target.getBindingAdapterPosition());
	}

	@Override
	public void onSelectedChanged(final RecyclerView.ViewHolder viewHolder, final int actionState) {
		super.onSelectedChanged(viewHolder, actionState);
		if (viewHolder == null) return;
		final boolean dragging = actionState == ItemTouchHelper.ACTION_STATE_DRAG;
		dragStateCallback.onDragStateChanged(dragging);
		if (dragging) {
			animateDraggedState(viewHolder.itemView);
		}
	}

	@Override
	public void clearView(@NonNull final RecyclerView recyclerView,
	                      @NonNull final RecyclerView.ViewHolder viewHolder) {
		super.clearView(recyclerView, viewHolder);
		animateReleasedState(viewHolder.itemView);
		dragStateCallback.onDragStateChanged(false);
		dragStateCallback.onDragFinished();
	}

	@Override
	public float getMoveThreshold(@NonNull final RecyclerView.ViewHolder viewHolder) {
		return 0.16f;
	}

	@Override
	public int interpolateOutOfBoundsScroll(@NonNull final RecyclerView recyclerView,
	                                        final int viewSize,
	                                        final int viewSizeOutOfBounds,
	                                        final int totalSize,
	                                        final long msSinceStartScroll) {
		if (viewSizeOutOfBounds == 0) {
			return 0;
		}
		final int direction = viewSizeOutOfBounds > 0 ? 1 : -1;
		final float distanceFraction = Math.min(1.0f, Math.abs(viewSizeOutOfBounds) / (float) viewSize);
		return Math.round((8 + (MAX_EDGE_SCROLL_PX - 8) * distanceFraction * distanceFraction) * direction);
	}

	@Override
	public void onSwiped(@NonNull final RecyclerView.ViewHolder viewHolder, final int direction) {
	}

	private void animateDraggedState(@NonNull final View itemView) {
		itemView.animate()
				.cancel();
		itemView.setPressed(false);
		itemView.animate()
				.scaleX(DRAGGED_SCALE)
				.scaleY(DRAGGED_SCALE)
				.alpha(DRAGGED_ALPHA)
				.translationZ(12f)
				.setDuration(LIFT_ANIMATION_DURATION_MS)
				.start();
	}

	private void animateReleasedState(@NonNull final View itemView) {
		itemView.animate()
				.cancel();
		itemView.animate()
				.scaleX(IDLE_SCALE)
				.scaleY(IDLE_SCALE)
				.alpha(IDLE_ALPHA)
				.translationZ(0f)
				.setDuration(RELEASE_ANIMATION_DURATION_MS)
				.setInterpolator(new OvershootInterpolator(1.15f))
				.start();
	}

	public interface MoveCallback {
		boolean onMove(int from, int to);
	}

	public interface DragStateCallback {
		DragStateCallback NO_OP = new DragStateCallback() {
			@Override
			public void onDragStateChanged(final boolean dragging) {
			}

			@Override
			public void onDragFinished() {
			}
		};

		void onDragStateChanged(boolean dragging);

		void onDragFinished();
	}
}
