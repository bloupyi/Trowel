package com.stackmc.trowel;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.api.TrowelApi;
import com.stackmc.trowel.api.TrowelHost;
import com.stackmc.trowel.axiom.AxiomBridge;
import com.stackmc.trowel.brush.BrushSettings;
import com.stackmc.trowel.brush.BrushType;
import com.stackmc.trowel.brush.Brushes;
import com.stackmc.trowel.engine.BlockTransforms;
import com.stackmc.trowel.engine.Clipboard;
import com.stackmc.trowel.engine.Engine;
import com.stackmc.trowel.pattern.Mask;
import com.stackmc.trowel.pattern.Masks;
import com.stackmc.trowel.pattern.Pattern;
import com.stackmc.trowel.pattern.Patterns;
import com.stackmc.trowel.ui.Commands;
import com.stackmc.trowel.ui.Dialogs;
import com.stackmc.trowel.ui.Hud;
import com.stackmc.trowel.ui.NoClip;
import com.stackmc.trowel.ui.PaletteEditor;
import com.stackmc.trowel.ui.ToolListener;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.keys.tags.DialogTagKeys;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trowel: building tools in the spirit of FastAsyncWorldEdit, goPaint, goBrush, VoxelSniper and
 * Arceon.
 *
 * <p>Other plugins decide who builds where through a {@link TrowelHost}; everything else
 * (commands, items, listeners, display, history) belongs to Trowel.</p>
 */
@Getter
@Accessors(fluent = true)
public final class Trowel implements TrowelApi {

    private final JavaPlugin plugin;
    private final HostRegistry hosts = new HostRegistry();
    private final Map<UUID, Session> sessions = new HashMap<>();

    private TrowelSettings settings;
    private BlockTransforms transforms;
    private BlockData air;
    private Engine engine;
    private TrowelItems items;
    private Hud hud;
    private NoClip noclip;
    private Dialogs dialogs;
    private Commands commands;
    private AxiomBridge axiom;
    private PaletteStore palettes;
    private PaletteStore patterns;
    private PaletteStore masks;
    private SchematicLibrary library;
    private PaletteEditor paletteEditor;
    private List<Listener> listeners = List.of();

    public Trowel(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Every registered host seen as one. */
    public TrowelHost host() {
        return hosts;
    }

    /** A pattern with the player's {@code @patterns} and {@code ##palettes} expanded. */
    public String expandPattern(UUID player, String raw) {
        return palettes.expand(player, patterns.expand(player, raw));
    }

    /** A mask with the player's {@code @masks} and {@code ##palettes} expanded. */
    public String expandMask(UUID player, String raw) {
        return palettes.expand(player, masks.expand(player, raw));
    }

    public void enable() {
        settings = TrowelSettings.load(plugin);
        transforms = new BlockTransforms();
        air = Material.AIR.createBlockData();
        engine = new Engine(this);
        items = new TrowelItems(this);
        hud = new Hud(this);
        dialogs = new Dialogs(this);
        palettes = new PaletteStore(plugin);
        patterns = PaletteStore.patterns(plugin);
        masks = PaletteStore.masks(plugin);
        library = new SchematicLibrary(plugin);
        paletteEditor = new PaletteEditor(this);
        commands = new Commands(this);

        listeners = List.of(new ToolListener(this), dialogs, paletteEditor);
        listeners.forEach(listener -> plugin.getServer().getPluginManager().registerEvents(listener, plugin));
        commands.register();
        hud.start();
        noclip = new NoClip(this);
        noclip.start();
        checkQuickActions();
        axiom = new AxiomBridge(this);
        axiom.enable();
    }

    /** The G key only works if the bootstrapper managed to register the dialog: say so at startup. */
    private void checkQuickActions() {
        try {
            Registry<Dialog> dialogs = RegistryAccess.registryAccess().getRegistry(RegistryKey.DIALOG);
            TypedKey<Dialog> key = TypedKey.create(RegistryKey.DIALOG, TrowelBootstrap.QUICK_DIALOG);
            boolean registered = dialogs.get(key) != null;
            boolean tagged = dialogs.hasTag(DialogTagKeys.QUICK_ACTIONS)
                    && dialogs.getTag(DialogTagKeys.QUICK_ACTIONS).contains(key);
            if (registered && tagged) {
                plugin.getLogger().info("Quick actions ready on the G key.");
            } else {
                plugin.getLogger().warning("Quick actions unavailable (dialog "
                        + (registered ? "registered" : "missing") + ", tag " + (tagged ? "ok" : "missing") + ").");
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not check the quick actions: " + e.getMessage());
        }
    }

    public void disable() {
        if (hud != null) {
            hud.stop();
        }
        if (noclip != null) {
            noclip.stop();
        }
        if (axiom != null) {
            axiom.disable();
        }
        listeners.forEach(HandlerList::unregisterAll);
        sessions.clear();
    }

    @Override
    public void reload() {
        settings = TrowelSettings.load(plugin);
    }

    public Session session(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), id -> new Session());
    }

    /** The session if it already exists: the display does not create one for every online player. */
    public Session existingSession(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public void forget(Player player) {
        if (noclip != null) {
            noclip.forget(player);
        }
        sessions.remove(player.getUniqueId());
        if (hud != null) {
            hud.forget(player);
        }
    }

    /** The player's selection in the world they stand in, cropped to the buildable part. */
    public Box selection(Player player) {
        return selection(player, player.getWorld());
    }

    @Override
    public Box selection(Player player, World world) {
        Box box = session(player).selection(world);
        if (box == null) {
            return null;
        }
        Box bounds = hosts.bounds(player, world);
        return bounds == null ? box : box.intersect(bounds);
    }

    @Override
    public void registerHost(Plugin owner, TrowelHost host) {
        hosts.register(owner, host);
    }

    @Override
    public void unregisterHost(Plugin owner) {
        hosts.unregister(owner);
    }

    @Override
    public ItemStack wand() {
        return items.wand();
    }

    @Override
    public boolean isTool(ItemStack stack) {
        return items.kind(stack) != null;
    }

    @Override
    public void clearSelection(Player player) {
        session(player).clearSelection();
    }

    @Override
    public void openMenu(Player player) {
        dialogs.openHub(player);
    }

    @Override
    public void openBrushes(Player player) {
        dialogs.openKit(player, false);
    }

    /** One brush stroke on this block. */
    public void paint(Player player, BrushSettings held, Block target, BlockFace face, int slot) {
        BrushSettings settings = held.effective();
        BrushType type = settings.type();
        Pattern pattern = (x, y, z) -> air;
        Mask mask = null;
        try {
            boolean hasPattern = settings.pattern() != null && !settings.pattern().isBlank();
            if (type.uses(BrushType.Setting.PATTERN) && hasPattern) {
                pattern = Patterns.parse(Patterns.expand(expandPattern(player.getUniqueId(), settings.pattern()), player),
                        new Patterns.Context(hosts.markers(), session(player).getClipboard(), target.getX(), target.getY(),
                                target.getZ()));
            } else if (type.uses(BrushType.Setting.PATTERN) && type != BrushType.RAISE) {
                Chat.error(player, "This brush has no pattern. Left click to set it.");
                return;
            }
            if (settings.mask() != null && !settings.mask().isBlank()) {
                mask = Masks.parse(expandMask(player.getUniqueId(), settings.mask()), hosts.markers());
            }
        } catch (IllegalArgumentException e) {
            Chat.error(player, e.getMessage());
            return;
        }
        Clipboard clipboard = session(player).getClipboard();
        if (type == BrushType.STAMP && clipboard == null) {
            Chat.error(player, "Empty clipboard: //copy first.");
            return;
        }

        int x = target.getX();
        int y = target.getY();
        int z = target.getZ();
        Box bounds = hosts.bounds(player, target.getWorld());
        if (bounds != null && !bounds.grow(settings.size()).contains(x, y, z)) {
            Chat.bar(player, "Outside the buildable area.", NamedTextColor.RED);
            return;
        }
        Pattern chosen = pattern;
        Mask limit = mask;
        engine.submit(player, Engine.Job.of(type.getDisplayName(), target.getWorld(),
                        Brushes.reads(settings, x, y, z, face, clipboard),
                        context -> Brushes.compute(context, settings, chosen, limit, x, y, z, face, clipboard))
                .stroke("brush:" + slot + ":" + type.id()));
    }
}
