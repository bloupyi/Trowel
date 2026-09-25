package com.stackmc.trowel.engine;

import java.util.Locale;

/**
 * Progress of an operation, written by the compute thread and read by the main thread to show
 * it as a subtitle. Also used to cancel it: computing stops at the next checkpoint, placing at
 * the next tick.
 *
 * <p>Computing counts locally and only publishes one step in a thousand: following progress
 * costs almost nothing, even over millions of blocks.</p>
 */
public final class Progress {

    /** Thrown inside computing when the player cancels; without a stack trace it costs nothing. */
    public static final class Cancelled extends RuntimeException {
        public Cancelled() {
            super("cancelled.", null, false, false);
        }
    }

    private final long started = System.nanoTime();
    private volatile String phase = "computing";
    private volatile long total;
    private volatile long done;
    private volatile long found;
    private volatile boolean cancelled;
    private long counter;

    /** A new stage of {@code total} steps (0 if unknown). */
    public void phase(String name, long total) {
        this.phase = name;
        this.total = Math.max(0, total);
        this.counter = 0;
        this.done = 0;
        check();
    }

    public void tick() {
        if ((++counter & 1023) == 0) {
            done = counter;
            check();
        }
    }

    public void tick(long steps) {
        counter += steps;
        done = counter;
        check();
    }

    /** Blocks found so far, published by the batch. */
    public void found(long blocks) {
        found = blocks;
    }

    public void check() {
        if (cancelled) {
            throw new Cancelled();
        }
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean cancelled() {
        return cancelled;
    }

    public double seconds() {
        return (System.nanoTime() - started) / 1e9;
    }

    /** {@code computing 42% (1.3 s)}, or {@code computing: 12,400 blocks (1.3 s)} without a known total. */
    public String describe() {
        long t = total;
        String time = String.format(Locale.ROOT, "%.1f s", seconds());
        if (t > 0) {
            long percent = Math.min(99, 100L * done / t);
            return phase + " " + percent + "%" + (found > 0 ? ", " + blocks(found) : "") + " (" + time + ")";
        }
        return phase + (found > 0 ? ": " + blocks(found) : "...") + " (" + time + ")";
    }

    public static String blocks(long n) {
        return String.format(Locale.ROOT, "%,d", n)
                + (n > 1 ? " blocks" : " block");
    }
}
