package com.hhst.youtubelite.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;

import com.hhst.youtubelite.R;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.Locale;

public final class DownloadStorageUtils {
	private static final String WORK_DIR_NAME = "download_work";

	private DownloadStorageUtils() {
	}

	@NonNull
	public static File getWorkingDirectory(@NonNull final Context context) {
		final File externalDir = context.getExternalFilesDir(WORK_DIR_NAME);
		final File appDir = externalDir != null ? externalDir : new File(context.getFilesDir(), WORK_DIR_NAME);
		if ((!appDir.exists() && !appDir.mkdirs()) && !appDir.isDirectory()) {
			throw new IllegalStateException("Unable to create work directory: " + appDir.getAbsolutePath());
		}
		return appDir;
	}

	@NonNull
	public static String publishToDownloads(@NonNull final Context context, @NonNull final File sourceFile, @NonNull final String displayName) throws IOException {
		final String mimeType = guessMimeType(displayName);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			return publishToDownloadsMediaStore(context, sourceFile, displayName, mimeType);
		}

		final File targetDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), context.getString(R.string.app_name));
		if ((!targetDir.exists() && !targetDir.mkdirs()) && !targetDir.isDirectory()) {
			throw new IOException("Unable to create downloads directory: " + targetDir.getAbsolutePath());
		}

		final File destination = buildUniqueFile(targetDir, displayName);
		FileUtils.copyFile(sourceFile, destination);
		MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()}, mimeType != null ? new String[]{mimeType} : null, null);
		FileUtils.deleteQuietly(sourceFile);
		return destination.getAbsolutePath();
	}

	public static void saveUrlToDownloads(@NonNull final Context context, @NonNull final URL url, @NonNull final String displayName) throws IOException {
		final File tmpFile = File.createTempFile("download_", ".tmp", getWorkingDirectory(context));
		try {
			FileUtils.copyURLToFile(url, tmpFile);
			publishToDownloads(context, tmpFile, displayName);
		} finally {
			FileUtils.deleteQuietly(tmpFile);
		}
	}

	public static boolean exists(@NonNull final Context context, @Nullable final String outputReference) {
		if (outputReference == null || outputReference.isBlank()) return false;
		if (isContentUri(outputReference)) {
			try (final var cursor = context.getContentResolver().query(Uri.parse(outputReference), new String[]{MediaStore.MediaColumns._ID}, null, null, null)) {
				return cursor != null && cursor.moveToFirst();
			} catch (Exception ignored) {
				return false;
			}
		}
		return new File(outputReference).exists();
	}

	public static void delete(@NonNull final Context context, @Nullable final String outputReference) {
		if (outputReference == null || outputReference.isBlank()) return;
		if (isContentUri(outputReference)) {
			try {
				context.getContentResolver().delete(Uri.parse(outputReference), null, null);
				return;
			} catch (Exception ignored) {
				return;
			}
		}
		FileUtils.deleteQuietly(new File(outputReference));
	}

	@Nullable
	public static Uri getOpenUri(@NonNull final Context context, @Nullable final String outputReference) {
		if (outputReference == null || outputReference.isBlank()) return null;
		if (isContentUri(outputReference)) return Uri.parse(outputReference);
		final File file = new File(outputReference);
		if (!file.exists()) return null;
		return FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file);
	}

	@Nullable
	public static String getMimeType(@NonNull final Context context, @Nullable final String outputReference, @NonNull final String fileName) {
		if (outputReference != null && isContentUri(outputReference)) {
			final String contentType = context.getContentResolver().getType(Uri.parse(outputReference));
			if (contentType != null && !contentType.isBlank()) return contentType;
		}
		if (outputReference != null && !outputReference.isBlank() && !isContentUri(outputReference)) {
			final String outputMimeType = guessMimeType(new File(outputReference).getName());
			if (outputMimeType != null) return outputMimeType;
		}
		return guessMimeType(fileName);
	}

	@NonNull
	public static String getDownloadsLocationLabel(@NonNull final Context context) {
		return Environment.DIRECTORY_DOWNLOADS + "/" + context.getString(R.string.app_name);
	}

	private static boolean isContentUri(@NonNull final String outputReference) {
		return outputReference.startsWith("content://");
	}

	@Nullable
	private static String guessMimeType(@NonNull final String fileName) {
		final String extension = extractExtension(fileName);
		if (extension == null) return null;
		try {
			final String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
			if (mimeType != null && !mimeType.isBlank()) return mimeType;
		} catch (RuntimeException ignored) {
		}
		return switch (extension) {
			case "mp4" -> "video/mp4";
			case "m4a" -> "audio/mp4";
			case "jpg", "jpeg" -> "image/jpeg";
			case "png" -> "image/png";
			case "webp" -> "image/webp";
			case "srt" -> "application/x-subrip";
			case "vtt" -> "text/vtt";
			default -> null;
		};
	}

	@Nullable
	private static String extractExtension(@NonNull final String fileName) {
		final int queryIndex = fileName.indexOf('?');
		final String withoutQuery = queryIndex >= 0 ? fileName.substring(0, queryIndex) : fileName;
		final int fragmentIndex = withoutQuery.indexOf('#');
		final String normalized = fragmentIndex >= 0 ? withoutQuery.substring(0, fragmentIndex) : withoutQuery;
		final int slashIndex = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
		final String lastSegment = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
		final int dotIndex = lastSegment.lastIndexOf('.');
		if (dotIndex <= 0 || dotIndex == lastSegment.length() - 1) return null;
		return lastSegment.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
	}

	@NonNull
	private static File buildUniqueFile(@NonNull final File targetDir, @NonNull final String displayName) {
		File candidate = new File(targetDir, displayName);
		if (!candidate.exists()) return candidate;

		final int dot = displayName.lastIndexOf('.');
		final String baseName = dot >= 0 ? displayName.substring(0, dot) : displayName;
		final String extension = dot >= 0 ? displayName.substring(dot) : "";
		int suffix = 1;
		while (candidate.exists()) {
			candidate = new File(targetDir, baseName + " (" + suffix + ")" + extension);
			suffix++;
		}
		return candidate;
	}

	@NonNull
	@RequiresApi(Build.VERSION_CODES.Q)
	private static String publishToDownloadsMediaStore(@NonNull final Context context, @NonNull final File sourceFile, @NonNull final String displayName, @Nullable final String mimeType) throws IOException {
		final ContentResolver resolver = context.getContentResolver();
		final ContentValues values = new ContentValues();
		values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
		values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + context.getString(R.string.app_name));
		values.put(MediaStore.MediaColumns.IS_PENDING, 1);
		if (mimeType != null) values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);

		final Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
		if (uri == null) throw new IOException("Unable to create MediaStore entry for " + displayName);

		try (final FileInputStream inputStream = new FileInputStream(sourceFile);
		     final OutputStream outputStream = resolver.openOutputStream(uri, "w")) {
			if (outputStream == null)
				throw new IOException("Unable to open MediaStore output stream for " + displayName);
			inputStream.transferTo(outputStream);
		} catch (Exception e) {
			resolver.delete(uri, null, null);
			throw e instanceof IOException ? (IOException) e : new IOException(e);
		} finally {
			FileUtils.deleteQuietly(sourceFile);
		}

		final ContentValues completedValues = new ContentValues();
		completedValues.put(MediaStore.MediaColumns.IS_PENDING, 0);
		resolver.update(uri, completedValues, null, null);
		return uri.toString();
	}
}
