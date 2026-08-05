package com.boydti.fawe.example;

import com.boydti.fawe.Fawe;
import org.bukkit.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class CarbonLightingEngine {

    private static final boolean AVAILABLE = findCarbonLightingEngine();

    private static volatile Method GET_LIGHT_ENGINE_METHOD;
    private static volatile Field LIGHT_ENGINE_FIELD;
    private static volatile Method RELIGHT_CHUNK_METHOD;

    private CarbonLightingEngine() {}


    public static boolean isAvailable() {
        return AVAILABLE;
    }

    static boolean relightChunk(NMSMappedFaweQueue queue, int x, int z) {
        if (!AVAILABLE) {
            return false;
        }
        try {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    queue.ensureChunkLoaded(x + dx, z + dz);
                }
            }

            Object chunk = queue.ensureChunkLoaded(x, z);
            if (chunk == null) {
                return false;
            }
            Object world = getChunkWorld(chunk);
            if (world == null) {
                return false;
            }

            Object lightEngine = getLightEngine(world);
            if (lightEngine == null) {
                return false;
            }

            Method relightChunk = getRelightChunkMethod(lightEngine.getClass(), chunk.getClass());
            if (relightChunk == null) {
                return false;
            }

            Object result = relightChunk.invoke(lightEngine, chunk);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Resolves the NMS {@code World}'s light engine, preferring the {@code getLightEngine()}
     * accessor and falling back to the raw {@code lightEngine} field if it isn't present.
     */
    private static Object getLightEngine(Object world) throws Exception {
        Method getter = GET_LIGHT_ENGINE_METHOD;
        if (getter == null) {
            try {
                getter = world.getClass().getMethod("getLightEngine");
                getter.setAccessible(true);
                GET_LIGHT_ENGINE_METHOD = getter;
            } catch (NoSuchMethodException ignored) {
            }
        }
        if (getter != null) {
            return getter.invoke(world);
        }

        Field field = LIGHT_ENGINE_FIELD;
        if (field == null) {
            field = world.getClass().getField("lightEngine");
            field.setAccessible(true);
            LIGHT_ENGINE_FIELD = field;
        }
        return field.get(world);
    }

    /**
     * Resolves the NMS-typed {@code relightChunk(Chunk)} overload — matched by the raw NMS
     * chunk's exact runtime type, not the {@code org.bukkit.Chunk} overload used by plugins.
     */
    private static Method getRelightChunkMethod(Class<?> lightEngineClass, Class<?> chunkClass) throws NoSuchMethodException {
        Method method = RELIGHT_CHUNK_METHOD;
        if (method != null
                && method.getDeclaringClass().isAssignableFrom(lightEngineClass)
                && method.getParameterTypes()[0] == chunkClass) {
            return method;
        }
        method = lightEngineClass.getMethod("relightChunk", chunkClass);
        method.setAccessible(true);
        RELIGHT_CHUNK_METHOD = method;
        return method;
    }

    private static Object getChunkWorld(Object chunk) throws Exception {
        try {
            Method getWorld = chunk.getClass().getMethod("getWorld");
            return getWorld.invoke(chunk);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Field world = chunk.getClass().getField("world");
            return world.get(chunk);
        } catch (NoSuchFieldException ignored) {
        }
        Field world = chunk.getClass().getDeclaredField("world");
        world.setAccessible(true);
        return world.get(chunk);
    }

    private static boolean findCarbonLightingEngine() {
        try {
            World.class.getDeclaredMethod("getLightEngine");
            Fawe.debug("Found Carbon lighting engine, using spigot lighting.");
            return true;
        } catch (Throwable t) {
            Fawe.debug("Carbon lighting engine not found, using vanilla lighting.");
            return false;
        }
    }

}
