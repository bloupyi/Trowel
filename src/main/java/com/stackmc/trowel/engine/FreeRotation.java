package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Keys;
import com.stackmc.trowel.geom.Transform;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Stairs;

import java.util.Arrays;

/**
 * Turns blocks by any angle around a vertical axis. Slow but careful: what matters is a clean
 * build, a facade with its pillars, windows, sills, cornices and torches included.
 *
 * <p>Blocks are turned in three tiers, each fitting around the previous ones.</p>
 * <ol>
 *   <li><b>Full blocks</b> (stone, logs, planks, glass) are resampled: a target block looks at
 *       8 x 8 points of its square, turned back, and takes the source block seen most there,
 *       full as soon as full blocks fill half of it. Then the result is repaired: every source
 *       block shows somewhere, so does every opening of a wall (a window stays a window), two
 *       blocks touching by a face still touch (a wall at 45 degrees is a staircase without
 *       gaps), and a block left alone while its source was not is dropped if it shows
 *       elsewhere.</li>
 *   <li><b>Partial blocks</b> (stairs, slabs, fences, walls, panes, trapdoors, doors) are
 *       resampled the same way in the room the full blocks left, and kept touching each other and
 *       the full blocks they touched: a cornice stays one line along its wall, a sill one run
 *       under its window.</li>
 *   <li><b>Small details</b> (torches, buttons, ladders, signs, flowers, carpets) are placed one
 *       by one against the turned image of the block holding them, on the side that keeps them
 *       closest to their exact place: nothing floats, nothing is doubled.</li>
 * </ol>
 * <p>Beyond the nearest quarter turn, a block can face that way or the next quarter: each block
 * takes the one where its neighbours look most like before, so a stair still backs against its
 * wall and a torch still points away from its pillar.</p>
 */
final class FreeRotation<T> {

    static final int EMPTY = 0;
    static final int FULL = 1;
    static final int PARTIAL = 2;
    static final int DETAIL = 3;

    /** What the rotation needs to know of a block. */
    interface Kind<T> {
        /** {@link #EMPTY}, {@link #FULL}, {@link #PARTIAL} or {@link #DETAIL}. */
        int tier(T value);

        /** The block state turned by quarter turns, clockwise seen from above. */
        T turn(T value, int quarters);

        /** The face (index in {@link #FACES}) of the block holding this detail, or -1 if unknown. */
        int support(T value);
    }

    /**
     * @param cells   the turned blocks, by target position
     * @param sources for each target position, the source position its block comes from
     */
    record Result<T>(Long2ObjectOpenHashMap<T> cells, Long2LongOpenHashMap sources) {
    }

    /** East, west, up, down, south, north. */
    static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int SAMPLES = 8;
    private static final int TOTAL = SAMPLES * SAMPLES;
    private static final long NONE = Long.MIN_VALUE;

    private final Long2ObjectOpenHashMap<T> cells;
    private final Kind<T> kind;
    private final LongSet unique;
    private final double cos;
    private final double sin;
    private final double cx;
    private final double cz;
    /** Quarter turns of every block state, and the other quarter a block may take instead. */
    private final int quarters;
    private final int otherQuarters;
    /** How far the angle is from {@link #quarters}, in quarter turns (0 to 0.5). */
    private final double rest;
    private T air;
    private Pass full;
    private Pass partial;
    /** Details placed: target to source, and source to target. */
    private final Long2LongOpenHashMap detailAt = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap detailOf = new Long2LongOpenHashMap();
    private final Long2ObjectOpenHashMap<T> out = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap sources = new Long2LongOpenHashMap();

    private FreeRotation(Long2ObjectOpenHashMap<T> cells, double degrees, double cx, double cz, Kind<T> kind,
                         LongSet unique) {
        this.cells = cells;
        this.kind = kind;
        this.unique = unique;
        this.cx = cx;
        this.cz = cz;
        double a = Math.toRadians(degrees);
        cos = Math.cos(a);
        sin = Math.sin(a);
        quarters = (int) Math.round(degrees / 90.0);
        double r = degrees / 90.0 - quarters;
        rest = Math.abs(r);
        otherQuarters = quarters + (r >= 0 ? 1 : -1);
        detailAt.defaultReturnValue(NONE);
        detailOf.defaultReturnValue(NONE);
    }

    /**
     * {@code degrees} clockwise seen from above, like {@link Transform}, around the vertical axis
     * through (cx, cz).
     *
     * @param unique source positions whose block must show exactly once (chests, markers)
     */
    static <T> Result<T> turn(Long2ObjectOpenHashMap<T> cells, double degrees, double cx, double cz, Kind<T> kind,
                              LongSet unique) {
        FreeRotation<T> rotation = new FreeRotation<>(cells, degrees, cx, cz, kind, unique);
        if (!cells.isEmpty()) {
            rotation.run();
        }
        return new Result<>(rotation.out, rotation.sources);
    }

    /** Blocks of a world or a clipboard; their states turn only when {@code statesTurn} (a vertical axis). */
    static Kind<BlockData> blocks(BlockTransforms transforms, boolean statesTurn) {
        return new Kind<>() {
            @Override
            public int tier(BlockData value) {
                if (value.getMaterial().isAir()) {
                    return EMPTY;
                }
                if (!value.getMaterial().isSolid()) {
                    return DETAIL;
                }
                for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH,
                        BlockFace.EAST, BlockFace.WEST}) {
                    if (!value.isFaceSturdy(face, BlockSupport.FULL)) {
                        return PARTIAL;
                    }
                }
                return FULL;
            }

            @Override
            public BlockData turn(BlockData value, int quarters) {
                Transform turn = Transform.rotation(90 * quarters);
                return !statesTurn || turn == null ? value : transforms.apply(value, turn);
            }

            @Override
            public int support(BlockData value) {
                if (!statesTurn) {
                    // Turned around another axis: its faces no longer match the world's.
                    return -1;
                }
                if (value instanceof Lantern lantern) {
                    return lantern.isHanging() ? 2 : 3;
                }
                if (value instanceof FaceAttachable attachable && value instanceof Directional directional) {
                    return switch (attachable.getAttachedFace()) {
                        case FLOOR -> 3;
                        case CEILING -> 2;
                        case WALL -> face(directional.getFacing().getOppositeFace());
                    };
                }
                if (value instanceof Stairs stairs) {
                    return face(stairs.getFacing());
                }
                // Wall torches, ladders, wall signs, banners and heads hang on the block behind them.
                if (value instanceof Directional directional && !value.getMaterial().isSolid()) {
                    return face(directional.getFacing().getOppositeFace());
                }
                return -1;
            }

            private int face(BlockFace face) {
                for (int i = 0; i < FACES.length; i++) {
                    if (FACES[i][0] == face.getModX() && FACES[i][1] == face.getModY() && FACES[i][2] == face.getModZ()) {
                        return i;
                    }
                }
                return -1;
            }
        };
    }

    private void run() {
        for (T value : cells.values()) {
            if (kind.tier(value) == EMPTY) {
                air = value;
                break;
            }
        }
        full = new Pass(FULL, null);
        full.resample();
        partial = new Pass(PARTIAL, full);
        partial.resample();
        for (Pass pass : java.util.List.of(full, partial)) {
            for (Long2LongOpenHashMap.Entry e : pass.owner.long2LongEntrySet()) {
                long target = e.getLongKey();
                long source = e.getLongValue();
                if (pass == full && partial.owner.containsKey(target)) {
                    continue;
                }
                T value = tier(source) == pass.tier ? cells.get(source) : air;
                if (value == null) {
                    continue;
                }
                out.put(target, value == air ? value : orient(value, source, target));
                sources.put(target, source);
            }
        }
        placeDetails();
    }

    /**
     * One tier resampled and repaired, in the room the tier before it left. Its sources are its
     * own blocks, solid, and everything lighter, seen as air.
     */
    private final class Pass {

        private final int tier;
        private final Pass before;
        /** Per target, how many of its samples fall on each source. */
        private final Long2ObjectOpenHashMap<Long2IntOpenHashMap> weights = new Long2ObjectOpenHashMap<>();
        /** Per source, the targets where it was sampled. */
        private final Long2ObjectOpenHashMap<LongArrayList> seen = new Long2ObjectOpenHashMap<>();
        private final Long2LongOpenHashMap owner = new Long2LongOpenHashMap();
        private final Long2ObjectOpenHashMap<LongArrayList> targets = new Long2ObjectOpenHashMap<>();
        /** Targets kept open for a window or a door: never filled by a repair. */
        private final LongOpenHashSet openings = new LongOpenHashSet();

        Pass(int tier, Pass before) {
            this.tier = tier;
            this.before = before;
        }

        void resample() {
            sample();
            assign();
            keepUniqueOnce();
            represent();
            if (before == null) {
                keepOpenings();
            }
            connect();
            prune();
            if (before != null) {
                anchor();
            }
        }

        /**
         * A partial block that leaned on full blocks and now touches none of them moves next to
         * one of their images, as close as it can to its exact place: no sill or shutter floats.
         */
        private void anchor() {
            for (long source : sorted(targets.keySet().toLongArray())) {
                LongArrayList holders = new LongArrayList();
                for (int[] face : FACES) {
                    long next = Keys.offset(source, face[0], face[1], face[2]);
                    if (before.solid(next) && before.targets.containsKey(next)) {
                        holders.add(next);
                    }
                }
                LongArrayList mine = targets.get(source);
                if (holders.isEmpty() || mine.longStream().anyMatch(this::touchesBefore)) {
                    continue;
                }
                double[] exact = forward(Keys.x(source), Keys.z(source));
                long best = NONE;
                double bestDistance = Double.MAX_VALUE;
                for (long holder : holders) {
                    for (long image : before.targets.get(holder)) {
                        for (int[] face : FACES) {
                            long candidate = Keys.offset(image, face[0], face[1], face[2]);
                            if (Keys.y(candidate) != Keys.y(source) || solidAt(candidate) || blocked(candidate)
                                    || before.openings.contains(candidate)) {
                                continue;
                            }
                            double d = Math.hypot(Keys.x(candidate) - exact[0], Keys.z(candidate) - exact[1]);
                            if (d < bestDistance || (d == bestDistance && candidate < best)) {
                                best = candidate;
                                bestDistance = d;
                            }
                        }
                    }
                }
                if (best == NONE) {
                    continue;
                }
                long nearest = mine.getLong(0);
                for (long t : mine) {
                    if (Math.hypot(Keys.x(t) - exact[0], Keys.z(t) - exact[1])
                            < Math.hypot(Keys.x(nearest) - exact[0], Keys.z(nearest) - exact[1])) {
                        nearest = t;
                    }
                }
                drop(nearest);
                own(best, source);
            }
        }

        /** A source this pass sees: its own tier or lighter (air for the pass). */
        private boolean sees(long source) {
            if (!cells.containsKey(source)) {
                return false;
            }
            int t = tier(source);
            return t == EMPTY || t >= tier;
        }

        private boolean solid(long source) {
            return cells.containsKey(source) && tier(source) == tier;
        }

        /** Taken by the tier before: out of reach. */
        private boolean blocked(long target) {
            return before != null && before.solidAt(target);
        }

        boolean solidAt(long target) {
            return owner.containsKey(target) && solid(owner.get(target));
        }

        private void sample() {
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            double lowX = Double.MAX_VALUE, highX = -Double.MAX_VALUE, lowZ = Double.MAX_VALUE, highZ = -Double.MAX_VALUE;
            boolean any = false;
            for (long key : cells.keySet()) {
                if (!solid(key)) {
                    continue;
                }
                any = true;
                minY = Math.min(minY, Keys.y(key));
                maxY = Math.max(maxY, Keys.y(key));
                for (double dx = -0.5; dx <= 0.5; dx += 1) {
                    for (double dz = -0.5; dz <= 0.5; dz += 1) {
                        double[] p = forward(Keys.x(key) + dx, Keys.z(key) + dz);
                        lowX = Math.min(lowX, p[0]);
                        highX = Math.max(highX, p[0]);
                        lowZ = Math.min(lowZ, p[1]);
                        highZ = Math.max(highZ, p[1]);
                    }
                }
            }
            if (before == null) {
                // The full pass also lays the air of the whole clipboard: a pasted room is emptied.
                for (long key : cells.keySet()) {
                    any = true;
                    minY = Math.min(minY, Keys.y(key));
                    maxY = Math.max(maxY, Keys.y(key));
                    for (double dx = -0.5; dx <= 0.5; dx += 1) {
                        for (double dz = -0.5; dz <= 0.5; dz += 1) {
                            double[] p = forward(Keys.x(key) + dx, Keys.z(key) + dz);
                            lowX = Math.min(lowX, p[0]);
                            highX = Math.max(highX, p[0]);
                            lowZ = Math.min(lowZ, p[1]);
                            highZ = Math.max(highZ, p[1]);
                        }
                    }
                }
            }
            if (!any) {
                return;
            }
            for (int x = (int) Math.floor(lowX) - 1; x <= (int) Math.ceil(highX) + 1; x++) {
                for (int z = (int) Math.floor(lowZ) - 1; z <= (int) Math.ceil(highZ) + 1; z++) {
                    // The source columns under the samples of this target column, the same for every layer.
                    long[] columns = new long[TOTAL];
                    for (int i = 0; i < SAMPLES; i++) {
                        for (int j = 0; j < SAMPLES; j++) {
                            double[] s = inverse(x - 0.5 + (i + 0.5) / SAMPLES, z - 0.5 + (j + 0.5) / SAMPLES);
                            columns[i * SAMPLES + j] = Keys.pack((int) Math.round(s[0]), 0, (int) Math.round(s[1]));
                        }
                    }
                    for (int y = minY; y <= maxY; y++) {
                        long target = Keys.pack(x, y, z);
                        if (blocked(target)) {
                            continue;
                        }
                        Long2IntOpenHashMap hits = null;
                        for (long column : columns) {
                            long source = Keys.pack(Keys.x(column), y, Keys.z(column));
                            if (sees(source)) {
                                if (hits == null) {
                                    hits = new Long2IntOpenHashMap();
                                }
                                hits.addTo(source, 1);
                            }
                        }
                        if (hits != null) {
                            weights.put(target, hits);
                            for (long source : hits.keySet()) {
                                seen.computeIfAbsent(source, k -> new LongArrayList()).add(target);
                            }
                        }
                    }
                }
            }
        }

        private void assign() {
            for (long target : sorted(weights.keySet().toLongArray())) {
                long source = choose(weights.get(target), true, NONE);
                if (source != NONE && (before == null || solid(source))) {
                    own(target, source);
                }
            }
        }

        /**
         * The source a target shows: the block of this tier seen most if they fill half of it,
         * else the lighter one seen most if the clipboard fills half of it, else none.
         */
        private long choose(Long2IntOpenHashMap hits, boolean allowUnique, long excluded) {
            int solidSum = 0;
            int anySum = 0;
            long bestSolid = NONE;
            long bestOther = NONE;
            for (long source : sorted(hits.keySet().toLongArray())) {
                if (source == excluded || (!allowUnique && unique.contains(source))) {
                    continue;
                }
                int w = hits.get(source);
                anySum += w;
                if (solid(source)) {
                    solidSum += w;
                    if (bestSolid == NONE || w > hits.get(bestSolid)) {
                        bestSolid = source;
                    }
                } else if (bestOther == NONE || w > hits.get(bestOther)) {
                    bestOther = source;
                }
            }
            if (solidSum * 2 >= TOTAL) {
                return bestSolid;
            }
            return anySum * 2 >= TOTAL ? bestOther : NONE;
        }

        private void keepUniqueOnce() {
            for (long source : sorted(unique.toLongArray())) {
                LongArrayList mine = targets.get(source);
                if (mine == null || mine.size() < 2) {
                    continue;
                }
                long keep = mine.getLong(0);
                for (long target : mine) {
                    if (weight(source, target) > weight(source, keep)) {
                        keep = target;
                    }
                }
                for (long target : mine.toLongArray()) {
                    if (target != keep) {
                        long other = choose(weights.get(target), false, source);
                        if (other == NONE || (before != null && !solid(other))) {
                            drop(target);
                        } else {
                            own(target, other);
                        }
                    }
                }
            }
        }

        /** Every source block of the tier shows, unless the only room left is taken by blocks that show nowhere else. */
        private void represent() {
            for (long source : sorted(cells.keySet().toLongArray())) {
                if (!solid(source) || targets.containsKey(source) || !seen.containsKey(source)) {
                    continue;
                }
                long best = NONE;
                int bestRank = -1;
                for (long target : seen.get(source)) {
                    int rank;
                    if (!solidAt(target)) {
                        rank = 2;
                    } else if (targets.get(owner.get(target)).size() > 1 && !unique.contains(owner.get(target))) {
                        rank = 1;
                    } else {
                        rank = unique.contains(source) ? 0 : -1;
                    }
                    if (rank > bestRank || (rank == bestRank && rank >= 0
                            && weight(source, target) > weight(source, best))) {
                        best = target;
                        bestRank = Math.max(rank, bestRank);
                    }
                }
                if (bestRank >= 0) {
                    own(best, source);
                }
            }
        }

        /**
         * An opening of the wall (window, door, arrow slit: free, with full blocks on two opposite
         * sides) keeps at least one free target, taken from a block that shows twice.
         */
        private void keepOpenings() {
            for (long source : sorted(cells.keySet().toLongArray())) {
                if (solid(source) || !seen.containsKey(source) || !opening(source)) {
                    continue;
                }
                long free = NONE;
                long best = NONE;
                for (long target : seen.get(source)) {
                    if (!solidAt(target)) {
                        if (free == NONE || weight(source, target) > weight(source, free)) {
                            free = target;
                        }
                    } else if (targets.get(owner.get(target)).size() > 1 && !unique.contains(owner.get(target))
                            && (best == NONE || weight(source, target) > weight(source, best))) {
                        best = target;
                    }
                }
                long kept = free != NONE ? free : best;
                if (kept != NONE) {
                    if (free == NONE) {
                        own(kept, source);
                    }
                    openings.add(kept);
                }
            }
        }

        private boolean opening(long source) {
            return (solid(Keys.offset(source, 1, 0, 0)) && solid(Keys.offset(source, -1, 0, 0)))
                    || (solid(Keys.offset(source, 0, 0, 1)) && solid(Keys.offset(source, 0, 0, -1)));
        }

        private void connect() {
            for (long source : sorted(cells.keySet().toLongArray())) {
                if (!solid(source)) {
                    continue;
                }
                for (int[] face : FACES) {
                    long next = Keys.offset(source, face[0], face[1], face[2]);
                    boolean forward = face[0] + face[1] + face[2] > 0;
                    if (forward && solid(next)) {
                        link(source, targets.get(source), next, targets.get(next), true);
                    } else if (before != null && before.solid(next)) {
                        // A partial block keeps touching the full block it touched.
                        link(source, targets.get(source), next, before.targets.get(next), false);
                    }
                }
            }
        }

        /**
         * Two blocks touching by a face in the source still touch, directly or through a solid
         * block; else the free block between them is filled, with {@code a}, or {@code b} if
         * {@code eitherFills}.
         */
        private void link(long a, LongArrayList ta, long b, LongArrayList tb, boolean eitherFills) {
            if (ta == null || tb == null) {
                return;
            }
            for (long x : ta) {
                for (long y : tb) {
                    if (adjacent(x, y)) {
                        return;
                    }
                }
            }
            long best = NONE;
            int bestWeight = -1;
            for (long x : ta) {
                for (int[] face : FACES) {
                    long candidate = Keys.offset(x, face[0], face[1], face[2]);
                    if (ta.contains(candidate) || tb.contains(candidate)) {
                        continue;
                    }
                    boolean touchesB = false;
                    for (long y : tb) {
                        touchesB |= adjacent(candidate, y);
                    }
                    if (!touchesB) {
                        continue;
                    }
                    if (solidAt(candidate) || blocked(candidate)) {
                        return;
                    }
                    if (openings.contains(candidate) || (before != null && before.openings.contains(candidate))) {
                        continue;
                    }
                    int w = weight(a, candidate) + weight(b, candidate);
                    if (w > bestWeight || (w == bestWeight && candidate < best)) {
                        best = candidate;
                        bestWeight = w;
                    }
                }
            }
            if (bestWeight < 0) {
                return;
            }
            long chosen;
            if (!eitherFills || Keys.y(best) != Keys.y(b)) {
                chosen = a;
            } else if (Keys.y(best) != Keys.y(a)) {
                chosen = b;
            } else {
                chosen = weight(a, best) >= weight(b, best) ? a : b;
            }
            if (unique.contains(chosen)) {
                chosen = chosen == a ? b : a;
                if (!eitherFills || unique.contains(chosen) || Keys.y(best) != Keys.y(chosen)) {
                    return;
                }
            }
            if (Keys.y(best) != Keys.y(chosen)) {
                return;
            }
            own(best, chosen);
        }

        /** A block left alone while its source touched others is dropped, if its source shows elsewhere. */
        private void prune() {
            for (long target : sorted(owner.keySet().toLongArray())) {
                long source = owner.get(target);
                if (!solid(source) || targets.get(source).size() < 2 || !heavyNear(source, false)
                        || heavyNear(target, true)) {
                    continue;
                }
                long free = NONE;
                Long2IntOpenHashMap hits = weights.get(target);
                if (hits != null && before == null) {
                    for (long other : sorted(hits.keySet().toLongArray())) {
                        if (!solid(other) && (free == NONE || hits.get(other) > hits.get(free))) {
                            free = other;
                        }
                    }
                }
                if (free == NONE) {
                    drop(target);
                } else {
                    own(target, free);
                }
            }
        }

        private boolean touchesBefore(long target) {
            for (int[] face : FACES) {
                if (blocked(Keys.offset(target, face[0], face[1], face[2]))) {
                    return true;
                }
            }
            return false;
        }

        /** A face neighbour of this tier or the one before: in the source, or among the targets. */
        private boolean heavyNear(long key, boolean turned) {
            for (int[] face : FACES) {
                long next = Keys.offset(key, face[0], face[1], face[2]);
                boolean heavy = turned ? solidAt(next) || blocked(next)
                        : solid(next) || (before != null && before.solid(next));
                if (heavy) {
                    return true;
                }
            }
            return false;
        }

        private void own(long target, long source) {
            drop(target);
            owner.put(target, source);
            targets.computeIfAbsent(source, k -> new LongArrayList()).add(target);
        }

        void drop(long target) {
            if (!owner.containsKey(target)) {
                return;
            }
            long previous = owner.remove(target);
            LongArrayList list = targets.get(previous);
            list.rem(target);
            if (list.isEmpty()) {
                targets.remove(previous);
            }
        }

        private int weight(long source, long target) {
            Long2IntOpenHashMap hits = weights.get(target);
            return hits == null ? 0 : hits.get(source);
        }
    }

    // --------------------------------------------------------------- orientation

    /**
     * A block facing a way (log, stair, trapdoor) takes the quarter turn where its neighbours
     * look most like before.
     */
    private T orient(T value, long source, long target) {
        T turned = kind.turn(value, quarters);
        T other = kind.turn(value, otherQuarters);
        if (turned.equals(other) || unique.contains(source)) {
            return turned;
        }
        return cost(source, target, otherQuarters) < cost(source, target, quarters) ? other : turned;
    }

    /**
     * How badly a block placed at {@code target} with {@code q} quarter turns fits: each face
     * whose neighbour is a block now and was not (or the reverse), plus the angle it is off by.
     */
    private double cost(long source, long target, int q) {
        int mismatch = 0;
        for (int[] e : FACES) {
            long next = Keys.offset(source, e[0], e[1], e[2]);
            boolean was = heavy(next) || detailOf.get(next) != NONE;
            int[] d = quarter(e[0], e[2], q);
            long after = Keys.offset(target, d[0], e[1], d[1]);
            boolean is = heavyAt(after) || detailAt.get(after) != NONE;
            if (was != is && (heavy(next) || detailOf.get(next) != NONE || !cells.containsKey(next)
                    || tier(next) == EMPTY)) {
                mismatch++;
            }
        }
        double off = q == quarters ? rest : 1 - rest;
        // A stair keeps its back, a torch its pillar: losing what it leans on costs most.
        int support = kind.support(cells.get(source));
        long holder = support < 0 ? NONE : Keys.offset(source, FACES[support][0], FACES[support][1], FACES[support][2]);
        if (holder != NONE && heavy(holder)) {
            int[] turned = FACES[kind.support(kind.turn(cells.get(source), q))];
            long after = Keys.offset(target, turned[0], turned[1], turned[2]);
            if (tier(holder) == FULL ? !full.solidAt(after) : !heavyAt(after)) {
                mismatch += 3;
            }
        }
        return 0.4 * mismatch + 0.5 * off;
    }

    private boolean heavy(long source) {
        int t = cells.containsKey(source) ? tier(source) : EMPTY;
        return t == FULL || t == PARTIAL;
    }

    private boolean heavyAt(long target) {
        return full.solidAt(target) || partial.solidAt(target);
    }

    // ------------------------------------------------------------------ details

    /**
     * Details, nearest to the blocks first, each placed once against the image of what holds it:
     * the image and quarter turn that put it closest to its exact place and among neighbours
     * most like before.
     */
    private void placeDetails() {
        LongArrayList order = new LongArrayList();
        LongOpenHashSet queued = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        for (long key : sorted(cells.keySet().toLongArray())) {
            if (tier(key) == DETAIL && heavyNeighbour(key)) {
                queue.enqueue(key);
                queued.add(key);
            }
        }
        while (!queue.isEmpty()) {
            long key = queue.dequeueLong();
            order.add(key);
            for (int[] face : FACES) {
                long next = Keys.offset(key, face[0], face[1], face[2]);
                if (cells.containsKey(next) && tier(next) == DETAIL && queued.add(next)) {
                    queue.enqueue(next);
                }
            }
        }
        for (long key : sorted(cells.keySet().toLongArray())) {
            if (tier(key) == DETAIL && queued.add(key)) {
                order.add(key);
            }
        }
        for (long source : order) {
            placeDetail(source);
        }
    }

    private boolean heavyNeighbour(long key) {
        for (int[] face : FACES) {
            if (heavy(Keys.offset(key, face[0], face[1], face[2]))) {
                return true;
            }
        }
        return false;
    }

    private void placeDetail(long source) {
        T value = cells.get(source);
        int support = supportOf(source, value);
        double[] exact = forward(Keys.x(source), Keys.z(source));
        long best = NONE;
        int bestQ = quarters;
        double bestCost = Double.MAX_VALUE;
        if (support >= 0) {
            int[] o = FACES[support];
            long holder = Keys.offset(source, o[0], o[1], o[2]);
            for (long image : images(holder)) {
                for (int q : unique.contains(source) ? new int[]{quarters} : new int[]{quarters, otherQuarters}) {
                    // The detail sits on the side of the holder it sat on, turned with it.
                    int[] d = quarter(-o[0], -o[2], q);
                    long target = Keys.offset(image, d[0], -o[1], d[1]);
                    if (!free(target)) {
                        continue;
                    }
                    double c = Math.hypot(Keys.x(target) - exact[0], Keys.z(target) - exact[1])
                            + cost(source, target, q);
                    if (c < bestCost || (c == bestCost && target < best)) {
                        best = target;
                        bestQ = q;
                        bestCost = c;
                    }
                }
            }
        }
        if (best == NONE) {
            long target = Keys.pack((int) Math.round(exact[0]), Keys.y(source), (int) Math.round(exact[1]));
            if (support >= 0 || !free(target)) {
                return;
            }
            best = target;
            bestQ = !unique.contains(source) && cost(source, target, otherQuarters) < cost(source, target, quarters)
                    ? otherQuarters : quarters;
        }
        detailAt.put(best, source);
        detailOf.put(source, best);
        out.put(best, kind.turn(value, bestQ));
        sources.put(best, source);
    }

    /** The face of what holds this detail: its own if it says so, else below, above, then the sides. */
    private int supportOf(long source, T value) {
        int hint = kind.support(value);
        if (hint >= 0 && holds(Keys.offset(source, FACES[hint][0], FACES[hint][1], FACES[hint][2]))) {
            return hint;
        }
        int[] order = {3, 2, 0, 1, 4, 5};
        for (int i : order) {
            if (heavy(Keys.offset(source, FACES[i][0], FACES[i][1], FACES[i][2]))) {
                return i;
            }
        }
        for (int i : order) {
            if (detailOf.get(Keys.offset(source, FACES[i][0], FACES[i][1], FACES[i][2])) != NONE) {
                return i;
            }
        }
        return -1;
    }

    private boolean holds(long source) {
        return heavy(source) || detailOf.get(source) != NONE;
    }

    private long[] images(long holder) {
        if (heavy(holder)) {
            Pass pass = tier(holder) == FULL ? full : partial;
            LongArrayList list = pass.targets.get(holder);
            return list == null ? new long[0] : sorted(list.toLongArray());
        }
        long placed = detailOf.get(holder);
        return placed == NONE ? new long[0] : new long[]{placed};
    }

    private boolean free(long target) {
        return !heavyAt(target) && detailAt.get(target) == NONE;
    }

    // ------------------------------------------------------------------ helpers

    private int tier(long source) {
        return kind.tier(cells.get(source));
    }

    private double[] forward(double x, double z) {
        double ux = x - cx;
        double uz = z - cz;
        return new double[]{ux * cos - uz * sin + cx, ux * sin + uz * cos + cz};
    }

    private double[] inverse(double x, double z) {
        double ux = x - cx;
        double uz = z - cz;
        return new double[]{ux * cos + uz * sin + cx, -ux * sin + uz * cos + cz};
    }

    /** (x, z) turned by q quarter turns, clockwise seen from above. */
    private static int[] quarter(int x, int z, int q) {
        int[] v = {x, z};
        for (int i = 0; i < Math.floorMod(q, 4); i++) {
            v = new int[]{-v[1], v[0]};
        }
        return v;
    }

    private static boolean adjacent(long a, long b) {
        return Math.abs(Keys.x(a) - Keys.x(b)) + Math.abs(Keys.y(a) - Keys.y(b)) + Math.abs(Keys.z(a) - Keys.z(b)) == 1;
    }

    /** Keys in a fixed order: the result does not depend on hash order. */
    private static long[] sorted(long[] keys) {
        Arrays.sort(keys);
        return keys;
    }
}
