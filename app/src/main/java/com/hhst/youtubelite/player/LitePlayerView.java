package com.hhst.youtubelite.player;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.util.AttributeSet;
import android.util.Rational;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.player.common.Constant;
import com.hhst.youtubelite.player.common.PlayerPreferences;
import com.hhst.youtubelite.player.controller.gesture.PlayerGestureListener;
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager;
import com.hhst.youtubelite.player.sponsor.SponsorOverlayView;
import com.hhst.youtubelite.util.ViewUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import dagger.hilt.android.scopes.ActivityScoped;
import lombok.Getter;

@UnstableApi
@AndroidEntryPoint
@ActivityScoped
public class LitePlayerView extends PlayerView {

    private static final float SUBTITLE_LINE_FRACTION = 0.92f;
    private static final float SUBTITLE_POSITION_FRACTION = 0.5f;

    @Nullable
    private SubtitleView subtitleView;

    @Inject SponsorBlockManager sponsor;
    @Inject Activity activity;
    @Inject PlayerPreferences prefs;

    private PlayerGestureListener gestureListener;

    @Getter
    private boolean isFs = false;

    private int playerWidth = 0;
    private int playerHeight = 0;
    private int normalHeight = 0;

    private OrientationEventListener orientationListener;

    public LitePlayerView(Context context) { super(context); }
    public LitePlayerView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
    public LitePlayerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    public void setGestureListener(PlayerGestureListener listener) { this.gestureListener = listener; }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (gestureListener != null && (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL)) {
            gestureListener.onTouchRelease();
        }
        return super.onTouchEvent(event);
    }

    public void setup() {
        setControllerAnimationEnabled(false);
        setControllerHideOnTouch(false);
        setControllerAutoShow(false);
        setControllerShowTimeoutMs(0);
        setResizeMode(prefs.getResizeMode());

        final ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp instanceof ConstraintLayout.LayoutParams params) {
            params.topMargin = ViewUtils.dpToPx(activity, Constant.TOP_MARGIN_DP);
            params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
            final int screenWidth = ViewUtils.getScreenWidth(activity);
            params.height = (int) (screenWidth * 9 / 16.0);
            setLayoutParams(params);
        }

        addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != playerWidth || bottom - top != playerHeight) {
                playerWidth = right - left;
                playerHeight = bottom - top;
            }
        });

        initOrientationListener();
    }

    private void initOrientationListener() {
        orientationListener = new OrientationEventListener(getContext()) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (!isFs || orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return;

                boolean isPortrait = (orientation >= 0 && orientation < 45) ||
                        (orientation > 315 && orientation < 360) ||
                        (orientation > 135 && orientation < 225);

                if (isPortrait) {
                    exitFullscreen();
                }
            }
        };
    }

    public void show() { setVisibility(VISIBLE); }
    public void hide() { setVisibility(GONE); }

    public void enterPiP() {
        final Rational aspectRatio = new Rational(16, 9);
        final PictureInPictureParams params = new PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build();
        activity.enterPictureInPictureMode(params);
    }

    public void cueing(@NonNull final CueGroup cueGroup) {
        if (subtitleView == null) {
            final SubtitleView defaultSubtitleView = getSubtitleView();
            if (defaultSubtitleView != null) defaultSubtitleView.setVisibility(View.GONE);
            subtitleView = findViewById(R.id.custom_subtitle_view);
        }
        if (subtitleView == null) return;

        final List<Cue> cues = new ArrayList<>();
        for (final Cue cue : cueGroup.cues)
            cues.add(cue.buildUpon()
                    .setLine(SUBTITLE_LINE_FRACTION, Cue.LINE_TYPE_FRACTION)
                    .setLineAnchor(Cue.ANCHOR_TYPE_END)
                    .setPosition(SUBTITLE_POSITION_FRACTION)
                    .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                    .build());
        subtitleView.setCues(cues);
    }

    public void updateSkipMarkers(long duration, TimeUnit unit) {
        final List<long[]> segs = sponsor.getSegments();
        final List<long[]> valid = new ArrayList<>();
        for (final long[] seg : segs) if (seg != null && seg.length >= 2) valid.add(seg);

        final SponsorOverlayView overlay = findViewById(R.id.sponsor_overlay);
        if (overlay != null) overlay.setData(valid.isEmpty() ? null : valid, duration, unit);

        final DefaultTimeBar bar = findViewById(R.id.exo_progress);
        if (bar != null) {
            if (valid.isEmpty()) bar.setAdGroupTimesMs(null, null, 0);
            else {
                final long[] times = new long[valid.size() * 2];
                for (int i = 0; i < valid.size(); i++) {
                    times[i*2] = valid.get(i)[0];
                    times[i*2+1] = valid.get(i)[1];
                }
                bar.setAdGroupTimesMs(times, new boolean[times.length], times.length);
            }
        }
    }

    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        if (isInPictureInPictureMode) {
            hideController();
            setUseController(false);
        } else {
            setUseController(true);
        }
    }

    public void enterFullscreen(boolean isPortraitVideo) {
        isFs = true;
        if (!activity.isInPictureInPictureMode()) normalHeight = playerHeight;
        ViewUtils.setFullscreen(activity.getWindow().getDecorView(), true);
        updatePlayerLayout(true);
        if (isPortraitVideo) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        } else {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            if (orientationListener != null) orientationListener.enable();
        }
    }

    public void exitFullscreen() {
        isFs = false;
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        ViewUtils.setFullscreen(activity.getWindow().getDecorView(), false);
        updatePlayerLayout(false);
        setResizeMode(prefs.getResizeMode());
        final ImageButton fsBtn = findViewById(R.id.btn_fullscreen);
        if (fsBtn != null) fsBtn.setImageResource(R.drawable.ic_fullscreen);
        if (orientationListener != null) orientationListener.disable();
    }

    public void updatePlayerLayout(boolean fullscreen) {
        final ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp instanceof ConstraintLayout.LayoutParams params) {
            if (fullscreen) {
                params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
                params.height = ConstraintLayout.LayoutParams.MATCH_PARENT;
                params.topMargin = 0;
            } else {
                params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
                params.height = normalHeight > 0 ? normalHeight : (int) (ViewUtils.getScreenWidth(activity) * 9 / 16.0);
                params.topMargin = ViewUtils.dpToPx(activity, Constant.TOP_MARGIN_DP);
            }
            setLayoutParams(params);
            requestLayout(); // CRITICAL: Force view redraw
        }
    }

    @Override
    public void setResizeMode(int resizeMode) {
        super.setResizeMode(resizeMode);
        final View surface = getVideoSurfaceView();
        if (surface instanceof AspectRatioFrameLayout frame) frame.setResizeMode(resizeMode);
    }

    public void setTitle(@Nullable String title) {
        final TextView titleView = findViewById(R.id.tv_title);
        if (titleView != null) {
            titleView.setText(title);
            titleView.setSelected(true);
        }
    }

    public void setHeight(int height) {
        if (activity.isInPictureInPictureMode() || isFs || height <= 0) return;
        int deviceHeight = ViewUtils.dpToPx(activity, height);
        if (getLayoutParams().height == deviceHeight) return;
        getLayoutParams().height = deviceHeight;
        if (!isFs) normalHeight = deviceHeight;
        requestLayout();
    }
}