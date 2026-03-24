package com.hhst.youtubelite.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MiniPlayerLayoutTest {

	@Test
	public void computeWidthDp_usesExpectedBreakpointsAndClamps() {
		assertEquals(198, MiniPlayerLayout.computeWidthDp(320));
		assertEquals(276, MiniPlayerLayout.computeWidthDp(600));
		assertEquals(322, MiniPlayerLayout.computeWidthDp(700));
	}

	@Test
	public void minWidthDpForScreen_returnsMinimumResizableWidth() {
		assertEquals(190, MiniPlayerLayout.minWidthDpForScreen(360));
		assertEquals(240, MiniPlayerLayout.minWidthDpForScreen(700));
	}

	@Test
	public void computeBottomMarginDp_accountsForBottomInsetAndMinimumDockSpacing() {
		assertEquals(68, MiniPlayerLayout.computeBottomMarginDp(12, 56));
		assertEquals(92, MiniPlayerLayout.computeBottomMarginDp(12, 80));
	}

	@Test
	public void computeSpec_returnsCompactAndInsetHeavyLayout() {
		final MiniPlayerLayout.Spec compact = MiniPlayerLayout.computeSpec(360, 0);
		assertEquals(223, compact.widthDp);
		assertEquals(12, compact.rightMarginDp);
		assertEquals(68, compact.bottomMarginDp);
		assertEquals(compact.widthDp * 9 / 16, compact.heightDp);

		final MiniPlayerLayout.Spec insetHeavy = MiniPlayerLayout.computeSpec(360, 80);
		assertEquals(92, insetHeavy.bottomMarginDp);
	}

	@Test
	public void computeSpec_widthOverrideClampsAndKeepsAspectRatio() {
		final MiniPlayerLayout.Spec minClamped = MiniPlayerLayout.computeSpec(360, 0, 120);
		assertEquals(190, minClamped.widthDp);
		assertEquals(190 * 9 / 16, minClamped.heightDp);

		final MiniPlayerLayout.Spec maxClamped = MiniPlayerLayout.computeSpec(360, 0, 420);
		assertEquals(320, maxClamped.widthDp);
		assertEquals(320 * 9 / 16, maxClamped.heightDp);
	}

	@Test
	public void clampTranslation_keepsMiniPlayerInsideParentBounds() {
		assertEquals(-168.0f, MiniPlayerLayout.clampTranslation(-500.0f, 168, 180, 360), 0.0f);
		assertEquals(-24.0f, MiniPlayerLayout.clampTranslation(-24.0f, 168, 180, 360), 0.0f);
		assertEquals(12.0f, MiniPlayerLayout.clampTranslation(500.0f, 168, 180, 360), 0.0f);
	}

	@Test
	public void clampTranslation_handlesSmallParentsWithoutInvertingBounds() {
		assertEquals(-40.0f, MiniPlayerLayout.clampTranslation(-100.0f, 40, 200, 160), 0.0f);
		assertEquals(-40.0f, MiniPlayerLayout.clampTranslation(20.0f, 40, 200, 160), 0.0f);
	}

	@Test
	public void computeGapByCenterDistanceRatio_preservesDefaultDistanceRatio() {
		assertEquals(18, MiniPlayerLayout.computeGapByCenterDistanceRatio(190, 190, 18, 30, 34));
		assertEquals(35, MiniPlayerLayout.computeGapByCenterDistanceRatio(255, 190, 18, 30, 34));
		assertEquals(52, MiniPlayerLayout.computeGapByCenterDistanceRatio(320, 190, 18, 30, 34));
	}
}
