package com.hhst.youtubelite.player;

public final class MiniPlayerLayout {

	private static final int COMPACT_BREAKPOINT_DP = 600;
	private static final float COMPACT_WIDTH_RATIO = 0.62f;
	private static final float LARGE_WIDTH_RATIO = 0.46f;
	private static final int COMPACT_MIN_WIDTH_DP = 190;
	private static final int COMPACT_MAX_WIDTH_DP = 320;
	private static final int LARGE_MIN_WIDTH_DP = 240;
	private static final int LARGE_MAX_WIDTH_DP = 420;
	private static final int OUTER_MARGIN_DP = 12;
	private static final int MIN_BOTTOM_DOCK_DP = 56;
	static final int NO_WIDTH_OVERRIDE_DP = -1;

	private MiniPlayerLayout() {
	}

	public record Spec(int widthDp, int heightDp, int rightMarginDp, int bottomMarginDp) {
	}

	static int computeWidthDp(int screenWidthDp) {
		if (isCompactScreen(screenWidthDp)) {
			return clamp(Math.round(screenWidthDp * COMPACT_WIDTH_RATIO), COMPACT_MIN_WIDTH_DP, COMPACT_MAX_WIDTH_DP);
		}
		return clamp(Math.round(screenWidthDp * LARGE_WIDTH_RATIO), LARGE_MIN_WIDTH_DP, LARGE_MAX_WIDTH_DP);
	}

	static int minWidthDpForScreen(final int screenWidthDp) {
		return isCompactScreen(screenWidthDp)
						? COMPACT_MIN_WIDTH_DP
						: LARGE_MIN_WIDTH_DP;
	}

	static int clampWidthDp(final int screenWidthDp, final int widthDp) {
		if (isCompactScreen(screenWidthDp)) {
			return clamp(widthDp, COMPACT_MIN_WIDTH_DP, COMPACT_MAX_WIDTH_DP);
		}
		return clamp(widthDp, LARGE_MIN_WIDTH_DP, LARGE_MAX_WIDTH_DP);
	}

	static int computeHeightDp(final int widthDp) {
		return widthDp * 9 / 16;
	}

	static int computeGapByCenterDistanceRatio(final int currentWidthDp,
	                                           final int referenceWidthDp,
	                                           final int referenceGapDp,
	                                           final int leftControlWidthDp,
	                                           final int rightControlWidthDp) {
		if (referenceWidthDp <= 0) return Math.max(referenceGapDp, 0);
		final float referenceCenterDistanceDp =
						leftControlWidthDp / 2.0f + referenceGapDp + rightControlWidthDp / 2.0f;
		final float targetCenterDistanceDp = referenceCenterDistanceDp * currentWidthDp / (float) referenceWidthDp;
		final float computedGapDp = targetCenterDistanceDp
						- leftControlWidthDp / 2.0f
						- rightControlWidthDp / 2.0f;
		return Math.max(Math.round(computedGapDp), 0);
	}

	static int computeBottomMarginDp(int outerMarginDp, int bottomInsetDp) {
		return outerMarginDp + Math.max(bottomInsetDp, MIN_BOTTOM_DOCK_DP);
	}

	static Spec computeSpec(int screenWidthDp, int bottomInsetDp) {
		return computeSpec(screenWidthDp, bottomInsetDp, NO_WIDTH_OVERRIDE_DP);
	}

	static Spec computeSpec(final int screenWidthDp,
	                        final int bottomInsetDp,
	                        final int widthOverrideDp) {
		final int widthDp = widthOverrideDp == NO_WIDTH_OVERRIDE_DP
						? computeWidthDp(screenWidthDp)
						: clampWidthDp(screenWidthDp, widthOverrideDp);
		final int heightDp = computeHeightDp(widthDp);
		return new Spec(widthDp, heightDp, OUTER_MARGIN_DP, computeBottomMarginDp(OUTER_MARGIN_DP, bottomInsetDp));
	}

	static float clampTranslation(final float translationPx,
	                              final int layoutStartPx,
	                              final int viewSizePx,
	                              final int parentSizePx) {
		final float minTranslation = -layoutStartPx;
		final float maxTranslation = Math.max(minTranslation, parentSizePx - viewSizePx - layoutStartPx);
		return Math.min(Math.max(translationPx, minTranslation), maxTranslation);
	}

	private static int clamp(int value, int min, int max) {
		return Math.min(Math.max(value, min), max);
	}

	private static boolean isCompactScreen(final int screenWidthDp) {
		return screenWidthDp < COMPACT_BREAKPOINT_DP;
	}
}
