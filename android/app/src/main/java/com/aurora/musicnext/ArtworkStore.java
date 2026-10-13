package com.aurora.musicnext;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

final class ArtworkStore {
    private static final long LIMIT = 64L * 1024 * 1024;
    private static File[] images(File directory) {
        File[] files = directory.listFiles(file -> file.isFile() && file.getName().matches("[0-9a-f]{24}\\.jpg"));
        return files == null ? new File[0] : files;
    }
    private static long bytes(File directory) {
        long total = 0; for (File file : images(directory)) total += file.length(); return total;
    }
    static synchronized long bytes(Context context) {
        return bytes(new File(context.getFilesDir(), "artwork")) + bytes(new File(context.getFilesDir(), "auto-artwork")) + bytes(new File(context.getFilesDir(), "chosen-artwork"));
    }
    static synchronized String save(Context context, String id, byte[] data) throws IOException {
        File directory = new File(context.getFilesDir(), "artwork");
        String path = encode(directory, id, data); prune(context, new File(path)); return path;
    }
    static synchronized String saveRemote(Context context, String id, byte[] data) throws IOException {
        String path = encode(new File(context.getFilesDir(), "auto-artwork"), id, data); prune(context, new File(path)); return path;
    }
    static synchronized String preview(Context context, String token, byte[] data) throws IOException {
        File directory = new File(context.getCacheDir(), "asset-previews");
        long now = System.currentTimeMillis();
        for (File file : images(directory)) if (now < file.lastModified() || now - file.lastModified() >= 10 * 60_000L) file.delete();
        if (images(directory).length >= 8 || bytes(directory) > 16L * 1024 * 1024) throw new IOException("Resource preview limit reached");
        return encode(directory, token, data);
    }
    static synchronized void removePreview(Context context, String path) {
        if (path == null || path.isEmpty()) return;
        try {
            File file = new File(path).getCanonicalFile(), directory = new File(context.getCacheDir(), "asset-previews").getCanonicalFile();
            if (directory.equals(file.getParentFile()) && file.getName().matches("[0-9a-f]{24}\\.jpg")) file.delete();
        } catch (IOException ignored) { }
    }
    static synchronized String choose(Context context, String preview, String oldPath) throws IOException {
        File source = new File(preview).getCanonicalFile(), previews = new File(context.getCacheDir(), "asset-previews").getCanonicalFile();
        if (!previews.equals(source.getParentFile()) || !source.getName().matches("[0-9a-f]{24}\\.jpg") || !source.isFile()
            || source.length() <= 0 || source.length() > 8 * 1024 * 1024) throw new IOException("Resource preview expired");
        File directory = new File(context.getFilesDir(), "chosen-artwork");
        File old = ownedChoice(context, oldPath); long oldBytes = old != null && old.isFile() ? old.length() : 0;
        if (bytes(directory) - oldBytes + source.length() > LIMIT || images(directory).length - (oldBytes > 0 ? 1 : 0) >= 400)
            throw new IOException("Chosen artwork storage is full");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Unable to create artwork directory");
        String id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        File target = new File(directory, id + ".jpg"), temporary = File.createTempFile(id, ".part", directory);
        try {
            try (java.io.FileInputStream input = new java.io.FileInputStream(source); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192]; int count, total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count; if (total > 8 * 1024 * 1024) throw new IOException("Resource preview too large"); output.write(buffer, 0, count);
                }
            }
            if (!temporary.renameTo(target)) throw new IOException("Unable to save chosen artwork");
            return target.getAbsolutePath();
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    private static File ownedChoice(Context context, String path) throws IOException {
        if (path == null || path.isEmpty()) return null;
        File file = new File(path).getCanonicalFile(), directory = new File(context.getFilesDir(), "chosen-artwork").getCanonicalFile();
        return directory.equals(file.getParentFile()) && file.getName().matches("[0-9a-f]{24}\\.jpg") ? file : null;
    }
    static synchronized void removeChoice(Context context, String path) {
        try { File file = ownedChoice(context, path); if (file != null) file.delete(); } catch (IOException ignored) { }
    }
    static String embedded(Context context, String id) {
        if (!id.matches("[0-9a-f]{24}")) return null;
        File file = new File(new File(context.getFilesDir(), "artwork"), id + ".jpg");
        return file.isFile() ? file.getAbsolutePath() : null;
    }
    private static String encode(File directory, String id, byte[] data) throws IOException {
        if (!id.matches("[0-9a-f]{24}") || data == null || data.length == 0 || data.length > 8 * 1024 * 1024)
            throw new IOException("Invalid or oversized artwork");
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 8000 || bounds.outHeight > 8000)
            throw new IOException("Invalid artwork dimensions");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 768) options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) throw new IOException("Unable to decode artwork");
        File temporary = null;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Unable to create artwork directory");
            temporary = File.createTempFile(id, ".part", directory);
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)) throw new IOException("Unable to encode artwork");
            }
            File target = new File(directory, id + ".jpg");
            if (!temporary.renameTo(target)) throw new IOException("Unable to save artwork");
            return target.getAbsolutePath();
        } finally {
            bitmap.recycle();
            if (temporary != null && temporary.exists()) temporary.delete();
        }
    }
    private static void prune(Context context, File keep) {
        java.util.List<File> list = new java.util.ArrayList<>();
        for (String directory : new String[]{"artwork", "auto-artwork"}) java.util.Collections.addAll(list, images(new File(context.getFilesDir(), directory)));
        File[] files = list.toArray(new File[0]);
        long total = 0; for (File file : files) total += file.length();
        if (files.length <= 400 && total <= LIMIT) return;
        java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
        int remaining = files.length;
        for (File file : files) {
            if (remaining <= 400 && total <= LIMIT) break;
            long size = file.length();
            if (!file.equals(keep) && file.delete()) { total -= size; remaining--; }
        }
    }
}
