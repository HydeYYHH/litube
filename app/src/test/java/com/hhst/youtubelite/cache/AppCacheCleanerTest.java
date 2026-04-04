package com.hhst.youtubelite.cache;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public class AppCacheCleanerTest {

	@Test
	public void deleteContents_removesChildrenButKeepsRootDirectory() throws Exception {
		final Path root = Files.createTempDirectory("litube-cache-cleaner");
		final Path childFile = Files.createFile(root.resolve("a.txt"));
		final Path childDir = Files.createDirectories(root.resolve("nested"));
		final Path grandChildFile = Files.createFile(childDir.resolve("b.txt"));

		AppCacheCleaner.deleteContents(root.toFile());

		assertTrue(Files.exists(root));
		assertFalse(Files.exists(childFile));
		assertFalse(Files.exists(childDir));
		assertFalse(Files.exists(grandChildFile));

		Files.deleteIfExists(root);
	}

	@Test
	public void deleteContents_ignoresNullMissingOrPlainFiles() throws Exception {
		AppCacheCleaner.deleteContents(null);

		final Path plainFile = Files.createTempFile("litube-cache-cleaner-file", ".txt");
		AppCacheCleaner.deleteContents(plainFile.toFile());

		assertTrue(Files.exists(plainFile));
		Files.deleteIfExists(plainFile);

		final File missing = new File(plainFile.getParent().toFile(), "missing-cache-dir");
		AppCacheCleaner.deleteContents(missing);
		assertFalse(missing.exists());
	}
}
