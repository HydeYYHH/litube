package com.hhst.youtubelite.player.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EngineQueueRoutingTest {
	@Test
	public void shouldUseLocalQueueNavigation_requiresEnabledQueueAndCurrentVideoMembership() {
		assertFalse(Engine.shouldUseLocalQueueNavigation(false, false));
		assertFalse(Engine.shouldUseLocalQueueNavigation(true, false));
		assertFalse(Engine.shouldUseLocalQueueNavigation(false, true));
		assertTrue(Engine.shouldUseLocalQueueNavigation(true, true));
	}
}
