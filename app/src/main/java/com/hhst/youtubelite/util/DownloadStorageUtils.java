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

/**
 * Utility that publishes completed downloads into shared storage.
 */
public final class DownloadStorageUtils {
	private static final String WORK_DIR_NAME = "download_work";

	private DownloadStorageUtils() {
	}

	@NonNull
	public static File getWorkingDirectory(@NonNull Context context) {
		File externalDir = context.getExternalFilesDir(WORK_DIR_NAME);
		File appDir = externalDir != null ? externalDir : new File(context.getFilesDir(), WORK_DIR_NAME);
		if ((!appDir.exists() && !appDir.mkdirs()) && !appDir.isDirectory()) {
			throw new IllegalStateException("Unable to create work directory: " + appDir.getAbsolutePath());
		}
		return appDir;
	}

	@NonNull
	public static String publishToDownloads(@NonNull Context context, @NonNull File sourceFile, @NonNull String displayName) throws IOException {
		String mimeType = guessMimeType(displayName);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			return publishToDownloadsMediaStore(context, sourceFile, displayName, mimeType);
		}

		File targetDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), context.getString(R.string.app_name));
		if ((!targetDir.exists() && !targetDir.mkdirs()) && !targetDir.isDirectory()) {
			throw new IOException("Unable to create downloads directory: " + targetDir.getAbsolutePath());
		}

		// Keep the filename unique when the target already exists.
		File destination = new File(targetDir, displayName);
		if (destination.exists()) {
			int dot = displayName.lastIndexOf('.');
			String baseName = dot >= 0 ? displayName.substring(0, dot) : displayName;
			String extension = dot >= 0 ? displayName.substring(dot) : "";
			int suffix = 1;
			while (destination.exists()) {
				destination = new File(targetDir, baseName + " (" + suffix + ")" + extension);
				suffix++;
			}
		}
		FileUtils.copyFile(sourceFile, destination);
		MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()}, mimeType != null ? new String[]{mimeType} : null, null);
		FileUtils.deleteQuietly(sourceFile);
		return destination.getAbsolutePath();
	}

	public static void saveUrlToDownloads(@NonNull Context context, @NonNull URL url, @NonNull String displayName) throws IOException {
		File tmpFile = File.createTempFile("download_", ".tmp", getWorkingDirectory(context));
		try {
			FileUtils.copyURLToFile(url, tmpFile);
			publishToDownloads(context, tmpFile, displayName);
		} finally {
			FileUtils.deleteQuietly(tmpFile);
		}
	}

	public static boolean doesNotExist(@NonNull Context context, @Nullable String outputReference) {
		if (outputReference == null || outputReference.isBlank()) return true;
		if (isContentUri(outputReference)) {
			try (var cursor = context.getContentResolver().query(Uri.parse(outputReference), new String[]{MediaStore.MediaColumns._ID}, null, null, null)) {
				return cursor == null || !cursor.moveToFirst();
			} catch (Exception ignored) {
				return true;
			}
		}
		return !new File(outputReference).exists();
	}

	public static void delete(@NonNull Context context, @Nullable String outputReference) {
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
	public static Uri getOpenUri(@NonNull Context context, @Nullable String outputReference) {
		if (outputReference == null || outputReference.isBlank()) return null;
		if (isContentUri(outputReference)) return Uri.parse(outputReference);
		File file = new File(outputReference);
		if (!file.exists()) return null;
		return FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file);
	}

	@Nullable
	public static String getMimeType(@NonNull Context context, @Nullable String outputReference, @NonNull String fileName) {
		if (outputReference != null && isContentUri(outputReference)) {
			try {
				String contentType = context.getContentResolver().getType(Uri.parse(outputReference));
				if (contentType != null && !contentType.isBlank()) return contentType;
			} catch (RuntimeException ignored) {
			}
		}
		if (outputReference != null && !outputReference.isBlank() && !isContentUri(outputReference)) {
			String outputMimeType = guessMimeType(new File(outputReference).getName());
			if (outputMimeType != null) return outputMimeType;
		}
		return guessMimeType(fileName);
	}

	@NonNull
	public static String getDownloadsLocationLabel(@NonNull Context context) {
		return Environment.DIRECTORY_DOWNLOADS + "/" + context.getString(R.string.app_name);
	}

	private static boolean isContentUri(@NonNull String outputReference) {
		return outputReference.startsWith("content://");
	}

	@Nullable
	private static String guessMimeType(@NonNull String fileName) {
		String extension = extractExtension(fileName);
		if (extension == null) return null;
		try {
			String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
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
	private static String extractExtension(@NonNull String fileName) {
		int queryIndex = fileName.indexOf('?');
		String withoutQuery = queryIndex >= 0 ? fileName.substring(0, queryIndex) : fileName;
		int fragmentIndex = withoutQuery.indexOf('#');
		String normalized = fragmentIndex >= 0 ? withoutQuery.substring(0, fragmentIndex) : withoutQuery;
		int slashIndex = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
		String lastSegment = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
		int dotIndex = lastSegment.lastIndexOf('.');
		if (dotIndex <= 0 || dotIndex == lastSegment.length() - 1) return null;
		return lastSegment.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
	}

	@NonNull
	@RequiresApi(Build.VERSION_CODES.Q)
	private static String publishToDownloadsMediaStore(@NonNull Context context, @NonNull File sourceFile, @NonNull String displayName, @Nullable String mimeType) throws IOException {
		ContentResolver resolver = context.getContentResolver();
		ContentValues values = new ContentValues();
		values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
		values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + context.getString(R.string.app_name));
		values.put(MediaStore.MediaColumns.IS_PENDING, 1);
		if (mimeType != null) values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);

		Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
		if (uri == null) throw new IOException("Unable to create MediaStore entry for " + displayName);

		try (FileInputStream inputStream = new FileInputStream(sourceFile);
		     OutputStream outputStream = resolver.openOutputStream(uri, "w")) {
			if (outputStream == null)
				throw new IOException("Unable to open MediaStore output stream for " + displayName);
			byte[] buffer = new byte[8_192];
			int read;
			while ((read = inputStream.read(buffer)) != -1) {
				outputStream.write(buffer, 0, read);
			}
		} catch (Exception e) {
			resolver.delete(uri, null, null);
			throw e instanceof IOException ? (IOException) e : new IOException(e);
		} finally {
			FileUtils.deleteQuietly(sourceFile);
		}

		ContentValues completedValues = new ContentValues();
		completedValues.put(MediaStore.MediaColumns.IS_PENDING, 0);
		resolver.update(uri, completedValues, null, null);
		return uri.toString();
	}
}
