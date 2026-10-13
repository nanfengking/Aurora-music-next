package com.aurora.musicnext;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** All files are isolated synthetic fixtures; never touches actual artwork or user media. */
@RunWith(AndroidJUnit4.class)
public class ArtworkStoreInstrumentedTest {
    private Context context;
    private byte[] image;
    private final String id = "0123456789abcdef01234567";
    @Before public void before() throws Exception {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "asset-fixture-" + java.util.UUID.randomUUID();
        context = new ContextWrapper(base) {
            @Override public File getFilesDir() { return new File(base.getFilesDir(), name); }
            @Override public File getCacheDir() { return new File(base.getCacheDir(), name); }
        };
        Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            bitmap.eraseColor(android.graphics.Color.BLUE); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); image = output.toByteArray();
        } finally { bitmap.recycle(); }
    }
    @After public void after() {
        for (File root : new File[]{context.getFilesDir(), context.getCacheDir()}) {
            for (String directory : new String[]{"artwork", "auto-artwork", "chosen-artwork", "asset-previews"}) {
                File child = new File(root, directory); File[] files = child.listFiles();
                if (files != null) for (File file : files) if (file.isFile() && file.getName().matches("[0-9a-f]{24}(\\.jpg|[0-9]+\\.part)")) assertTrue(file.delete());
                if (child.exists()) assertTrue(child.delete());
            }
            if (root.exists()) assertTrue(root.delete());
        }
    }
    @Test public void previewCleanupKeepsChosenAndEmbeddedArtwork() throws Exception {
        String embedded = ArtworkStore.save(context, id, image), remote = ArtworkStore.saveRemote(context, id, image);
        assertNotEquals(embedded, remote); assertEquals(embedded, ArtworkStore.embedded(context, id));
        String preview = ArtworkStore.preview(context, id, image), chosen = ArtworkStore.choose(context, preview, null);
        String otherPreview = ArtworkStore.preview(context, "abcdef0123456789abcdef01", image);
        ArtworkStore.removePreview(context, preview); assertFalse(new File(preview).exists()); assertTrue(new File(otherPreview).exists());
        ArtworkStore.removePreview(context, embedded); assertTrue(new File(embedded).exists());
        assertTrue(new File(chosen).isFile()); assertTrue(new File(embedded).isFile()); assertTrue(new File(remote).isFile());
        ArtworkStore.removeChoice(context, embedded); assertTrue(new File(embedded).isFile());
        ArtworkStore.removeChoice(context, chosen); assertFalse(new File(chosen).exists());
        assertThrows(Exception.class, () -> ArtworkStore.choose(context, embedded, null));
    }
    @Test public void chosenQuotaCannotEvictExistingManualImages() throws Exception {
        File chosenDirectory = new File(context.getFilesDir(), "chosen-artwork"); assertTrue(chosenDirectory.mkdirs());
        File existing = new File(chosenDirectory, id + ".jpg");
        try (RandomAccessFile file = new RandomAccessFile(existing, "rw")) { file.setLength(64L * 1024 * 1024); }
        String preview = ArtworkStore.preview(context, id, image);
        assertThrows(Exception.class, () -> ArtworkStore.choose(context, preview, null));
        assertTrue(existing.isFile()); assertEquals(64L * 1024 * 1024, existing.length());
        String replacement = ArtworkStore.choose(context, preview, existing.getAbsolutePath());
        assertTrue(existing.isFile()); assertTrue(new File(replacement).isFile());
        ArtworkStore.removeChoice(context, existing.getAbsolutePath()); assertTrue(new File(replacement).isFile());
    }
}
