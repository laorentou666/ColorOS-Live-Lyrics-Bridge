package io.github.andrealtb.lockscreenlyrics.online;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class AmllTtmlCacheTest {
    private Context context;
    private AmllTtmlCache cache;
    private File directory;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        cache = new AmllTtmlCache(context);
        cache.clear();
        directory = new File(context.getCacheDir(), "amll-ttml");
        Files.createDirectories(directory.toPath());
    }

    @Test
    public void writesAndReadsTtmlUsingHashedPlatformAndSongIdentity() throws Exception {
        cache.write("netease", "123", "<tt>lyrics</tt>");

        byte[] digest = MessageDigest.getInstance("SHA-256").digest("netease:123".getBytes(StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder();
        for (byte value : digest) key.append(String.format("%02x", value & 0xff));
        assertEquals("<tt>lyrics</tt>", new String(Files.readAllBytes(
                new File(directory, key + ".ttml").toPath()), StandardCharsets.UTF_8));
        assertEquals("<tt>lyrics</tt>", cache.read("netease", "123"));
        assertNull(cache.read("qqmusic", "123"));
    }

    @Test
    public void reachingTheLimitEvictsTheOldestEntryAndPersistsTheLimit() throws Exception {
        File old = new File(directory, "old.ttml");
        File recent = new File(directory, "recent.ttml");
        Files.write(old.toPath(), new byte[500_000]);
        Files.write(recent.toPath(), new byte[500_000]);
        assertTrue(old.setLastModified(recent.lastModified() - 1000));

        cache.setLimitMb(1);

        assertEquals(1, AmllTtmlCache.readLimitMb(context));
        assertFalse(old.exists());
        assertTrue(recent.exists());
    }

    @Test
    public void manualClearLeavesOtherCacheFilesAndTheConfiguredLimit() throws Exception {
        cache.setLimitMb(250);
        File cachedLyric = new File(directory, "track.ttml");
        File otherCache = new File(context.getCacheDir(), "other-cache");
        Files.write(cachedLyric.toPath(), new byte[]{1});
        Files.write(otherCache.toPath(), new byte[]{1});

        cache.clear();

        assertFalse(cachedLyric.exists());
        assertTrue(otherCache.exists());
        assertEquals(250, AmllTtmlCache.readLimitMb(context));
    }
}
