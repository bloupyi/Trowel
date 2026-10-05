package com.stackmc.trowel.engine;

import com.stackmc.trowel.Chat;
import com.stackmc.trowel.Session;
import com.stackmc.trowel.Trowel;
import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.api.MarkerSupport;
import com.stackmc.trowel.api.Keys;
import com.stackmc.trowel.pattern.Mask;
import com.stackmc.trowel.pattern.Masks;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.logging.Level;

/**
 * The engine: compute off the main thread, place in small batches, and be able to go back.
 *
 * <p>An operation runs in three steps. On the main thread, the chunks it reads are frozen
 * ({@link ChunkSnapshot}) with the marker settings. Computing then happens on another thread,
 * touching nothing: it returns the list of what to place. Finally, back on the main thread,
 * blocks are placed a few thousand per tick, and the previous state is kept for undo.</p>
 *
 * <p>The safeguards (buildable area, global mask, marker protection, size limit) live in the
 * batch itself, from computing on. Whatever happens while placing, the player is never left
 * stuck on "an operation is already running".</p>
 */
public final class Engine {

    private final Trowel trowel;

    public Engine(Trowel trowel) {
        this.trowel = trowel;
    }

    @FunctionalInterface
    public interface Compute {
        ChangeSet run(EditContext context);
    }

    /**
     * An operation to run.
     *
     * @param reads   what it needs to read; blocks placed outside of it are not protected
     * @param protect {@code true}: does not replace a marker (unless the player allowed it)
     * @param stroke  a brush stroke: silent, and merged with the neighbouring strokes of the same gesture
     */
    public record Job(String label, World world, Box reads, Compute compute, boolean protect, String stroke,
                      Consumer<Integer> then) {

        public static Job of(String label, World world, Box reads, Compute compute) {
            return new Job(label, world, reads, compute, true, null, null);
        }

        /** Markers are part of what is placed: copy, move, undo. */
        public Job unprotected() {
            return new Job(label, world, reads, compute, false, stroke, then);
        }

        public Job stroke(String key) {
            return new Job(label, world, reads, compute, protect, key, then);
        }

        public Job then(Consumer<Integer> next) {
            return new Job(label, world, reads, compute, protect, stroke, next);
        }
    }

    // --------------------------------------------------------------------- run

    public void submit(Player player, Job job) {
        Session session = trowel.session(player);
        if (session.isBusy()) {
            if (job.stroke() == null) {
                Chat.error(player, "An operation is already running.");
            }
            return;
        }

        Mask global = null;
        if (session.getGlobalMask() != null) {
            try {
                global = Masks.parse(trowel.expandMask(player.getUniqueId(), session.getGlobalMask()),
                        trowel.host().markers());
            } catch (IllegalArgumentException e) {
                Chat.error(player, "Global mask: " + e.getMessage());
                return;
            }
        }

        boolean protect = job.protect() && !session.isEditMarkers();
        EditContext context = capture(player, job.world(), job.reads(), protect, global);
        if (context == null) {
            return;
        }

        session.setBusy(true);
        session.setRunning(context.progress());
        boolean quiet = job.stroke() != null;
        BukkitTask ticker = quiet ? null : showProgress(player, session, job.label(), context.progress());
        Bukkit.getScheduler().runTaskAsynchronously(trowel.plugin(), () -> {
            ChangeSet changes;
            try {
                changes = job.compute().run(context);
            } catch (Throwable e) {
                Bukkit.getScheduler().runTask(trowel.plugin(), () -> {
                    stop(ticker);
                    abort(player, session, job.label(), e, quiet);
                });
                return;
            }
            Bukkit.getScheduler().runTask(trowel.plugin(), () -> {
                stop(ticker);
                try {
                    apply(player, session, job, changes, context.progress());
                } catch (Throwable e) {
                    abort(player, session, job.label(), e, quiet);
                }
            });
        });
    }

    /**
     * The subtitle showing computing progress, refreshed every quarter second. It only shows
     * after a fifth of a second: a quick operation does not flicker.
     */
    private BukkitTask showProgress(Player player, Session session, String label, Progress progress) {
        return new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !session.isBusy() || session.getRunning() != progress) {
                    cancel();
                    return;
                }
                subtitle(player, label + ": " + progress.describe(), NamedTextColor.AQUA, 25);
            }
        }.runTaskTimer(trowel.plugin(), 4L, 5L);
    }

    private static void stop(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    /** A subtitle alone: the empty title does not hide the screen. */
    static void subtitle(Player player, String text, NamedTextColor color, int stay) {
        player.showTitle(Title.title(Component.empty(), Component.text(text, color), 0, stay, 6));
    }

    /** Stops the player's running operation: computing at once, placing at the next tick. */
    public boolean cancel(Player player) {
        Progress running = trowel.session(player).getRunning();
        if (running == null || !trowel.session(player).isBusy()) {
            return false;
        }
        running.cancel();
        return true;
    }

    /** A read without placing anything: copy, count. */
    public <T> void read(Player player, World world, Box box, String label, Function<EditContext, T> compute,
                         Consumer<T> then) {
        Session session = trowel.session(player);
        if (session.isBusy()) {
            Chat.error(player, "An operation is already running.");
            return;
        }
        EditContext context = capture(player, world, box, false, null);
        if (context == null) {
            return;
        }
        session.setBusy(true);
        session.setRunning(context.progress());
        context.progress().phase("reading", 0);
        BukkitTask ticker = showProgress(player, session, label, context.progress());
        Bukkit.getScheduler().runTaskAsynchronously(trowel.plugin(), () -> {
            T result;
            try {
                result = compute.apply(context);
            } catch (Throwable e) {
                Bukkit.getScheduler().runTask(trowel.plugin(), () -> {
                    stop(ticker);
                    abort(player, session, label, e, false);
                });
                return;
            }
            Bukkit.getScheduler().runTask(trowel.plugin(), () -> {
                stop(ticker);
                session.setBusy(false);
                session.setRunning(null);
                try {
                    then.accept(result);
                } catch (Throwable e) {
                    abort(player, session, label, e);
                }
            });
        });
    }

    private void abort(Player player, Session session, String label, Throwable e) {
        abort(player, session, label, e, false);
    }

    /**
     * Frees the player and says why; an unexpected error also goes to the console. A brush
     * stroke says it in the action bar: a refused stroke on every click would flood the chat.
     */
    private void abort(Player player, Session session, String label, Throwable e, boolean quiet) {
        session.setBusy(false);
        session.setRunning(null);
        if (!player.isOnline()) {
            return;
        }
        if (e instanceof Progress.Cancelled) {
            if (!quiet) {
                subtitle(player, label + ": cancelled", NamedTextColor.GOLD, 30);
                Chat.info(player, label + ": cancelled, nothing was placed.");
            }
            return;
        }
        if (!(e instanceof IllegalArgumentException)) {
            trowel.plugin().getLogger().log(Level.WARNING, label + " failed", e);
        }
        String message = label + ": " + reason(e);
        if (quiet) {
            Chat.bar(player, message, NamedTextColor.RED);
        } else {
            subtitle(player, label + ": failed", NamedTextColor.RED, 40);
            Chat.error(player, message);
        }
    }

    private static String reason(Throwable e) {
        if (e instanceof OutOfMemoryError) {
            return "not enough memory for such a large area. Shrink the selection or the radius.";
        }
        if (e instanceof StackOverflowError) {
            return "computation too deep (recursive expression or pattern?).";
        }
        if (e instanceof IllegalArgumentException && e.getMessage() != null) {
            return e.getMessage();
        }
        return "internal error (" + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "), nothing was placed.";
    }

    /** Freezes what the operation will read. {@code null}, with a message, if it is too big. */
    private EditContext capture(Player player, World world, Box reads, boolean protect, Mask global) {
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        Long2ObjectOpenHashMap<ChunkSnapshot> chunks = new Long2ObjectOpenHashMap<>();
        Long2ObjectMap<Map<String, String>> params = new Long2ObjectOpenHashMap<>();
        Box bounds = trowel.host().bounds(player, world);
        if (reads != null && bounds != null) {
            // Nothing to read far from the buildable area: nothing would be placed, and reading loads chunks.
            reads = reads.intersect(bounds.grow(4));
            if (reads == null) {
                Chat.error(player, "It is entirely outside the buildable area.");
                return null;
            }
        }
        if (reads != null) {
            Box box = reads.grow(2).withY(Math.max(minY, reads.minY() - 2), Math.min(maxY, reads.maxY() + 2));
            long count = (long) ((box.maxX() >> 4) - (box.minX() >> 4) + 1) * ((box.maxZ() >> 4) - (box.minZ() >> 4) + 1);
            if (count > trowel.settings().maxChunks(player)) {
                Chat.error(player, "Area too large: " + count + " chunks to read, the maximum is "
                        + trowel.settings().maxChunks(player) + ".");
                return null;
            }
            for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                    chunks.put(Views.chunkKey(cx, cz), world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false));
                }
            }
            params = trowel.host().markers().within(world, box);
        }
        MarkerSupport markers = trowel.host().markers();
        LongOpenHashSet placed = new LongOpenHashSet();
        BlockView bare = Views.snapshot(chunks, minY, maxY, trowel.air(), LongSets.EMPTY_SET);
        for (long key : params.keySet().toLongArray()) {
            if (markers.isMarker(bare.type(Keys.x(key), Keys.y(key), Keys.z(key)))) {
                placed.add(key);
            } else {
                params.remove(key);
            }
        }
        BlockView view = Views.snapshot(chunks, minY, maxY, trowel.air(), placed);
        ChangeSet.Gate gate = !protect && global == null ? null : (x, y, z, data) -> {
            if (protect && view.marker(x, y, z) && view.type(x, y, z) != data.getMaterial()) {
                return false;
            }
            return global == null || global.test(x, y, z, view);
        };
        return new EditContext(view, params, markers, bounds, trowel.settings().maxBlocks(player), gate,
                trowel.transforms(), trowel.air(), new Progress());
    }

    // ------------------------------------------------------------------- place

    private void apply(Player player, Session session, Job job, ChangeSet changes, Progress progress) {
        World world = job.world();
        Batch run = new Batch(world.getUID(), job.stroke(), Bukkit.getCurrentTick(), job.label());
        LongArrayList keys = new LongArrayList(changes.blocks().keySet());
        write(player, world, keys, changes.blocks()::get, run, job.stroke() == null ? job.label() : null, progress,
                () -> finish(player, session, job, changes, run, progress.cancelled()),
                e -> abort(player, session, job.label(), e));
    }

    private void finish(Player player, Session session, Job job, ChangeSet changes, Batch run, boolean cancelled) {
        World world = job.world();
        boolean paramsChanged = writeParams(world, changes, run);
        session.setBusy(false);
        session.setRunning(null);

        if (run.isEmpty()) {
            if (job.stroke() == null && changes.outside() > 0) {
                Chat.error(player, job.label() + ": everything is outside the buildable area.");
            } else if (job.stroke() == null && changes.refused() > 0) {
                Chat.info(player, job.label() + ": nothing to change (" + changes.refused()
                        + " block(s) dropped by the global mask or marker protection).");
            } else if (job.stroke() == null) {
                Chat.info(player, job.label() + ": nothing to change.");
            }
            if (job.then() != null) {
                job.then().accept(0);
            }
            return;
        }

        trowel.host().changed(world, paramsChanged);
        record(session, run);

        int count = run.size();
        if (job.stroke() == null) {
            String done = job.label() + ": " + Progress.blocks(count) + (cancelled ? " (cancelled while placing)" : "");
            subtitle(player, done, cancelled ? NamedTextColor.GOLD : NamedTextColor.GREEN, 30);
            Chat.info(player, job.label() + ": ", Progress.blocks(count) + (cancelled
                    ? ", placing stopped by //cancel" : ""));
            if (changes.outside() > 0) {
                Chat.hint(player, changes.outside() + " block(s) outside the buildable area ignored.");
            }
            if (changes.refused() > 0) {
                Chat.hint(player, changes.refused() + " block(s) dropped by the global mask or marker "
                        + "protection (//markers).");
            }
            Chat.hint(player, "//undo to go back.");
            Box bounds = Batch.bounds(run.after.isEmpty() ? run.paramsAfter.keySet() : run.after.keySet());
            if (bounds != null) {
                trowel.hud().flash(player, world, bounds);
            }
        } else {
            Chat.bar(player, job.label() + ": " + count + " block" + (count > 1 ? "s" : ""), NamedTextColor.AQUA);
        }
        if (job.then() != null) {
            job.then().accept(count);
        }
    }

    /**
     * Brings markers in line with the placed blocks.
     *
     * <p>A covered marker disappears with its settings; a pasted or moved marker carries its
     * own; a marker replaced by the same block keeps them; a marker asked for by name becomes a
     * real marker. A block that merely has a marker's material stays a block.
     * {@code null}: no marker at that position.</p>
     */
    private boolean writeParams(World world, ChangeSet changes, Batch run) {
        MarkerSupport markers = trowel.host().markers();
        LongOpenHashSet touched = new LongOpenHashSet(run.after.keySet());
        touched.addAll(changes.params().keySet());
        touched.addAll(changes.named());
        boolean changed = false;
        for (long key : touched) {
            Block block = world.getBlockAt(Keys.x(key), Keys.y(key), Keys.z(key));
            BlockData wanted = changes.get(key);
            if (!run.after.containsKey(key) && wanted != null && !block.getBlockData().equals(wanted)) {
                continue;
            }
            Map<String, String> before = markers.read(world, key);
            Map<String, String> after;
            if (changes.params().containsKey(key)) {
                after = changes.params().get(key);
            } else if (before != null && (!run.before.containsKey(key)
                    || run.before.get(key).getMaterial() == run.after.get(key).getMaterial())) {
                after = before;
            } else {
                after = changes.named().contains(key) ? Map.of() : null;
            }
            if (!markers.isMarker(block.getType())) {
                after = null;
            }
            if (!Objects.equals(before, after)) {
                markers.write(world, key, after == null ? null : new LinkedHashMap<>(after));
                run.paramsBefore.put(key, before == null ? null : new LinkedHashMap<>(before));
                run.paramsAfter.put(key, after == null ? null : new LinkedHashMap<>(after));
                changed = true;
            }
        }
        return changed;
    }

    private void record(Session session, Batch run) {
        Deque<Batch> undo = session.getUndo();
        Batch top = undo.peek();
        if (run.stroke != null && top != null && run.stroke.equals(top.stroke)
                && run.lastTick - top.lastTick <= trowel.settings().strokeTicks()) {
            top.absorb(run);
        } else {
            undo.push(run);
            while (undo.size() > trowel.settings().undoSteps()) {
                undo.removeLast();
            }
        }
        session.getRedo().clear();
    }

    /**
     * Places the blocks, a few thousand per tick.
     *
     * <p>A block the server refuses is skipped rather than stopping the placing; a world unloaded
     * along the way stops it cleanly.</p>
     *
     * @param record   where to keep the previous state, or {@code null} for an undo
     * @param label    progress shown as a subtitle, or {@code null} to stay quiet
     * @param progress stops placing if the player cancels, or {@code null}
     */
    private void write(Player player, World world, LongArrayList keys, LongFunction<BlockData> wanted,
                       Batch record, String label, Progress progress, Runnable done,
                       Consumer<Throwable> failed) {
        int perTick = trowel.settings().blocksPerTick();
        int[] cursor = {0};
        UUID worldId = world.getUID();
        if (keys.size() <= perTick) {
            try {
                writeSome(world, keys, wanted, record, cursor, perTick);
            } catch (Throwable e) {
                failed.accept(e);
                return;
            }
            done.run();
            return;
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (Bukkit.getWorld(worldId) == null) {
                        cancel();
                        failed.accept(new IllegalArgumentException("the world was closed while placing."));
                        return;
                    }
                    if (progress != null && progress.cancelled()) {
                        cancel();
                        done.run();
                        return;
                    }
                    writeSome(world, keys, wanted, record, cursor, perTick);
                    if (cursor[0] < keys.size()) {
                        if (label != null && player.isOnline()) {
                            subtitle(player, label + ": placing " + (100L * cursor[0] / keys.size()) + "% ("
                                    + Progress.blocks(cursor[0]) + " of " + keys.size() + ")", NamedTextColor.AQUA, 25);
                        }
                        return;
                    }
                    cancel();
                    done.run();
                } catch (Throwable e) {
                    cancel();
                    failed.accept(e);
                }
            }
        }.runTaskTimer(trowel.plugin(), 0L, 1L);
    }

    private static void writeSome(World world, LongArrayList keys, LongFunction<BlockData> wanted, Batch record,
                                  int[] cursor, int budget) {
        while (budget-- > 0 && cursor[0] < keys.size()) {
            long key = keys.getLong(cursor[0]++);
            BlockData data = wanted.apply(key);
            if (data == null) {
                continue;
            }
            Block block = world.getBlockAt(Keys.x(key), Keys.y(key), Keys.z(key));
            BlockData current = block.getBlockData();
            if (current.equals(data)) {
                continue;
            }
            try {
                block.setBlockData(data, false);
            } catch (RuntimeException refused) {
                continue;
            }
            if (record != null) {
                record.before.putIfAbsent(key, current);
                record.after.put(key, data);
            }
        }
    }

    // --------------------------------------------------------------- undo/redo

    public void undo(Player player, int times) {
        step(player, true, Math.max(1, times));
    }

    public void redo(Player player, int times) {
        step(player, false, Math.max(1, times));
    }

    /**
     * Undoes or redoes the last operation.
     *
     * <p>Only in the world where it happened, and where the player stands: otherwise a player
     * who moved to another world could change the one they left.</p>
     */
    private void step(Player player, boolean undo, int times) {
        Session session = trowel.session(player);
        if (session.isBusy()) {
            Chat.error(player, "An operation is already running.");
            return;
        }
        Deque<Batch> from = undo ? session.getUndo() : session.getRedo();
        if (from.isEmpty()) {
            Chat.error(player, undo ? "Nothing to undo." : "Nothing to redo.");
            return;
        }
        Batch batch = from.peek();
        World world = Bukkit.getWorld(batch.world);
        if (world == null) {
            from.pop();
            Chat.error(player, "The world of this operation is no longer loaded: it is forgotten.");
            return;
        }
        if (!world.equals(player.getWorld())) {
            Chat.error(player, "This operation happened in another world: go back there to "
                    + (undo ? "undo it." : "redo it."));
            return;
        }
        from.pop();

        Long2ObjectOpenHashMap<BlockData> blocks = undo ? batch.before : batch.after;
        Long2ObjectOpenHashMap<Map<String, String>> params = undo ? batch.paramsBefore : batch.paramsAfter;
        session.setBusy(true);
        String label = undo ? "Undo" : "Redo";
        write(player, world, new LongArrayList(blocks.keySet()), blocks::get, null, label, null, () -> {
            MarkerSupport markers = trowel.host().markers();
            params.forEach((key, values) -> markers.write(world, key, values));
            trowel.host().changed(world, !params.isEmpty());
            session.setBusy(false);
            Deque<Batch> to = undo ? session.getRedo() : session.getUndo();
            to.push(batch);
            while (to.size() > trowel.settings().undoSteps()) {
                to.removeLast();
            }
            Chat.info(player, label + ": ", batch.size() + " blocks");
            Box bounds = Batch.bounds(blocks.isEmpty() ? params.keySet() : blocks.keySet());
            if (bounds != null) {
                trowel.hud().flash(player, world, bounds);
            }
            if (times > 1) {
                step(player, undo, times - 1);
            }
        }, e -> abort(player, session, label, e));
    }

    // ------------------------------------------------------------ fine history

    /** The player's history, most recent first. */
    public java.util.List<Batch> history(Player player) {
        return java.util.List.copyOf(trowel.session(player).getUndo());
    }

    /**
     * Undoes a single operation, even if others followed. Blocks a more recent operation touched
     * again do not move: later work is not undone.
     *
     * @param index 1 for the most recent
     */
    public void undoOnly(Player player, int index) {
        Session session = trowel.session(player);
        java.util.List<Batch> list = new java.util.ArrayList<>(session.getUndo());
        if (index < 1 || index > list.size()) {
            Chat.error(player, "There are only " + list.size() + " operation(s) in the history.");
            return;
        }
        Batch target = list.get(index - 1);
        LongOpenHashSet later = new LongOpenHashSet();
        for (Batch newer : list.subList(0, index - 1)) {
            later.addAll(newer.after.keySet());
            later.addAll(newer.paramsAfter.keySet());
        }
        Batch part = new Batch(target.world, null, Bukkit.getCurrentTick(), "Undo of " + target.label);
        moveInto(target, part, key -> !later.contains(key), false);
        if (target.isEmpty() && target.paramsBefore.isEmpty()) {
            session.getUndo().remove(target);
        }
        revert(player, session, part, later.isEmpty() ? null
                : "blocks touched since then are kept as they are.");
    }

    /** Undoes the last {@code count} operations, but only inside this box. */
    public void undoInside(Player player, Box box, int count) {
        Session session = trowel.session(player);
        UUID world = player.getWorld().getUID();
        Batch part = new Batch(world, null, Bukkit.getCurrentTick(), "Undo inside the selection");
        int seen = 0;
        for (Batch batch : new java.util.ArrayList<>(session.getUndo())) {
            if (seen >= count) {
                break;
            }
            if (!batch.world.equals(world)) {
                continue;
            }
            seen++;
            moveInto(batch, part, key -> box.contains(Keys.x(key), Keys.y(key), Keys.z(key)), true);
            if (batch.isEmpty() && batch.paramsBefore.isEmpty()) {
                session.getUndo().remove(batch);
            }
        }
        revert(player, session, part, null);
    }

    /** Undoes the last gesture of this brush, even if other operations followed. */
    public void undoStroke(Player player, String stroke) {
        java.util.List<Batch> list = history(player);
        for (int i = 0; i < list.size(); i++) {
            if (stroke.equals(list.get(i).stroke)) {
                undoOnly(player, i + 1);
                return;
            }
        }
        Chat.error(player, "This brush has nothing in the history.");
    }

    /**
     * Takes the kept blocks out of {@code from} and puts them in {@code part}: their previous
     * state to place, and their current state to be able to redo. Going back in history
     * ({@code older}), the oldest state wins for placing, the most recent for redoing.
     */
    private static void moveInto(Batch from, Batch part, java.util.function.LongPredicate keep, boolean older) {
        for (long key : from.before.keySet().toLongArray()) {
            if (!keep.test(key)) {
                continue;
            }
            if (older || !part.before.containsKey(key)) {
                part.before.put(key, from.before.get(key));
            }
            if (!part.after.containsKey(key)) {
                part.after.put(key, from.after.get(key));
            }
            from.before.remove(key);
            from.after.remove(key);
        }
        for (long key : from.paramsBefore.keySet().toLongArray()) {
            if (!keep.test(key)) {
                continue;
            }
            if (older || !part.paramsBefore.containsKey(key)) {
                part.paramsBefore.put(key, from.paramsBefore.get(key));
            }
            if (!part.paramsAfter.containsKey(key)) {
                part.paramsAfter.put(key, from.paramsAfter.get(key));
            }
            from.paramsBefore.remove(key);
            from.paramsAfter.remove(key);
        }
    }

    /** Places the previous state of a history part; it can then be redone with //redo. */
    private void revert(Player player, Session session, Batch part, String note) {
        if (part.before.isEmpty() && part.paramsBefore.isEmpty()) {
            Chat.error(player, "Nothing to undo here.");
            return;
        }
        World world = Bukkit.getWorld(part.world);
        if (world == null || !world.equals(player.getWorld())) {
            Chat.error(player, "This operation happened in another world: go back there to undo it.");
            return;
        }
        if (session.isBusy()) {
            Chat.error(player, "An operation is already running.");
            return;
        }
        session.setBusy(true);
        Long2ObjectOpenHashMap<BlockData> blocks = part.before;
        write(player, world, new LongArrayList(blocks.keySet()), blocks::get, null, part.label, null, () -> {
            MarkerSupport markers = trowel.host().markers();
            part.paramsBefore.forEach((key, values) -> markers.write(world, key, values));
            trowel.host().changed(world, !part.paramsBefore.isEmpty());
            session.setBusy(false);
            session.getRedo().push(part);
            Chat.info(player, part.label + ": ", Progress.blocks(part.before.size()));
            if (note != null) {
                Chat.hint(player, note);
            }
            Chat.hint(player, "//redo to go back.");
            Box bounds = Batch.bounds(blocks.isEmpty() ? part.paramsBefore.keySet() : blocks.keySet());
            if (bounds != null) {
                trowel.hud().flash(player, world, bounds);
            }
        }, e -> abort(player, session, part.label, e));
    }

    public int undoDepth(Player player) {
        return trowel.session(player).getUndo().size();
    }
}
