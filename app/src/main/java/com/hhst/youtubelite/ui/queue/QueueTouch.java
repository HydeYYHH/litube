package com.hhst.youtubelite.ui.queue;

import android.view.View;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Touch helper that reorders queue items by drag and drop.
 */
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

	public QueueTouch(@NonNull MoveCallback moveCallback,
	                  @NonNull DragStateCallback dragStateCallback) {
		super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
		this.moveCallback = moveCallback;
		this.dragStateCallback = dragStateCallback;
	}

	@Override
	public boolean onMove(@NonNull RecyclerView recyclerView,
	                      @NonNull RecyclerView.ViewHolder viewHolder,
	                      @NonNull RecyclerView.ViewHolder target) {
		return moveCallback.onMove(
						viewHolder.getBindingAdapterPosition(),
						target.getBindingAdapterPosition());
	}

	@Override
	public void onSelectedChanged(final RecyclerView.ViewHolder viewHolder, int actionState) {
		super.onSelectedChanged(viewHolder, actionState);
		if (viewHolder == null) return;
		boolean dragging = actionState == ItemTouchHelper.ACTION_STATE_DRAG;
		dragStateCallback.onDragStateChanged(dragging);
		if (dragging) {
			animateDraggedState(viewHolder.itemView);
		}
	}

	@Override
	public void clearView(@NonNull RecyclerView recyclerView,
	                      @NonNull RecyclerView.ViewHolder viewHolder) {
		super.clearView(recyclerView, viewHolder);
		animateReleasedState(viewHolder.itemView);
		dragStateCallback.onDragStateChanged(false);
		dragStateCallback.onDragFinished();
	}

	@Override
	public float getMoveThreshold(@NonNull RecyclerView.ViewHolder viewHolder) {
		return 0.16f;
	}

	@Override
	public int interpolateOutOfBoundsScroll(@NonNull RecyclerView recyclerView,
	                                        final int viewSize,
	                                        final int viewSizeOutOfBounds,
	                                        final int totalSize,
	                                        final long msSinceStartScroll) {
		if (viewSizeOutOfBounds == 0) {
			return 0;
		}
		int direction = viewSizeOutOfBounds > 0 ? 1 : -1;
		float distanceFraction = Math.min(1.0f, Math.abs(viewSizeOutOfBounds) / (float) viewSize);
		return Math.round((8 + (MAX_EDGE_SCROLL_PX - 8) * distanceFraction * distanceFraction) * direction);
	}

	@Override
	public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
	}

	private void animateDraggedState(@NonNull View itemView) {
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

	private void animateReleasedState(@NonNull View itemView) {
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

/**
 * Contract for app logic.
 */
	public interface MoveCallback {
		boolean onMove(int from, int to);
	}

	public interface DragStateCallback {

		void onDragStateChanged(boolean dragging);

		void onDragFinished();
	}
}
