package com.boydti.fawe.bukkit.v0;

public class ChunkListener_8 extends ChunkListener {

    @Override
    protected int getDepth(Exception ex) {
        return ex.getStackTrace().length;
    }

    @Override
    protected StackTraceElement getElement(Exception ex, int index) {
        return ex.getStackTrace()[index];
    }
}
