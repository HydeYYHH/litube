package com.hhst.youtubelite.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textfield.TextInputEditText;

public class NestedScrollableTextInputEditText extends TextInputEditText {
	private int touchSlop;
	private float downX;
	private float downY;

	public NestedScrollableTextInputEditText(@NonNull Context context) {
		super(context);
		init();
	}

	public NestedScrollableTextInputEditText(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	public NestedScrollableTextInputEditText(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init();
	}

	private void init() {
		touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		ViewParent parent = getParent();
		int action = event.getActionMasked();
		if (parent != null) {
			if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
				parent.requestDisallowInterceptTouchEvent(true);
			} else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
				parent.requestDisallowInterceptTouchEvent(false);
			}
		}
		if (action == MotionEvent.ACTION_DOWN) {
			downX = event.getX();
			downY = event.getY();
		} else if (action == MotionEvent.ACTION_UP) {
			if (Math.abs(event.getX() - downX) <= touchSlop && Math.abs(event.getY() - downY) <= touchSlop) {
				performClick();
			}
		}
		return super.onTouchEvent(event);
	}

	@Override
	public boolean performClick() {
		return super.performClick();
	}
}

