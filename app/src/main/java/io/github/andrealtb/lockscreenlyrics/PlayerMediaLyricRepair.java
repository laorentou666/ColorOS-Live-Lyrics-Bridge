package io.github.andrealtb.lockscreenlyrics;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/** Completes the native lyric load omitted by ColorOS 16's player-state rebuild. */
final class PlayerMediaLyricRepair {
    static final String REBUILD_REASON = "loadPlayerMediaDataInBg";

    interface MetadataGate {
        boolean accepts(String packageName, String title, String artist, Object metadata);
    }

    private PlayerMediaLyricRepair() {
    }

    static Method resolveEntry(Class<?> managerClass, Class<?> mediaDataClass) {
        try {
            Method method = managerClass.getDeclaredMethod("onMediaDataLoaded",
                    String.class, String.class, mediaDataClass, String.class);
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class) {
                return null;
            }
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    static boolean repair(Object manager, String key, Object data, String reason,
            Method loader, MetadataGate gate) throws ReflectiveOperationException {
        if (!REBUILD_REASON.equals(reason) || data == null || key == null || key.isEmpty()) {
            return false;
        }
        Object token = get(data, "getToken");
        Object metadata = get(data, "getMetadata");
        if (token == null || metadata == null) return false;
        String packageName = text(get(data, "getPackageName"));
        String title = text(get(data, "getSong"));
        String artist = text(get(data, "getArtist"));
        if (!gate.accepts(packageName, title, artist, metadata)) return false;

        Object extension = get(data, "getMediaDataEx");
        if (extension == null) return false;
        Object previous = get(extension, "getLyricData");
        if (hasLines(previous)) return false;
        Object owner = get(manager, "getMediaDataManagerEx");
        if (!loader.getDeclaringClass().isInstance(owner)) return false;

        // Reload this entry's validated metadata. A null previous lyric prevents borrowing
        // state from another song or MediaSession, including a session that reused the key.
        Object loaded = loader.invoke(owner, key, metadata, packageName, title, artist, null);
        if (!hasLines(loaded)) return false;
        Method setter = extension.getClass().getMethod("setLyricData", loader.getReturnType());
        setter.invoke(extension, loaded);
        return true;
    }

    private static boolean hasLines(Object lyric) throws ReflectiveOperationException {
        if (lyric == null) return false;
        Object lines = get(lyric, "getLines");
        return lines instanceof List<?> && !((List<?>) lines).isEmpty();
    }

    private static Object get(Object owner, String name) throws ReflectiveOperationException {
        return owner.getClass().getMethod(name).invoke(owner);
    }

    private static String text(Object value) {
        return value instanceof CharSequence ? value.toString() : "";
    }
}
