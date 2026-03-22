package com.hhst.youtubelite.util;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.view.View;
import android.view.ViewPropertyAnimator;

import org.junit.Test;

public class ViewUtilsTest {

	@Test
	public void animateViewAlpha_hidingAlreadyGoneView_doesNotMakeItVisibleFirst() {
		final View view = mock(View.class);
		final ViewPropertyAnimator animator = mock(ViewPropertyAnimator.class);
		when(view.getVisibility()).thenReturn(View.GONE);
		when(view.animate()).thenReturn(animator);
		when(animator.alpha(anyFloat())).thenReturn(animator);
		when(animator.setDuration(anyLong())).thenReturn(animator);
		when(animator.withEndAction(any())).thenReturn(animator);

		ViewUtils.animateViewAlpha(view, 0.0f, View.GONE);

		verify(view, never()).setVisibility(View.VISIBLE);
		verify(view).setVisibility(View.GONE);
	}
}
