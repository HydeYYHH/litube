package com.hhst.youtubelite.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;

import androidx.core.content.FileProvider;

import org.apache.commons.io.FileUtils;

import org.junit.Test;
import org.mockito.MockedStatic;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class DownloadStorageUtilsTest {

	@Test
	public void getMimeType_prefersOutputReferenceExtensionWhenHistoryNameHasNoExtension() {
		final Context context = mock(Context.class);

		final String mimeType = DownloadStorageUtils.getMimeType(
						context,
						"/storage/emulated/0/Download/LiteTube/Sample Video.mp4",
						"Sample Video");

		assertEquals("video/mp4", mimeType);
	}

	@Test
	public void getMimeType_prefersContentResolverTypeForContentUri() {
		final Context context = mock(Context.class);
		final ContentResolver contentResolver = mock(ContentResolver.class);
		final String outputReference = "content://downloads/public_downloads/1";
		final Uri uri = mock(Uri.class);
		when(context.getContentResolver()).thenReturn(contentResolver);
		when(contentResolver.getType(uri)).thenReturn("video/webm");

		try (MockedStatic<Uri> uriStatic = mockParsedUri(outputReference, uri)) {
			final String mimeType = DownloadStorageUtils.getMimeType(context, outputReference, "fallback.mp4");

			assertEquals("video/webm", mimeType);
		}
	}

	@Test
	public void getMimeType_fallsBackToFileNameWhenContentUriTypeIsBlank() {
		final Context context = mock(Context.class);
		final ContentResolver contentResolver = mock(ContentResolver.class);
		final String outputReference = "content://downloads/public_downloads/1";
		final Uri uri = mock(Uri.class);
		when(context.getContentResolver()).thenReturn(contentResolver);
		when(contentResolver.getType(uri)).thenReturn(" ");

		try (MockedStatic<Uri> uriStatic = mockParsedUri(outputReference, uri)) {
			final String mimeType = DownloadStorageUtils.getMimeType(context, outputReference, "captions.vtt");

			assertEquals("text/vtt", mimeType);
		}
	}

	@Test
	public void getMimeType_fallsBackToFileNameWhenContentResolverThrows() {
		final Context context = mock(Context.class);
		final ContentResolver contentResolver = mock(ContentResolver.class);
		final String outputReference = "content://downloads/public_downloads/1";
		final Uri uri = mock(Uri.class);
		when(context.getContentResolver()).thenReturn(contentResolver);
		when(contentResolver.getType(uri)).thenThrow(new SecurityException("denied"));

		try (MockedStatic<Uri> uriStatic = mockParsedUri(outputReference, uri)) {
			final String mimeType = DownloadStorageUtils.getMimeType(context, outputReference, "fallback.mp4");

			assertEquals("video/mp4", mimeType);
		}
	}

	@Test
	public void getMimeType_ignoresQueryAndFragmentInLocalOutputReference() {
		final Context context = mock(Context.class);

		final String mimeType = DownloadStorageUtils.getMimeType(
						context,
						"/storage/emulated/0/Download/LiteTube/cover.webp?cache=1#preview",
						"history-name");

		assertEquals("image/webp", mimeType);
	}

	@Test
	public void exists_returnsTrueForExistingLocalFile() throws Exception {
		final Context context = mock(Context.class);
		final File file = Files.createTempFile("download-storage-exists", ".mp4").toFile();

		try {
			assertTrue(DownloadStorageUtils.exists(context, file.getAbsolutePath()));
		} finally {
			Files.deleteIfExists(file.toPath());
		}
	}

	@Test
	public void exists_queriesContentResolverForContentUri() {
		final Context context = mock(Context.class);
		final ContentResolver contentResolver = mock(ContentResolver.class);
		final Cursor cursor = mock(Cursor.class);
		final String outputReference = "content://downloads/public_downloads/5";
		final Uri uri = mock(Uri.class);
		when(context.getContentResolver()).thenReturn(contentResolver);
		when(contentResolver.query(eq(uri), any(), any(), any(), any())).thenReturn(cursor);
		when(cursor.moveToFirst()).thenReturn(true);

		try (MockedStatic<Uri> uriStatic = mockParsedUri(outputReference, uri)) {
			assertTrue(DownloadStorageUtils.exists(context, outputReference));
		}
	}

	@Test
	public void exists_returnsFalseWhenContentResolverQueryFails() {
		final Context context = mock(Context.class);
		final ContentResolver contentResolver = mock(ContentResolver.class);
		final String outputReference = "content://downloads/public_downloads/5";
		final Uri uri = mock(Uri.class);
		when(context.getContentResolver()).thenReturn(contentResolver);
		when(contentResolver.query(eq(uri), any(), any(), any(), any()))
						.thenThrow(new IllegalStateException("resolver offline"));

		try (MockedStatic<Uri> uriStatic = mockParsedUri(outputReference, uri)) {
			assertFalse(DownloadStorageUtils.exists(context, outputReference));
		}
	}

	@Test
	public void delete_removesExistingLocalFile() throws Exception {
		final Context context = mock(Context.class);
		final File file = Files.createTempFile("download-storage-delete", ".tmp").toFile();

		DownloadStorageUtils.delete(context, file.getAbsolutePath());

		assertFalse(file.exists());
	}

	@Test
	public void delete_delegatesContentUriDeletionToResolver() {
		final Context context = mock(Context.class);
		final ContentResolver contentResolver = mock(ContentResolver.class);
		final String outputReference = "content://downloads/public_downloads/8";
		final Uri uri = mock(Uri.class);
		when(context.getContentResolver()).thenReturn(contentResolver);

		try (MockedStatic<Uri> uriStatic = mockParsedUri(outputReference, uri)) {
			DownloadStorageUtils.delete(context, outputReference);
		}

		verify(contentResolver).delete(uri, null, null);
	}

	@Test
	public void getOpenUri_returnsContentUriAsIs() {
		final Context context = mock(Context.class);
		final String outputReference = "content://downloads/public_downloads/13";
		final Uri uri = mock(Uri.class);

		try (MockedStatic<Uri> uriStatic = mockParsedUri(outputReference, uri)) {
			assertSame(uri, DownloadStorageUtils.getOpenUri(context, outputReference));
		}
	}

	@Test
	public void getOpenUri_returnsNullWhenLocalFileDoesNotExist() {
		final Context context = mock(Context.class);

		assertNull(DownloadStorageUtils.getOpenUri(context, "C:\\missing\\video.mp4"));
	}

	@Test
	public void getOpenUri_usesFileProviderForExistingLocalFile() throws Exception {
		final Context context = mock(Context.class);
		final File file = Files.createTempFile("download-storage-open", ".mp4").toFile();
		final Uri expected = mock(Uri.class);
		when(context.getPackageName()).thenReturn("com.hhst.litube");

		try (MockedStatic<FileProvider> fileProvider = mockStatic(FileProvider.class)) {
			fileProvider.when(() -> FileProvider.getUriForFile(context, "com.hhst.litube.provider", file))
							.thenReturn(expected);

			assertSame(expected, DownloadStorageUtils.getOpenUri(context, file.getAbsolutePath()));
		} finally {
			Files.deleteIfExists(file.toPath());
		}
	}

	@Test
	public void getWorkingDirectory_prefersExternalFilesDirAndCreatesDirectory() throws Exception {
		final Context context = mock(Context.class);
		final File externalRoot = Files.createTempDirectory("download-storage-external").toFile();
		final File expected = new File(externalRoot, "download_work");
		when(context.getExternalFilesDir("download_work")).thenReturn(expected);
		when(context.getFilesDir()).thenReturn(Files.createTempDirectory("download-storage-files").toFile());

		try {
			final File workingDirectory = DownloadStorageUtils.getWorkingDirectory(context);

			assertEquals(expected.getAbsolutePath(), workingDirectory.getAbsolutePath());
			assertTrue(workingDirectory.isDirectory());
		} finally {
			FileUtils.deleteQuietly(externalRoot);
			FileUtils.deleteQuietly(expected);
		}
	}

	@Test
	public void getWorkingDirectory_fallsBackToInternalFilesDirWhenExternalMissing() throws Exception {
		final Context context = mock(Context.class);
		final File filesDir = Files.createTempDirectory("download-storage-files").toFile();
		when(context.getExternalFilesDir("download_work")).thenReturn(null);
		when(context.getFilesDir()).thenReturn(filesDir);

		try {
			final File workingDirectory = DownloadStorageUtils.getWorkingDirectory(context);

			assertEquals(new File(filesDir, "download_work").getAbsolutePath(), workingDirectory.getAbsolutePath());
			assertTrue(workingDirectory.isDirectory());
		} finally {
			FileUtils.deleteQuietly(filesDir);
		}
	}

	@Test
	public void getWorkingDirectory_throwsWhenDirectoryCannotBeCreated() throws Exception {
		final Context context = mock(Context.class);
		final File filesDir = File.createTempFile("download-storage-blocked", ".tmp");
		when(context.getExternalFilesDir("download_work")).thenReturn(null);
		when(context.getFilesDir()).thenReturn(filesDir);

		try {
			final IllegalStateException failure = assertThrows(
							IllegalStateException.class,
							() -> DownloadStorageUtils.getWorkingDirectory(context));

			assertTrue(failure.getMessage().contains("Unable to create work directory"));
		} finally {
			Files.deleteIfExists(filesDir.toPath());
		}
	}

	@Test
	public void getDownloadsLocationLabel_includesApplicationName() {
		final Context context = mock(Context.class);
		when(context.getString(com.hhst.youtubelite.R.string.app_name)).thenReturn("LiteTube");

		assertEquals(Environment.DIRECTORY_DOWNLOADS + "/LiteTube", DownloadStorageUtils.getDownloadsLocationLabel(context));
	}

	private static MockedStatic<Uri> mockParsedUri(final String outputReference, final Uri uri) {
		final MockedStatic<Uri> uriStatic = mockStatic(Uri.class);
		uriStatic.when(() -> Uri.parse(outputReference)).thenReturn(uri);
		return uriStatic;
	}

	@Test
	public void publishToDownloads_preQ_createsUniqueNameAndScans() throws Exception {
		final Context context = mock(Context.class);
		when(context.getString(com.hhst.youtubelite.R.string.app_name)).thenReturn("LiteTube");
		final File downloadsRoot = Files.createTempDirectory("lite-public-downloads").toFile();
		final File parentDir = new File(downloadsRoot, "LiteTube");
		parentDir.mkdirs();
		final File existing = new File(parentDir, "song.mp4");
		Files.write(existing.toPath(), "existing".getBytes(StandardCharsets.UTF_8));
		final File source = Files.createTempFile("publish-source", ".mp4").toFile();
		Files.write(source.toPath(), "payload".getBytes(StandardCharsets.UTF_8));

		try (MockedStatic<Environment> env = mockStatic(Environment.class);
		     MockedStatic<MediaScannerConnection> scanner = mockStatic(MediaScannerConnection.class)) {
			env.when(() -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
							.thenReturn(downloadsRoot);
			final String destPath = DownloadStorageUtils.publishToDownloads(context, source, "song.mp4");
			final File dest = new File(destPath);

			assertTrue(dest.exists());
			assertFalse(source.exists());
			assertTrue(dest.getName().startsWith("song"));
			assertTrue(dest.getName().endsWith(".mp4"));
			assertTrue(destPath.contains("song ("));

			scanner.verify(
							() -> MediaScannerConnection.scanFile(eq(context), any(String[].class), any(), isNull()),
							times(1));
		}
	}

	@Test
	public void saveUrlToDownloads_cleansTemporaryFileAfterPublish() throws Exception {
		final Context context = mock(Context.class);
		when(context.getString(com.hhst.youtubelite.R.string.app_name)).thenReturn("LiteTube");
		final File workingRoot = Files.createTempDirectory("download-work-root").toFile();
		final File filesDir = Files.createTempDirectory("download-files-dir").toFile();
		when(context.getExternalFilesDir("download_work")).thenReturn(workingRoot);
		when(context.getFilesDir()).thenReturn(filesDir);
		final File sourcePayload = Files.createTempFile("download-source", ".txt").toFile();
		Files.write(sourcePayload.toPath(), "payload".getBytes(StandardCharsets.UTF_8));
		final URL fileUrl = sourcePayload.toURI().toURL();
		final File downloadsRoot = Files.createTempDirectory("lite-public-downloads").toFile();

		try (MockedStatic<Environment> env = mockStatic(Environment.class);
		     MockedStatic<MediaScannerConnection> scanner = mockStatic(MediaScannerConnection.class)) {
			env.when(() -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
							.thenReturn(downloadsRoot);
			DownloadStorageUtils.saveUrlToDownloads(context, fileUrl, "movie.mp4");
			final File dest = new File(new File(downloadsRoot, "LiteTube"), "movie.mp4");

			assertTrue(dest.exists());
			assertTrue(sourcePayload.exists());
			assertEquals(0, workingRoot.listFiles().length);
			scanner.verify(
							() -> MediaScannerConnection.scanFile(eq(context), any(String[].class), any(), isNull()),
							atLeastOnce());
		} finally {
			Files.deleteIfExists(sourcePayload.toPath());
		}
	}

}
