package com.hhst.youtubelite.player.sponsor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class SponsorOverlayView extends View {
	private final Paint paint = new Paint();
	private List<long[]> segments;
	private long duration;

	public SponsorOverlayView(Context context, AttributeSet attrs) {
		super(context, attrs);
		paint.setColor(Color.parseColor("#4CAF50"));
		paint.setStyle(Paint.Style.FILL);
	}

	public void setData(List<long[]> segments, long duration, TimeUnit unit) {
		this.segments = segments;
		this.duration = unit.toMillis(duration);
		invalidate();
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		super.onDraw(canvas);
		if (segments == null || duration <= 0) return;
		int width = getWidth();
		for (long[] seg : segments) {
			float startX = (float) seg[0] / duration * width;
			float endX = (float) seg[1] / duration * width;
			if (endX < startX) endX = startX;
			canvas.drawRect(startX, 0, endX, getHeight(), paint);
		}
	}
}
