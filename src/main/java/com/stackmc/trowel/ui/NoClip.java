package com.stackmc.trowel.ui;

import com.stackmc.trowel.Session;
import com.stackmc.trowel.Trowel;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Passing through blocks in creative.
 *
 * <p>A builder who walks or flies into a block goes to spectator, which has no collision, and
 * comes back to creative, still flying, as soon as nothing blocks them. Only the players this
 * class switched are ever switched back.</p>
 */
public final class NoClip {

    /** How far ahead a pushed direction is probed. */
    private static final double PROBE = 0.15;
    /** The floor under the feet does not count as a wall. */
    private static final double FEET = 0.05;

    private final Trowel trowel;
    private final Set<UUID> ghosts = new HashSet<>();
    private BukkitTask task;

    public NoClip(Trowel trowel) {
        this.trowel = trowel;
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(trowel.plugin(), this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : Set.copyOf(ghosts)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                solid(player);
            }
        }
        ghosts.clear();
    }

    public void forget(Player player) {
        if (ghosts.contains(player.getUniqueId())) {
            solid(player);
        }
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Session session = trowel.existingSession(player);
            boolean wanted = session != null && session.isNoclip();
            boolean ghost = ghosts.contains(player.getUniqueId());
            if (ghost && (player.getGameMode() != GameMode.SPECTATOR || !wanted)) {
                if (player.getGameMode() == GameMode.SPECTATOR) {
                    solid(player);
                } else {
                    ghosts.remove(player.getUniqueId());
                }
                continue;
            }
            if (!wanted) {
                continue;
            }
            if (!ghost && player.getGameMode() == GameMode.CREATIVE && blocked(player)) {
                ghosts.add(player.getUniqueId());
                player.setGameMode(GameMode.SPECTATOR);
            } else if (ghost && !blocked(player)) {
                solid(player);
            }
        }
    }

    private void solid(Player player) {
        ghosts.remove(player.getUniqueId());
        player.setGameMode(GameMode.CREATIVE);
        player.setAllowFlight(true);
        player.setFlying(true);
    }

    /** Inside a block, or pushing into one: towards where the keys lead, down when sneaking, up when jumping. */
    private boolean blocked(Player player) {
        BoundingBox body = player.getBoundingBox();
        World world = player.getWorld();
        BoundingBox raised = body.clone().resize(body.getMinX(), body.getMinY() + FEET, body.getMinZ(),
                body.getMaxX(), body.getMaxY(), body.getMaxZ());
        if (overlaps(world, raised)) {
            return true;
        }
        Input input = player.getCurrentInput();
        Vector push = new Vector();
        double yaw = Math.toRadians(player.getLocation().getYaw());
        Vector forward = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        Vector left = new Vector(forward.getZ(), 0, -forward.getX());
        if (input.isForward()) {
            push.add(forward);
        }
        if (input.isBackward()) {
            push.subtract(forward);
        }
        if (input.isLeft()) {
            push.add(left);
        }
        if (input.isRight()) {
            push.subtract(left);
        }
        if (push.lengthSquared() > 1.0e-6 && overlaps(world, raised.clone().shift(push.normalize().multiply(PROBE)))) {
            return true;
        }
        boolean flying = player.isFlying() || player.getGameMode() == GameMode.SPECTATOR;
        if (flying && input.isSneak() && overlaps(world, body.clone().shift(0, -PROBE, 0))) {
            return true;
        }
        return flying && input.isJump() && overlaps(world, body.clone().shift(0, PROBE, 0));
    }

    private static boolean overlaps(World world, BoundingBox box) {
        for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX() - 1.0e-7); x++) {
            for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY() - 1.0e-7); y++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ() - 1.0e-7); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!block.getType().isAir()
                            && block.getCollisionShape().overlaps(box.clone().shift(-x, -y, -z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
