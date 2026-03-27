package com.hhst.youtubelite.extension;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExtensionTreeTest {
	@Test
	public void defaultExtensionTree_containsInAppMiniPlayerToggle() {
		assertTrue(Extension.defaultExtensionTree()
						.stream()
						.filter(group -> group.children() != null)
						.flatMap(group -> group.children().stream())
						.anyMatch(item -> com.hhst.youtubelite.Constant.ENABLE_IN_APP_MINI_PLAYER.equals(item.key())));
	}

	@Test
	public void defaultPreferences_enableInAppMiniPlayerByDefault() {
		assertTrue(com.hhst.youtubelite.extension.Constant.DEFAULT_PREFERENCES
						.get(com.hhst.youtubelite.Constant.ENABLE_IN_APP_MINI_PLAYER));
	}
}
