package com.hhst.youtubelite.webview;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import com.hhst.youtubelite.R;

public class ZoomableViewGroup extends FrameLayout {

  private static final int NONE = 0;
  private static final int DRAG = 1;
  private static final int ZOOM = 2;
  private static final float MIN_SCALE_SPAN = 50f; // Minimum scale gesture distance
  private final Matrix matrix = new Matrix();
  private final Matrix savedMatrix = new Matrix();
  private final PointF start = new PointF();
  private final float defaultScale = 1f;

  private final float[] m = new float[9];
  private int mode = NONE;
  private boolean isZoomed = false;

  private View child;
  private Button resetButton;
  private Handler hideButtonHandler;
  private Runnable hideButtonRunnable;
  private static final long HIDE_BUTTON_DELAY = 2000;

  private ScaleGestureDetector mScaleDetector;

  public ZoomableViewGroup(Context context) {
    super(context);
    init(context);
  }

  public ZoomableViewGroup(Context context, AttributeSet attrs) {
    super(context, attrs);
    init(context);
  }

  public ZoomableViewGroup(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    init(context);
  }

  private void init(Context context) {
    mScaleDetector = new ScaleGestureDetector(context, new ScaleListener());
    hideButtonHandler = new Handler(Looper.getMainLooper());
    hideButtonRunnable = () -> {
      if (resetButton != null && isZoomed) {
        resetButton.setVisibility(View.GONE);
      }
    };
    createResetButton(context);
  }

  private void createResetButton(Context context) {
    resetButton = new Button(context);
    resetButton.setText(R.string.reset);
    resetButton.setTextColor(Color.parseColor("#FFCCCB"));
    resetButton.setTextSize(14);

    GradientDrawable background = new GradientDrawable();
    background.setColor(Color.parseColor("#80000000"));
    background.setCornerRadius(20);
    resetButton.setBackground(background);

    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
    params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
    params.setMargins(0, 0, 0, 100);
    resetButton.setLayoutParams(params);

    resetButton.setElevation(10f);
    resetButton.setPadding(30, 15, 30, 15);
    resetButton.setOnClickListener(v -> {
      resetView();
      cancelHideButtonTimer();
    });
    resetButton.setVisibility(View.GONE);

    addView(resetButton);
  }

  private void resetView() {
    matrix.reset();
    savedMatrix.reset();

    if (child != null) {
      child.setScaleX(1f);
      child.setScaleY(1f);
      child.setTranslationX(0f);
      child.setTranslationY(0f);
      child.setClickable(true);
    }

    mode = NONE;
    start.set(0, 0);
    isZoomed = false;

    resetButton.setVisibility(View.GONE);
    cancelHideButtonTimer();
  }

  private void showResetButtonWithTimer() {
    if (resetButton != null && isZoomed) {
      resetButton.setVisibility(View.VISIBLE);
      cancelHideButtonTimer();
      hideButtonHandler.postDelayed(hideButtonRunnable, HIDE_BUTTON_DELAY);
    }
  }

  private void cancelHideButtonTimer() {
    if (hideButtonHandler != null && hideButtonRunnable != null) {
      hideButtonHandler.removeCallbacks(hideButtonRunnable);
    }
  }

  private float getCurrentScale() {
    matrix.getValues(m);
    return m[Matrix.MSCALE_X];
  }

  @Override
  public void addView(View child, int index, ViewGroup.LayoutParams params) {
    super.addView(child, index, params);
    if (child != resetButton) {
      this.child = child;
      this.child.setClickable(true);
      resetButton.bringToFront();
    }
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent ev) {
    if (isTouchingResetButton(ev)) {
      return false;
    }

    // Only handle scaling during explicit multi-finger operations
    if (ev.getPointerCount() > 1) {
      mScaleDetector.onTouchEvent(ev);
      if (mScaleDetector.isInProgress()) {
        isZoomed = true;
        return true;
      }
    }

    // If already zoomed, intercept all events
    return isZoomed;
  }

  private boolean isTouchingResetButton(MotionEvent ev) {
    if (resetButton == null || resetButton.getVisibility() != View.VISIBLE) return false;

    int[] location = new int[2];
    resetButton.getLocationOnScreen(location);

    float x = ev.getRawX();
    float y = ev.getRawY();

    return x >= location[0]
        && x <= location[0] + resetButton.getWidth()
        && y >= location[1]
        && y <= location[1] + resetButton.getHeight();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    mScaleDetector.onTouchEvent(event);

    PointF curr = new PointF(event.getX(), event.getY());
    int action = event.getAction() & MotionEvent.ACTION_MASK;

    switch (action) {
      case MotionEvent.ACTION_DOWN:
        savedMatrix.set(matrix);
        start.set(curr);
        mode = DRAG;
        break;

      case MotionEvent.ACTION_POINTER_DOWN:
        mode = ZOOM;
        break;

      case MotionEvent.ACTION_MOVE:
        if (mode == DRAG) {
          handleDrag(curr);
        }
        break;

      case MotionEvent.ACTION_UP:
        if (!isZoomed) {
          performClick();
        } else {
          showResetButtonWithTimer();
        }
        mode = NONE;
        break;

      case MotionEvent.ACTION_POINTER_UP:
      case MotionEvent.ACTION_CANCEL:
        mode = NONE;
        break;
    }

    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    // If not in zoom state, let child view handle the click
    if (!isZoomed && child != null) {
      return child.performClick();
    }
    return true;
  }

  private void handleDrag(PointF curr) {
    matrix.set(savedMatrix);
    float deltaX = curr.x - start.x;
    float deltaY = curr.y - start.y;
    matrix.postTranslate(deltaX, deltaY);

    constrainMatrix();
    applyMatrix();
  }

  private void constrainMatrix() {
    matrix.getValues(m);
    float scale = m[Matrix.MSCALE_X];

    if (scale <= defaultScale) {
      resetView();
      return;
    }

    float viewWidth = getWidth();
    float viewHeight = getHeight();
    float contentWidth = viewWidth * scale;
    float contentHeight = viewHeight * scale;

    float contentLeft = m[Matrix.MTRANS_X];
    float contentTop = m[Matrix.MTRANS_Y];
    float contentRight = contentLeft + contentWidth;
    float contentBottom = contentTop + contentHeight;

    float adjustX = 0;
    float adjustY = 0;

    if (contentLeft > 0) {
      adjustX = -contentLeft;
    } else if (contentRight < viewWidth) {
      adjustX = viewWidth - contentRight;
    }

    if (contentTop > 0) {
      adjustY = -contentTop;
    } else if (contentBottom < viewHeight) {
      adjustY = viewHeight - contentBottom;
    }

    if (adjustX != 0 || adjustY != 0) {
      matrix.postTranslate(adjustX, adjustY);
    }
  }

  private void applyMatrix() {
    if (child == null) return;

    matrix.getValues(m);

    child.setPivotX(0);
    child.setPivotY(0);
    child.setTranslationX(m[Matrix.MTRANS_X]);
    child.setTranslationY(m[Matrix.MTRANS_Y]);
    child.setScaleX(m[Matrix.MSCALE_X]);
    child.setScaleY(m[Matrix.MSCALE_Y]);

    if (isZoomed) {
      showResetButtonWithTimer();
    } else {
      resetButton.setVisibility(View.GONE);
      cancelHideButtonTimer();
    }
  }

  private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
    @Override
    public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
      // Only start scaling when finger distance is large enough
      if (detector.getCurrentSpan() > MIN_SCALE_SPAN) {
        mode = ZOOM;
        return true;
      }
      return false;
    }

    @Override
    public boolean onScale(ScaleGestureDetector detector) {
      float scaleFactor = detector.getScaleFactor();
      float currentScale = getCurrentScale();
      float newScale = currentScale * scaleFactor;

      float minScale = 1f;
      float maxScale = 3f;
      if (newScale < minScale) {
        scaleFactor = minScale / currentScale;
      } else if (newScale > maxScale) {
        scaleFactor = maxScale / currentScale;
      }

      matrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
      constrainMatrix();
      applyMatrix();
      return true;
    }

    @Override
    public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
      if (getCurrentScale() <= defaultScale) {
        resetView();
      }
    }
  }
}
