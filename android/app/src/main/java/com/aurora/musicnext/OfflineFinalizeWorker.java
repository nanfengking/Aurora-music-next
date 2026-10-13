package com.aurora.musicnext;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.WorkManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public final class OfflineFinalizeWorker extends Worker {
    public OfflineFinalizeWorker(@NonNull Context context, @NonNull WorkerParameters parameters) { super(context, parameters); }

    static void enqueue(Context context, long id) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(OfflineFinalizeWorker.class)
            .setInputData(new Data.Builder().putLong("downloadId", id).build()).build();
        WorkManager.getInstance(context).enqueueUniqueWork("offline-" + id, ExistingWorkPolicy.KEEP, request);
    }

    @NonNull @Override public Result doWork() {
        long id = getInputData().getLong("downloadId", -1);
        if (id <= 0) return Result.failure();
        Context context = getApplicationContext();
        MusicStore store = new MusicStore(context);
        try {
            String[] pending = store.pendingDownload(id);
            if (pending == null || pending[1] == null) return Result.success();
            DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
                if (cursor == null || !cursor.moveToFirst()) return Result.failure();
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status != DownloadManager.STATUS_SUCCESSFUL) return status == DownloadManager.STATUS_FAILED ? Result.failure() : Result.retry();
            }
            File source = new File(pending[1]);
            File externalRoot = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if (externalRoot == null) return Result.retry();
            File externalDirectory = new File(externalRoot, "offline");
            if (!externalDirectory.getCanonicalFile().equals(source.getCanonicalFile().getParentFile())
                || !source.getName().startsWith(pending[0] + ".")) return Result.failure();
            File directory = new File(context.getFilesDir(), "offline");
            if (!directory.exists() && !directory.mkdirs()) return Result.retry();
            File target = new File(directory, source.getName());
            if (source.isFile()) {
                try { Os.rename(source.getAbsolutePath(), target.getAbsolutePath()); }
                catch (ErrnoException crossVolume) {
                    File temporary = File.createTempFile("aurora-", ".part", directory);
                    try {
                        try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(temporary)) {
                            byte[] buffer = new byte[64 * 1024]; int count;
                            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                            output.getFD().sync();
                        }
                        if (temporary.length() == 0) return Result.retry();
                        try { Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath()); }
                        catch (ErrnoException error) { return Result.retry(); }
                    } finally { if (temporary.exists()) temporary.delete(); }
                }
            }
            if (!target.isFile() || target.length() == 0) return Result.retry();
            store.finishDownload(pending[0], target.getAbsolutePath());
            manager.remove(id);
            return Result.success();
        } catch (IOException error) { return Result.retry(); }
        catch (Exception error) { return Result.failure(); }
        finally { store.close(); }
    }
}
