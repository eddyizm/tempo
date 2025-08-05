package com.cappielloantonio.tempo.util;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.media3.common.MediaItem;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.Locale;

public class ExternalAudioWriter {

    private static String sanitizeFileName(String name) {
        String sanitized = name.replaceAll("[\\/:*?\\\"<>|]", "_");
        sanitized = sanitized.replaceAll("\\s+", " ").trim();
        return sanitized;
    }

    private static String normalizeForComparison(String name) {
        String s = sanitizeFileName(name);
        s = Normalizer.normalize(s, Normalizer.Form.NFKD);
        s = s.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return s.toLowerCase(Locale.ROOT);
    }

    private static DocumentFile findFile(DocumentFile dir, String fileName) {
        String normalized = normalizeForComparison(fileName);
        for (DocumentFile file : dir.listFiles()) {
            String existing = file.getName();
            if (existing != null && normalizeForComparison(existing).equals(normalized)) {
                return file;
            }
        }
        return null;
    }

    public static void downloadToUserDirectory(Context context, MediaItem mediaItem, String fallbackName) {
        new Thread(() -> {
            SharedPreferences prefs = context.getSharedPreferences("tempo_prefs", Context.MODE_PRIVATE);
            String uriString = prefs.getString("download_directory_uri", null);

            if (uriString == null) {
                notifyUnavailable(context);
                return;
            }

            Uri treeUri = Uri.parse(uriString);
            DocumentFile directory = DocumentFile.fromTreeUri(context, treeUri);
            if (directory == null || !directory.canWrite()) {
                notifyFailure(context, "Cannot write to folder.");
                return;
            }

            try {
                Uri mediaUri = mediaItem.requestMetadata.mediaUri;
                if (mediaUri == null) {
                    notifyFailure(context, "Invalid media URI.");
                    return;
                }

                HttpURLConnection connection = (HttpURLConnection) new URL(mediaUri.toString()).openConnection();
                connection.connect();

                String mimeType = connection.getContentType();
                if (mimeType == null) mimeType = "application/octet-stream";

                String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                if (extension == null) extension = "bin";

                String artist = mediaItem.mediaMetadata.artist != null ? mediaItem.mediaMetadata.artist.toString() : "";
                String title = mediaItem.mediaMetadata.title != null ? mediaItem.mediaMetadata.title.toString() : fallbackName;
                String album = mediaItem.mediaMetadata.albumTitle != null ? mediaItem.mediaMetadata.albumTitle.toString() : "";
                String name = artist.isEmpty() ? title : artist + " - " + title;
                if (!album.isEmpty()) name += " (" + album + ")";

                String sanitized = sanitizeFileName(name);
                String fullName = sanitized + "." + extension;

                DocumentFile existingFile = findFile(directory, fullName);
                long remoteLength = connection.getContentLengthLong();
                if (existingFile != null && existingFile.exists()) {
                    long localLength = existingFile.length();
                    if (remoteLength > 0 && localLength == remoteLength) {
                        notifyExists(context, fullName);
                        return;
                    } else {
                        existingFile.delete();
                    }
                }

                DocumentFile targetFile = directory.createFile(mimeType, fullName);
                if (targetFile == null) {
                    notifyFailure(context, "Failed to create file.");
                    return;
                }

                try (InputStream in = connection.getInputStream();
                OutputStream out = context.getContentResolver().openOutputStream(targetFile.getUri())) {
                    if (out == null) {
                        notifyFailure(context, "Cannot open output stream.");
                        return;
                    }

                    byte[] buffer = new byte[8192];
                    int len;
                    long total = 0;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                        total += len;
                    }

                    if (remoteLength > 0 && total != remoteLength) {
                        targetFile.delete();
                        notifyFailure(context, "Incomplete download.");
                    } else {
                        notifySuccess(context, fullName);
                    }
                }
            } catch (Exception e) {
                notifyFailure(context, e.getMessage());
            }
        }).start();
    }

    private static void notifyUnavailable(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Intent settingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.getPackageName(), null));
        PendingIntent openSettings = PendingIntent.getActivity(context, 0, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, DownloadUtil.DOWNLOAD_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("No download folder set")
            .setContentText("Tap to set one in settings")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setContentIntent(openSettings)
            .setAutoCancel(true);

        manager.notify(1011, builder.build());
    }

    private static void notifyFailure(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(context, "External download failed: " + message, Toast.LENGTH_LONG).show()
        );
    }

    private static void notifySuccess(Context context, String name) {
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(context, "Download success: " + name, Toast.LENGTH_SHORT).show()
        );
    }

    private static void notifyExists(Context context, String name) {
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(context, "Already exists: " + name, Toast.LENGTH_SHORT).show()
        );
    }
}
