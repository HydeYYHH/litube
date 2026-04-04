package com.hhst.youtubelite.player;

/**
 * Computes mini-player dimensions and spacing.
 */
public final class MiniPlayerLayout {

	static final int NO_WIDTH_OVERRIDE_DP = -1;
	private static final int COMPACT_BREAKPOINT_DP = 600;
	private static final float COMPACT_WIDTH_RATIO = 0.62f;
	private static final float LARGE_WIDTH_RATIO = 0.46f;
	private static final int COMPACT_MIN_WIDTH_DP = 190;
	private static final int COMPACT_MAX_WIDTH_DP = 320;
	private static final int LARGE_MIN_WIDTH_DP = 240;
	private static final int LARGE_MAX_WIDTH_DP = 420;
	private static final int OUTER_MARGIN_DP = 12;
	private static final int MIN_BOTTOM_DOCK_DP = 56;

	private MiniPlayerLayout() {
	}

	static int computeWidthDp(int screenWidthDp) {
		if (isCompactScreen(screenWidthDp)) {
			return clamp(Math.round(screenWidthDp * COMPACT_WIDTH_RATIO), COMPACT_MIN_WIDTH_DP, COMPACT_MAX_WIDTH_DP);
		}
		return clamp(Math.round(screenWidthDp * LARGE_WIDTH_RATIO), LARGE_MIN_WIDTH_DP, LARGE_MAX_WIDTH_DP);
	}

	static int minWidthDpForScreen(int screenWidthDp) {
		return isCompactScreen(screenWidthDp)
						? COMPACT_MIN_WIDTH_DP
						: LARGE_MIN_WIDTH_DP;
	}

	static int clampWidthDp(int screenWidthDp, int widthDp) {
		if (isCompactScreen(screenWidthDp)) {
			return clamp(widthDp, COMPACT_MIN_WIDTH_DP, COMPACT_MAX_WIDTH_DP);
		}
		return clamp(widthDp, LARGE_MIN_WIDTH_DP, LARGE_MAX_WIDTH_DP);
	}

	static int computeHeightDp(int widthDp) {
		return widthDp * 9 / 16;
	}

	static int computeGapByRatio(int widthDp,
	                             final int referenceWidthDp,
	                             final int referenceGapDp,
	                             final int leftControlWidthDp,
	                             final int rightControlWidthDp) {
		if (referenceWidthDp <= 0) return Math.max(referenceGapDp, 0);
		float referenceCenterDistanceDp =
						leftControlWidthDp / 2.0f + referenceGapDp + rightControlWidthDp / 2.0f;
		float targetCenterDistanceDp = referenceCenterDistanceDp * widthDp / (float) referenceWidthDp;
		float computedGapDp = targetCenterDistanceDp
						- leftControlWidthDp / 2.0f
						- rightControlWidthDp / 2.0f;
		return Math.max(Math.round(computedGapDp), 0);
	}

	static int computeBottomMarginDp(int outerMarginDp, int bottomInsetDp) {
		return outerMarginDp + Math.max(bottomInsetDp, MIN_BOTTOM_DOCK_DP);
	}

	static Spec computeSpec(int screenWidthDp,
	                        final int bottomInsetDp,
	                        final int widthOverrideDp) {
		int widthDp = widthOverrideDp == NO_WIDTH_OVERRIDE_DP
						? computeWidthDp(screenWidthDp)
						: clampWidthDp(screenWidthDp, widthOverrideDp);
		int heightDp = computeHeightDp(widthDp);
		return new Spec(widthDp, heightDp, OUTER_MARGIN_DP, computeBottomMarginDp(OUTER_MARGIN_DP, bottomInsetDp));
	}

	static float clampTranslation(float translationPx,
	                              final int layoutStartPx,
	                              final int viewSizePx,
	                              final int parentSizePx) {
		float minTranslation = -layoutStartPx;
		float maxTranslation = Math.max(minTranslation, parentSizePx - viewSizePx - layoutStartPx);
		return Math.min(Math.max(translationPx, minTranslation), maxTranslation);
	}

	static float snapX(float translationPx,
	                   final int layoutStartPx,
	                   final int viewSizePx,
	                   final int parentSizePx) {
		float minTranslation = -layoutStartPx;
		float maxTranslation = Math.max(minTranslation, parentSizePx - viewSizePx - layoutStartPx);
		float clampedTranslation = clampTranslation(translationPx, layoutStartPx, viewSizePx, parentSizePx);
		return Math.abs(clampedTranslation - minTranslation) <= Math.abs(maxTranslation - clampedTranslation)
						? minTranslation
						: maxTranslation;
	}

	private static int clamp(int value, int min, int max) {
		return Math.min(Math.max(value, min), max);
	}

	private static boolean isCompactScreen(int screenWidthDp) {
		return screenWidthDp < COMPACT_BREAKPOINT_DP;
	}

/**
 * Value object for app logic.
 */
	public record Spec(int widthDp, int heightDp, int rightMarginDp, int bottomMarginDp) {
	}
}
