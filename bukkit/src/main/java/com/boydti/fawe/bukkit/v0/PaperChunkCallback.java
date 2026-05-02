package com.boydti.fawe.bukkit.v0;

import org.bukkit.Chunk;
import org.bukkit.World;
import java.lang.reflect.Method;
import java.util.function.Consumer;

public abstract class PaperChunkCallback {
    public PaperChunkCallback(World world, int x, int z) {
        try {
            Method method = world.getClass().getMethod("getChunkAtAsync", int.class, int.class, Consumer.class);
            method.invoke(world, x, z, (Consumer<Chunk>) chunk -> PaperChunkCallback.this.onLoad(chunk));
        } catch (Throwable e) {
            onLoad(world.getChunkAt(x, z));
        }
    }

    public abstract void onLoad(Chunk chunk);
}
