package com.stackmc.trowel.axiom;

import com.stackmc.trowel.Chat;
import com.stackmc.trowel.Trowel;
import com.stackmc.trowel.api.Box;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.logging.Level;

/**
 * Compatibility with Axiom (the client mod and its server plugin AxiomPaper).
 *
 * <p>Axiom is only active where Trowel is: a player gets the Axiom permission while the host
 * lets them build (for instance on their own plot), and loses it as soon as they leave.
 * On top of that, Trowel hooks into Axiom's protection hook, the one PlotSquared and WorldGuard
 * use: Axiom only places and breaks inside the buildable area, block by block and chunk by
 * chunk. Its world, game mode, teleport, time and entity actions are refused elsewhere.</p>
 *
 * <p>AxiomPaper is not a compile dependency: everything goes through reflection, and without it
 * this bridge does nothing.</p>
 */
public final class AxiomBridge implements Listener {

    public static final String PLUGIN_NAME = "AxiomPaper";
    private static final String BASE = "com.moulberry.axiom.";
    private static final int REFRESH_TICKS = 10;

    private final Trowel trowel;
    /** Where each player may build with Axiom, read off the main thread by AxiomPaper. */
    private final Map<UUID, Access> access = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionAttachment> grants = new HashMap<>();
    private final Map<String, Method> getters = new ConcurrentHashMap<>();
    private BukkitTask task;
    private Plugin axiom;
    private String failure;

    private record Access(UUID world, Box bounds) {

        boolean allows(World other, int x, int y, int z) {
            return other != null && world.equals(other.getUID()) && (bounds == null || bounds.contains(x, y, z));
        }

        /** A teleport is judged on the column: the height of the area does not count. */
        boolean allowsColumn(World other, int x, int z) {
            return other != null && world.equals(other.getUID())
                    && (bounds == null || bounds.contains(x, bounds.minY(), z));
        }
    }

    public AxiomBridge(Trowel trowel) {
        this.trowel = trowel;
    }

    public void enable() {
        if (!trowel.settings().axiomEnabled()) {
            return;
        }
        trowel.plugin().getServer().getPluginManager().registerEvents(this, trowel.plugin());
        Plugin found = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (found != null && found.isEnabled()) {
            hook(found);
        }
    }

    public void disable() {
        if (task != null) {
            task.cancel();
        }
        grants.values().forEach(this::detach);
        grants.clear();
        access.clear();
        HandlerList.unregisterAll(this);
    }

    /** AxiomPaper may enable after us. */
    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (axiom == null && PLUGIN_NAME.equals(event.getPlugin().getName())) {
            hook(event.getPlugin());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        access.remove(event.getPlayer().getUniqueId());
        grants.remove(event.getPlayer().getUniqueId());
    }

    private void hook(Plugin found) {
        ClassLoader loader = found.getClass().getClassLoader();
        try {
            registerIntegration(loader);
            listen(loader, "AxiomModifyWorldEvent", (event, player) -> {
                World world = (World) call(event, "getWorld");
                return allowed(player) && player.getWorld().equals(world);
            }, true);
            listen(loader, "AxiomGameModeChangeEvent", (event, player) -> {
                GameMode mode = (GameMode) call(event, "getGameMode");
                return allowed(player) && (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR);
            }, false);
            listen(loader, "AxiomTeleportEvent", (event, player) -> {
                Location target = (Location) call(event, "getLocation");
                Access where = access.get(player.getUniqueId());
                return where != null && target != null
                        && where.allowsColumn(target.getWorld(), target.getBlockX(), target.getBlockZ());
            }, false);
            listen(loader, "AxiomUnknownTeleportEvent", (event, player) -> false, false);
            for (String name : List.of("AxiomFlySpeedChangeEvent", "AxiomTimeChangeEvent", "AxiomSpawnEntityEvent",
                    "AxiomManipulateEntityEvent", "AxiomRemoveEntityEvent")) {
                listen(loader, name, (event, player) -> allowed(player), false);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failure = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            trowel.plugin().getLogger().log(Level.WARNING, "AxiomPaper found but incompatible, "
                    + "Axiom stays under its own rules.", e);
            return;
        }
        axiom = found;
        task = Bukkit.getScheduler().runTaskTimer(trowel.plugin(), this::refresh, 1L, REFRESH_TICKS);
        trowel.plugin().getLogger().info("Axiom active, only where building is allowed (permission "
                + trowel.settings().axiomPermission() + ").");
    }

    // ------------------------------------------------------------------ access

    private boolean allowed(Player player) {
        return access.containsKey(player.getUniqueId());
    }

    /**
     * Updates who may use Axiom, and where.
     *
     * <p>On the main thread: the host can only be asked there. AxiomPaper then reads the result
     * from its own threads.</p>
     */
    private void refresh() {
        String node = trowel.settings().axiomPermission();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            boolean allowed = trowel.host().denyEdit(player) == null;
            if (allowed) {
                access.put(id, new Access(player.getWorld().getUID(), trowel.host().bounds(player.getWorld())));
            } else {
                access.remove(id);
            }
            PermissionAttachment attachment = grants.get(id);
            if (allowed && attachment == null) {
                attachment = player.addAttachment(trowel.plugin());
                attachment.setPermission(node, true);
                grants.put(id, attachment);
                changed(player, true);
            } else if (!allowed && attachment != null) {
                detach(attachment);
                grants.remove(id);
                changed(player, false);
            }
        }
    }

    private void detach(PermissionAttachment attachment) {
        try {
            attachment.remove();
        } catch (IllegalArgumentException ignored) {
            // Already removed with the player.
        }
    }

    /** Axiom rereads permissions every tick; we still force its update, and tell the player. */
    private void changed(Player player, boolean active) {
        Object instance = axiomInstance();
        if (instance != null) {
            try {
                instance.getClass().getMethod("clearCachedPermissionsFor", UUID.class).invoke(instance, player.getUniqueId());
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Optional: AxiomPaper rereads the permissions on the next tick anyway.
            }
        }
        if (usesAxiom(player)) {
            Chat.bar(player, active ? "Axiom active here." : "Axiom paused: you cannot build here.",
                    active ? NamedTextColor.AQUA : NamedTextColor.GRAY);
        }
    }

    // ------------------------------------------------------------------- state

    public boolean present() {
        return Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) != null;
    }

    public boolean hooked() {
        return axiom != null;
    }

    public String failure() {
        return failure;
    }

    public boolean activeFor(Player player) {
        return allowed(player);
    }

    /** Does this player's client have Axiom? */
    public boolean usesAxiom(Player player) {
        Object instance = axiomInstance();
        if (instance == null) {
            return false;
        }
        try {
            return (boolean) instance.getClass().getMethod("canUseAxiom", Player.class).invoke(instance, player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private Object axiomInstance() {
        if (axiom == null) {
            return null;
        }
        try {
            return Class.forName(BASE + "AxiomPaper", true, axiom.getClass().getClassLoader()).getField("PLUGIN").get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return axiom;
        }
    }

    // ------------------------------------------------------------------ events

    /**
     * Listens to an AxiomPaper event by name.
     *
     * @param decide  {@code true} if the action is allowed
     * @param replace {@code true}: the decision replaces the cancelled state (Axiom cancels by
     *                default a change in another world); otherwise it only cancels
     */
    private void listen(ClassLoader loader, String name, BiPredicate<Event, Player> decide, boolean replace)
            throws ClassNotFoundException {
        Class<? extends Event> type = Class.forName(BASE + "event." + name, true, loader).asSubclass(Event.class);
        Bukkit.getPluginManager().registerEvent(type, this, EventPriority.HIGHEST, (listener, event) -> {
            if (!type.isInstance(event) || !(event instanceof Cancellable cancellable)) {
                return;
            }
            Player player = playerOf(event);
            if (player == null) {
                return;
            }
            boolean ok = decide.test(event, player);
            if (replace) {
                cancellable.setCancelled(!ok);
            } else if (!ok) {
                cancellable.setCancelled(true);
            }
        }, trowel.plugin(), false);
    }

    private Player playerOf(Event event) {
        Object player = call(event, "getPlayer");
        return player instanceof Player found ? found : null;
    }

    private Object call(Event event, String name) {
        try {
            Method method = getters.computeIfAbsent(event.getClass().getName() + "#" + name,
                    key -> find(event.getClass(), name));
            return method == null ? null : method.invoke(event);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Method find(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    // -------------------------------------------------------------- protection

    /**
     * Hooks into AxiomPaper's {@code Integration.registerCustomIntegration}: the same door as
     * PlotSquared and WorldGuard, through a proxy since the interface is not compiled here.
     */
    private void registerIntegration(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> integration = Class.forName(BASE + "integration.Integration", true, loader);
        Class<?> custom = Class.forName(BASE + "integration.Integration$CustomIntegration", true, loader);
        Class<?> checker = Class.forName(BASE + "integration.SectionPermissionChecker", true, loader);
        Class<?> boxType = Class.forName(BASE + "integration.Box", true, loader);
        Object all = checker.getField("ALL_ALLOWED").get(null);
        Object none = checker.getField("NONE_ALLOWED").get(null);
        Method fromBoxes = checker.getMethod("fromAllowedBoxes", List.class);
        Constructor<?> box = boxType.getConstructor(int.class, int.class, int.class, int.class, int.class, int.class);

        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{custom}, (self, method, args) -> switch (method.getName()) {
            case "canBreakBlock" -> {
                Block block = (Block) args[1];
                yield canEdit((Player) args[0], block.getWorld(), block.getX(), block.getY(), block.getZ());
            }
            case "canPlaceBlock" -> {
                Location location = (Location) args[1];
                yield canEdit((Player) args[0], location.getWorld(), location.getBlockX(), location.getBlockY(),
                        location.getBlockZ());
            }
            case "checkSection" -> section((Player) args[0], (World) args[1], (int) args[2], (int) args[3],
                    (int) args[4], all, none, fromBoxes, box);
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == args[0];
            case "toString" -> "Trowel";
            default -> null;
        });
        integration.getMethod("registerCustomIntegration", Plugin.class, custom).invoke(null, trowel.plugin(), proxy);
    }

    private boolean canEdit(Player player, World world, int x, int y, int z) {
        Access where = access.get(player.getUniqueId());
        return where != null && where.allows(world, x, y, z);
    }

    /** What Axiom may touch in a 16 x 16 x 16 chunk section, in coordinates local to the section. */
    private Object section(Player player, World world, int cx, int cy, int cz, Object all, Object none,
                           Method fromBoxes, Constructor<?> box) throws ReflectiveOperationException {
        Access where = access.get(player.getUniqueId());
        if (where == null || world == null || !where.world().equals(world.getUID())) {
            return none;
        }
        Box section = new Box(cx << 4, cy << 4, cz << 4, (cx << 4) + 15, (cy << 4) + 15, (cz << 4) + 15);
        if (where.bounds() == null) {
            return all;
        }
        Box inside = section.intersect(where.bounds());
        if (inside == null) {
            return none;
        }
        if (inside.equals(section)) {
            return all;
        }
        Object local = box.newInstance(inside.minX() - section.minX(), inside.minY() - section.minY(),
                inside.minZ() - section.minZ(), inside.maxX() - section.minX(), inside.maxY() - section.minY(),
                inside.maxZ() - section.minZ());
        return fromBoxes.invoke(null, List.of(local));
    }
}
