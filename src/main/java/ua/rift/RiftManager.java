package ua.rift;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class RiftManager {

    public enum Phase { IDLE, COUNTDOWN, ACTIVE, WEAK, ENDED, RUIN }

    private static final class Saved {
        final int x, y, z;
        final BlockData orig;
        Material placed;
        Saved(int x, int y, int z, BlockData orig, Material placed) {
            this.x = x; this.y = y; this.z = z; this.orig = orig; this.placed = placed;
        }
    }

    private static final class LootEntry {
        final Material mat; final int min, max, weight;
        LootEntry(Material m, int min, int max, int w) { this.mat = m; this.min = min; this.max = max; this.weight = w; }
    }

    private final RiftPlugin plugin;
    private final Random rnd = new Random();

    // состояние
    private Phase phase = Phase.IDLE;
    private boolean restoring = false;
    private World world;
    private int cx, cz, refY, floorY;
    private int t, startT;
    private int radius;
    private boolean coreBuilt;
    private boolean quick;
    private int fxCounter;
    private double swirl;
    private Location centerLoc;

    private final ArrayDeque<int[]> queue = new ArrayDeque<>();
    private final Map<Long, Saved> saved = new HashMap<>();
    private final Map<Long, int[]> loot = new HashMap<>();
    private final Set<Long> protectedKeys = new HashSet<>();
    private final Set<UUID> mobs = new HashSet<>();
    private final Map<UUID, Integer> stats = new HashMap<>();
    private final List<int[]> tickets = new ArrayList<>();
    private TextDisplay panel;
    private String lastPanelText = "";

    private BossBar bar;
    private BukkitTask tickTask, fxTask, buildTask;

    // конфиг (кэш)
    private int announce, coordsAt, growSec, weakAt, endAt, ruinDelay, ruinDur;
    private int startR, maxR, depth;
    private Material lootMat;
    private final List<LootEntry> table = new ArrayList<>();
    private int tableWeight;

    public RiftManager(RiftPlugin plugin) {
        this.plugin = plugin;
    }

    // ===================== публичный API =====================

    public boolean isRunning() { return phase != Phase.IDLE; }
    public boolean isRestoring() { return restoring; }
    public Phase getPhase() { return phase; }
    public Location getCenter() { return centerLoc == null ? null : centerLoc.clone(); }

    public String info() {
        if (!isRunning()) return "Разлом сейчас не активен.";
        return "Фаза: " + phase + ", t=" + t + "с, радиус=" + radius + ", лут-блоков=" + loot.size()
                + ", центр: " + cx + " " + refY + " " + cz + " (" + world.getName() + ")";
    }

    public boolean start(boolean quickStart) {
        if (isRunning() || restoring) return false;
        loadConfig();
        String wn = plugin.getConfig().getString("world", "");
        world = (wn == null || wn.isEmpty()) ? null : Bukkit.getWorld(wn);
        if (world == null) world = Bukkit.getWorlds().get(0);
        if (!pickLocation()) return false;

        this.quick = quickStart;
        startT = quickStart ? -5 : -announce;
        t = startT;
        radius = startR;
        coreBuilt = false;
        fxCounter = 0;
        queue.clear(); saved.clear(); loot.clear(); protectedKeys.clear(); mobs.clear(); stats.clear();
        phase = Phase.COUNTDOWN;

        int maxChunk = (maxR + 16) >> 4;
        for (int dx = -maxChunk; dx <= maxChunk; dx++) {
            for (int dz = -maxChunk; dz <= maxChunk; dz++) {
                int chx = (cx >> 4) + dx, chz = (cz >> 4) + dz;
                world.loadChunk(chx, chz, true);
                world.addPluginChunkTicket(chx, chz, plugin);
                tickets.add(new int[]{chx, chz});
            }
        }

        if (plugin.getConfig().getBoolean("bossbar.enabled", true)) {
            bar = Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SEGMENTED_10);
            for (Player p : Bukkit.getOnlinePlayers()) bar.addPlayer(p);
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 20L);
        fxTask = Bukkit.getScheduler().runTaskTimer(plugin, this::fx, 5L, 4L);
        buildTask = Bukkit.getScheduler().runTaskTimer(plugin, this::buildStep, 2L, 1L);
        return true;
    }

    /** Остановить немедленно. immediate=true — без отложенной очистки (при выключении сервера). */
    public void stop(boolean immediate) {
        if (!isRunning()) return;
        finish(immediate);
    }

    public void onJoin(Player p) {
        if (bar != null) bar.addPlayer(p);
    }

    public void onQuit(Player p) {
        if (bar != null) bar.removePlayer(p);
    }

    // ===================== конфиг =====================

    private void loadConfig() {
        FileConfiguration c = plugin.getConfig();
        announce = c.getInt("timeline.announce", 60);
        coordsAt = c.getInt("timeline.coords-at", 30);
        growSec = Math.max(1, c.getInt("timeline.grow-seconds", 360));
        weakAt = c.getInt("timeline.weak-at", 360);
        endAt = c.getInt("timeline.end-at", 480);
        ruinDelay = c.getInt("timeline.ruin-delay", 10);
        ruinDur = c.getInt("timeline.ruin-duration", 120);
        startR = c.getInt("shape.start-radius", 12);
        maxR = Math.max(startR, c.getInt("shape.max-radius", 25));
        depth = c.getInt("shape.depth", 9);
        lootMat = Material.matchMaterial(c.getString("loot-blocks.material", "SEA_LANTERN"));
        if (lootMat == null) lootMat = Material.matchMaterial("SEA_LANTERN");

        table.clear();
        tableWeight = 0;
        for (Map<?, ?> m : c.getMapList("loot.items")) {
            try {
                Material mat = Material.matchMaterial(String.valueOf(m.get("item")));
                if (mat == null) continue;
                int min = ((Number) m.get("min")).intValue();
                int max = ((Number) m.get("max")).intValue();
                int w = ((Number) m.get("weight")).intValue();
                if (w <= 0) continue;
                table.add(new LootEntry(mat, Math.max(1, min), Math.max(min, max), w));
                tableWeight += w;
            } catch (Throwable ex) {
                plugin.getLogger().warning("Неверная запись в loot.items: " + m);
            }
        }
        if (table.isEmpty()) {
            table.add(new LootEntry(Material.DIAMOND, 1, 2, 1));
            tableWeight = 1;
        }
    }

    // ===================== выбор места =====================

    private boolean pickLocation() {
        FileConfiguration c = plugin.getConfig();
        int min = c.getInt("location.min-distance", 400);
        int max = Math.max(min + 1, c.getInt("location.max-distance", 2500));
        Location spawn = world.getSpawnLocation();
        for (int i = 0; i < 60; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double dist = min + rnd.nextDouble() * (max - min);
            int x = (int) Math.round(spawn.getX() + Math.cos(ang) * dist);
            int z = (int) Math.round(spawn.getZ() + Math.sin(ang) * dist);
            Location probe = new Location(world, x, 64, z);
            if (!world.getWorldBorder().isInside(probe)) continue;
            Location edge1 = new Location(world, x + maxR + 8, 64, z + maxR + 8);
            Location edge2 = new Location(world, x - maxR - 8, 64, z - maxR - 8);
            if (!world.getWorldBorder().isInside(edge1) || !world.getWorldBorder().isInside(edge2)) continue;
            world.loadChunk(x >> 4, z >> 4, true);
            int y = world.getHighestBlockYAt(x, z);
            String n = world.getBlockAt(x, y, z).getType().name();
            if (n.contains("WATER") || n.contains("LAVA") || n.contains("ICE") || n.contains("KELP") || n.contains("SEAGRASS")) continue;
            if (y < world.getMinHeight() + depth + 10) continue;
            cx = x; cz = z; refY = y;
            floorY = refY - depth;
            centerLoc = new Location(world, cx + 0.5, refY, cz + 0.5);
            return true;
        }
        return false;
    }

    // ===================== главный тик (раз в секунду) =====================

    private void tick() {
        if (!isRunning()) return;

        if (phase == Phase.COUNTDOWN) {
            if (t == startT) {
                broadcast(m("pre-announce", "time", Util.time(-t)));
                Util.soundAll("entity.warden.heartbeat", 1f, 0.6f);
            }
            int coordsT = Math.max(startT + 1, -coordsAt);
            if (t == coordsT) {
                broadcast(m("coords", "x", cx, "y", refY, "z", cz, "world", world.getName()));
                Util.soundAll("block.beacon.ambient", 1f, 0.5f);
            }
            if (t < 0 && t % 15 == 0) Util.soundAll("entity.generic.explode", 0.25f, 0.5f);
            if (t >= -5 && t < 0) {
                for (Player p : Bukkit.getOnlinePlayers())
                    p.sendTitle("\u00a75\u00a7l" + (-t), "\u00a77Разлом открывается...", 0, 25, 5);
                Util.soundAll("block.note_block.bass", 1f, 0.5f);
            }
            updateBar();
            if (t >= 0) open();
            t++;
            return;
        }

        // ---- активные фазы ----
        if (phase == Phase.ACTIVE || phase == Phase.WEAK) {
            int target = startR + (int) Math.round((maxR - startR) * Math.min(1.0, (double) t / growSec));
            if (target > radius) {
                enqueue(radius, target);
                radius = target;
            }
            if (phase == Phase.ACTIVE && t >= weakAt) {
                phase = Phase.WEAK;
                broadcast(m("weak"));
                Util.soundAll("entity.warden.sonic_charge", 1f, 0.7f);
            }
            if (t >= endAt) {
                phase = Phase.ENDED;
                onEnded();
            }
        }

        if (phase == Phase.ACTIVE || phase == Phase.WEAK) {
            pruneLoot();
            boolean active = phase == Phase.ACTIVE;
            int cap = plugin.getConfig().getInt(active ? "loot-blocks.cap-active" : "loot-blocks.cap-weak", active ? 14 : 4);
            double ch = plugin.getConfig().getDouble(active ? "loot-blocks.chance-active" : "loot-blocks.chance-weak", active ? 0.35 : 0.07);
            if (coreBuilt && loot.size() < cap && rnd.nextDouble() < ch) spawnLootBlock();
            if (plugin.getConfig().getBoolean("mobs.enabled", true)
                    && t % Math.max(1, plugin.getConfig().getInt("mobs.interval-seconds", 8)) == 0) spawnMob();
        }

        if (phase == Phase.ENDED && t >= endAt + ruinDelay) {
            phase = Phase.RUIN;
            placeRuin();
        }

        if (phase == Phase.RUIN && t >= endAt + ruinDelay + ruinDur) {
            broadcast(m("finish"));
            finish(false);
            return;
        }

        updateBar();
        updatePanel();
        t++;
    }

    // ===================== открытие =====================

    private void open() {
        phase = Phase.ACTIVE;
        radius = startR;
        enqueue(-1, startR);
        broadcast(m("open"));
        for (Player p : Bukkit.getOnlinePlayers())
            p.sendTitle(Util.c(plugin.getConfig().getString("messages.open-title", "")),
                    Util.c(plugin.getConfig().getString("messages.open-subtitle", "")), 10, 60, 20);
        Util.soundAll("entity.wither.spawn", 0.8f, 0.7f);
        Util.soundAll("entity.lightning_bolt.thunder", 1f, 0.8f);
        for (int i = 0; i < 3; i++) {
            Location l = centerLoc.clone().add((rnd.nextDouble() - 0.5) * 8, 0, (rnd.nextDouble() - 0.5) * 8);
            l.setY(world.getHighestBlockYAt(l.getBlockX(), l.getBlockZ()) + 1);
            world.strikeLightningEffect(l);
        }
        runHooks("hooks.on-open");
    }

    private void runHooks(String path) {
        for (String cmd : plugin.getConfig().getStringList(path)) {
            String s = cmd.replace("{world}", world.getName()).replace("{x}", String.valueOf(cx))
                    .replace("{y}", String.valueOf(refY)).replace("{z}", String.valueOf(cz))
                    .replace("{radius}", String.valueOf(maxR));
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), s);
            } catch (Throwable ex) {
                plugin.getLogger().warning("Ошибка команды хука: " + s);
            }
        }
    }

    // ===================== строительство воронки =====================

    private void enqueue(double from, double to) {
        int r = (int) Math.ceil(to);
        List<int[]> add = new ArrayList<>();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d > from && d <= to) add.add(new int[]{x, z});
            }
        }
        Collections.shuffle(add, rnd);
        queue.addAll(add);
    }

    private void buildStep() {
        if (!isRunning()) return;
        int n = 0;
        while (!queue.isEmpty() && n < 140) {
            int[] c = queue.poll();
            processColumn(c[0], c[1]);
            n++;
        }
        if (queue.isEmpty() && !coreBuilt && (phase == Phase.ACTIVE || phase == Phase.WEAK)) {
            buildCore();
            coreBuilt = true;
            for (int i = 0; i < plugin.getConfig().getInt("loot-blocks.initial", 6); i++) spawnLootBlock();
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) ((y + 2048) & 0xFFF);
    }

    private void setBlock(int x, int y, int z, Material m) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return;
        Block b = world.getBlockAt(x, y, z);
        long k = key(x, y, z);
        Saved s = saved.get(k);
        if (s == null) saved.put(k, new Saved(x, y, z, b.getBlockData(), m));
        else s.placed = m;
        b.setType(m, false);
    }

    private static boolean isTreePart(String n) {
        return n.endsWith("_LEAVES") || n.endsWith("_LOG") || n.endsWith("_WOOD") || n.equals("MANGROVE_ROOTS") || n.equals("SNOW");
    }

    private void processColumn(int x, int z) {
        double d = Math.hypot(x - cx, z - cz);
        int surf = world.getHighestBlockYAt(x, z);
        int guard = 0;
        while (guard++ < 40 && isTreePart(world.getBlockAt(x, surf, z).getType().name())) {
            setBlock(x, surf, z, Material.AIR);
            surf = world.getHighestBlockYAt(x, z);
        }
        String sn = world.getBlockAt(x, surf, z).getType().name();
        boolean liquid = sn.contains("WATER") || sn.contains("LAVA");

        if (d <= startR) {
            int dep = (int) Math.round(depth * Math.pow(1.0 - d / startR, 1.4));
            int target = refY - dep;
            if (surf > target) {
                int from = Math.min(surf, target + 45);
                for (int y = from; y > target; y--) setBlock(x, y, z, Material.AIR);
            } else if (surf < target) {
                for (int y = Math.max(surf + 1, target - 8); y < target; y++) setBlock(x, y, z, palette(d, true));
            }
            setBlock(x, target, z, palette(d, true));
            clearPlantAbove(x, target + 1, z);
        } else if (!liquid) {
            setBlock(x, surf, z, palette(d, false));
            if (rnd.nextBoolean()) setBlock(x, surf - 1, z, palette(d, false));
            clearPlantAbove(x, surf + 1, z);
        }
    }

    private void clearPlantAbove(int x, int y, int z) {
        Material mt = world.getBlockAt(x, y, z).getType();
        if (!mt.isAir() && !mt.isSolid() && !mt.name().contains("WATER") && !mt.name().contains("LAVA")) {
            setBlock(x, y, z, Material.AIR);
        }
    }

    private Material palette(double d, boolean floor) {
        double r = rnd.nextDouble();
        String n;
        if (d <= 2.8) n = r < 0.55 ? "CRYING_OBSIDIAN" : "OBSIDIAN";
        else if (d <= 6) n = r < 0.12 ? "CRYING_OBSIDIAN" : r < 0.5 ? "OBSIDIAN" : r < 0.8 ? "BLACKSTONE" : "DEEPSLATE";
        else if (d <= startR + 0.5) n = r < 0.04 ? "AMETHYST_BLOCK" : r < 0.1 ? "SCULK" : r < 0.4 ? "DEEPSLATE" : r < 0.7 ? "BLACKSTONE" : "POLISHED_BLACKSTONE";
        else n = r < 0.12 ? "SCULK" : r < 0.4 ? "BASALT" : r < 0.65 ? "BLACKSTONE" : r < 0.85 ? "SMOOTH_BASALT" : "COARSE_DIRT";
        Material m = Material.matchMaterial(n);
        return m == null ? Material.matchMaterial("BLACKSTONE") : m;
    }

    private void buildCore() {
        Material base = Material.POLISHED_BLACKSTONE_BRICKS;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                setBlock(cx + dx, floorY, cz + dz, base);
                protectedKeys.add(key(cx + dx, floorY, cz + dz));
            }
        setBlock(cx, floorY + 1, cz, Material.BEACON);
        setBlock(cx, floorY + 2, cz, Material.YELLOW_STAINED_GLASS);
        protectedKeys.add(key(cx, floorY + 1, cz));
        protectedKeys.add(key(cx, floorY + 2, cz));
        for (int y = floorY + 3; y <= world.getHighestBlockYAt(cx, cz) + 2; y++) {
            if (!world.getBlockAt(cx, y, cz).getType().isAir()) setBlock(cx, y, cz, Material.AIR);
        }
    }

    // ===================== лут-блоки =====================

    private void spawnLootBlock() {
        for (int i = 0; i < 14; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double rr = 3 + Math.sqrt(rnd.nextDouble()) * Math.max(1, radius - 4);
            int x = cx + (int) Math.round(Math.cos(ang) * rr);
            int z = cz + (int) Math.round(Math.sin(ang) * rr);
            int y = world.getHighestBlockYAt(x, z);
            Block ground = world.getBlockAt(x, y, z);
            String gn = ground.getType().name();
            if (!ground.getType().isSolid() || gn.contains("WATER") || gn.contains("LAVA")) continue;
            if (loot.containsKey(key(x, y, z)) || protectedKeys.contains(key(x, y, z))) continue;
            if (!world.getBlockAt(x, y + 1, z).getType().isAir()) continue;
            setBlock(x, y + 1, z, lootMat);
            loot.put(key(x, y + 1, z), new int[]{x, y + 1, z});
            Location l = new Location(world, x + 0.5, y + 1.5, z + 0.5);
            particle("END_ROD", l, 12, 0.3, 0.4, 0.3, 0.05, false);
            world.playSound(l, "block.amethyst_block.chime", 1f, 1.2f);
            return;
        }
    }

    private void pruneLoot() {
        Iterator<Map.Entry<Long, int[]>> it = loot.entrySet().iterator();
        while (it.hasNext()) {
            int[] p = it.next().getValue();
            if (world.getBlockAt(p[0], p[1], p[2]).getType() != lootMat) it.remove();
        }
    }

    private void removeAllLoot() {
        for (int[] p : loot.values()) {
            Block b = world.getBlockAt(p[0], p[1], p[2]);
            if (b.getType() == lootMat) {
                b.setType(Material.AIR, false);
                particle("SMOKE", new Location(world, p[0] + 0.5, p[1] + 0.5, p[2] + 0.5), 10, 0.3, 0.3, 0.3, 0.02, false);
            }
        }
        loot.clear();
    }

    public void handleBreak(BlockBreakEvent e) {
        if (!isRunning() || world == null) return;
        Block b = e.getBlock();
        if (!b.getWorld().getName().equals(world.getName())) return;
        long k = key(b.getX(), b.getY(), b.getZ());
        Player p = e.getPlayer();

        if (protectedKeys.contains(k)) {
            e.setCancelled(true);
            p.sendMessage(m("protected"));
            return;
        }
        if (!loot.containsKey(k) || b.getType() != lootMat) return;

        e.setCancelled(true);
        e.setDropItems(false);
        loot.remove(k);
        b.setType(Material.AIR, false);
        stats.merge(p.getUniqueId(), 1, Integer::sum);

        Location l = new Location(world, b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
        particle("END_ROD", l, 20, 0.3, 0.3, 0.3, 0.1, false);
        particle("PORTAL", l, 25, 0.4, 0.4, 0.4, 0.5, false);
        world.playSound(l, "entity.experience_orb.pickup", 1f, 1f);

        FileConfiguration c = plugin.getConfig();
        if (rnd.nextDouble() < c.getDouble("loot-blocks.ambush-chance", 0.06)) {
            spawnMobAt(l);
            p.sendMessage(Util.c("&5Из блока вырвалась тень!"));
        }

        int min = c.getInt("loot-blocks.items-min", 1);
        int max = Math.max(min, c.getInt("loot-blocks.items-max", 3));
        int cnt = min + rnd.nextInt(max - min + 1);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < cnt; i++) {
            LootEntry le = roll();
            int amount = le.min + rnd.nextInt(le.max - le.min + 1);
            give(p, new ItemStack(le.mat, amount));
            names.add(amount + "x " + Util.ruName(le.mat.name()));
        }
        if (rnd.nextDouble() < c.getDouble("loot-blocks.donate-chance", 0.03)) {
            ItemStack d = plugin.items().random(rnd);
            give(p, d);
            names.add("\u00a76" + d.getItemMeta().getDisplayName());
            Bukkit.broadcastMessage(m("") + Util.c("&6" + p.getName() + " &7нашёл в Разломе ") + d.getItemMeta().getDisplayName());
        }
        if (c.getBoolean("command-rewards.enabled", false) && rnd.nextDouble() < c.getDouble("command-rewards.chance", 0.1)) {
            rewardCommand(p);
        }
        p.sendMessage(m("loot-got", "items", String.join("&7, &f", names)));
    }

    private void rewardCommand(Player p) {
        List<Map<?, ?>> list = plugin.getConfig().getMapList("command-rewards.list");
        int total = 0;
        for (Map<?, ?> mm : list) total += ((Number) mm.get("weight")).intValue();
        if (total <= 0) return;
        int r = rnd.nextInt(total);
        for (Map<?, ?> mm : list) {
            r -= ((Number) mm.get("weight")).intValue();
            if (r < 0) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), String.valueOf(mm.get("cmd")).replace("{player}", p.getName()));
                return;
            }
        }
    }

    private LootEntry roll() {
        int r = rnd.nextInt(tableWeight);
        for (LootEntry e : table) {
            r -= e.weight;
            if (r < 0) return e;
        }
        return table.get(0);
    }

    private void give(Player p, ItemStack it) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(it);
        for (ItemStack rest : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
    }

    // ===================== моби =====================

    private int playersInZone() {
        int n = 0;
        for (Player p : world.getPlayers()) {
            Location l = p.getLocation();
            if (Math.hypot(l.getX() - cx, l.getZ() - cz) <= radius + 2) n++;
        }
        return n;
    }

    private void spawnMob() {
        mobs.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            return e == null || !e.isValid();
        });
        if (!coreBuilt || mobs.size() >= plugin.getConfig().getInt("mobs.cap", 8) || playersInZone() == 0) return;
        double ang = rnd.nextDouble() * Math.PI * 2;
        double rr = 4 + rnd.nextDouble() * Math.max(1, radius - 6);
        int x = cx + (int) Math.round(Math.cos(ang) * rr);
        int z = cz + (int) Math.round(Math.sin(ang) * rr);
        int y = world.getHighestBlockYAt(x, z) + 1;
        spawnMobAt(new Location(world, x + 0.5, y, z + 0.5));
    }

    private void spawnMobAt(Location l) {
        List<String> types = plugin.getConfig().getStringList("mobs.types");
        if (types.isEmpty()) return;
        EntityType et = Util.constant(EntityType.class, types.get(rnd.nextInt(types.size())).toUpperCase());
        if (et == null) return;
        try {
            Entity e = world.spawnEntity(l, et);
            e.setCustomName(Util.c(plugin.getConfig().getString("mobs.name", "&6&lСтраж Разлома")));
            e.setCustomNameVisible(true);
            e.setGlowing(true);
            mobs.add(e.getUniqueId());
        } catch (Throwable ex) {
            plugin.getLogger().warning("Не удалось создать моба: " + ex.getMessage());
        }
    }

    public void handleMobDeath(org.bukkit.event.entity.EntityDeathEvent e) {
        if (!mobs.remove(e.getEntity().getUniqueId())) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        if (rnd.nextDouble() < 0.25) {
            LootEntry le = roll();
            e.getDrops().add(new ItemStack(le.mat, Math.max(1, le.min)));
        }
    }

    // ===================== конец лута и Руина =====================

    private void onEnded() {
        removeAllLoot();
        broadcast(m("ended"));
        for (Player p : Bukkit.getOnlinePlayers())
            p.sendTitle(Util.c(plugin.getConfig().getString("messages.ended-title", "")),
                    Util.c(plugin.getConfig().getString("messages.ended-subtitle", "")), 10, 50, 20);
        Util.soundAll("block.beacon.deactivate", 1f, 0.8f);
        Util.soundAll("entity.wither.break_block", 0.8f, 0.8f);
        // топ добытчиков
        List<Map.Entry<UUID, Integer>> top = new ArrayList<>(stats.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        if (!top.isEmpty()) {
            Bukkit.broadcastMessage(m("") + Util.c(plugin.getConfig().getString("messages.top-header", "")));
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                Player pl = Bukkit.getPlayer(top.get(i).getKey());
                String name = pl != null ? pl.getName() : "???";
                Bukkit.broadcastMessage(Util.c(plugin.getConfig().getString("messages.top-line", ""))
                        .replace("{place}", String.valueOf(i + 1)).replace("{name}", name)
                        .replace("{count}", String.valueOf(top.get(i).getValue())));
            }
        }
    }

    private void placeRuin() {
        int bx = cx + 2, bz = cz;
        int by = world.getHighestBlockYAt(bx, bz) + 1;
        setBlock(bx, by, bz, Material.BARREL);
        protectedKeys.add(key(bx, by, bz));
        BlockState st = world.getBlockAt(bx, by, bz).getState();
        if (st instanceof Container) {
            Inventory inv = ((Container) st).getInventory();
            List<ItemStack> items = new ArrayList<>();
            items.add(new ItemStack(Material.NETHERITE_BLOCK, plugin.getConfig().getInt("ruin.netherite-blocks", 2)));
            int dmin = plugin.getConfig().getInt("ruin.donate-min", 2);
            int dmax = Math.max(dmin, plugin.getConfig().getInt("ruin.donate-max", 3));
            int dn = Math.min(RiftItems.ALL.size(), dmin + rnd.nextInt(dmax - dmin + 1));
            List<String> pool = new ArrayList<>(RiftItems.ALL);
            Collections.shuffle(pool, rnd);
            for (int i = 0; i < dn; i++) items.add(plugin.items().create(pool.get(i)));
            items.add(new ItemStack(Material.matchMaterial("ENCHANTED_GOLDEN_APPLE"), 1 + rnd.nextInt(2)));
            items.add(new ItemStack(Material.matchMaterial("GOLDEN_APPLE"), 8));
            items.add(new ItemStack(Material.matchMaterial("DIAMOND"), 8));
            items.add(new ItemStack(Material.matchMaterial("EXPERIENCE_BOTTLE"), 32));
            items.add(new ItemStack(Material.matchMaterial("ENDER_PEARL"), 8));
            items.add(new ItemStack(Material.matchMaterial("TOTEM_OF_UNDYING"), 1));
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < 27; i++) slots.add(i);
            Collections.shuffle(slots, rnd);
            for (int i = 0; i < items.size(); i++) inv.setItem(slots.get(i), items.get(i));
        }
        Location l = new Location(world, bx + 0.5, by + 0.5, bz + 0.5);
        particle("TOTEM_OF_UNDYING", l, 60, 0.4, 0.6, 0.4, 0.3, true);
        world.playSound(l, "block.end_portal.spawn", 1f, 1.4f);
        Util.soundAll("ui.toast.challenge_complete", 1f, 1f);
        broadcast(m("ruin", "time", Util.time(ruinDur)));
    }

    // ===================== эффекты =====================

    private void particle(String name, Location l, int count, double dx, double dy, double dz, double extra, boolean force) {
        Particle p = Util.constant(Particle.class, name);
        if (p == null) return;
        try {
            world.spawnParticle(p, l, count, dx, dy, dz, extra, null, force);
        } catch (Throwable ignored) {
        }
    }

    private void fx() {
        if (!isRunning()) return;
        fxCounter++;
        swirl += 0.35;
        if (phase == Phase.COUNTDOWN) {
            if (t >= -coordsAt) {
                particle("PORTAL", new Location(world, cx + 0.5, refY + 1, cz + 0.5), 30, 2.5, 0.6, 2.5, 0.5, false);
                particle("SMOKE", new Location(world, cx + 0.5, refY + 1, cz + 0.5), 6, 2.0, 0.3, 2.0, 0.02, false);
            }
            return;
        }
        // луч + вихрь вокруг ядра
        for (int i = 0; i < 12; i++) {
            particle("END_ROD", new Location(world, cx + 0.5, floorY + 3 + i * 4, cz + 0.5), 1, 0.1, 0.5, 0.1, 0, true);
        }
        for (int k = 0; k < 3; k++) {
            double a = swirl + k * 2.094;
            Location l = new Location(world, cx + 0.5 + Math.cos(a) * 2.6, floorY + 1.2 + (fxCounter % 10) * 0.3, cz + 0.5 + Math.sin(a) * 2.6);
            particle("REVERSE_PORTAL", l, 3, 0.1, 0.1, 0.1, 0.02, false);
        }
        if (fxCounter % 2 == 0) {
            for (int[] p : loot.values()) {
                particle("END_ROD", new Location(world, p[0] + 0.5, p[1] + 1.3, p[2] + 0.5), 2, 0.2, 0.2, 0.2, 0.01, true);
            }
        }
    }

    // ===================== HUD =====================

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(msg);
    }

    private String m(String key, Object... kv) {
        FileConfiguration c = plugin.getConfig();
        String prefix = Util.c(c.getString("messages.prefix", ""));
        if (key.isEmpty()) return prefix;
        String s = c.getString("messages." + key, key);
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        return prefix + Util.c(s);
    }

    private void updateBar() {
        if (bar == null) return;
        String coords = (phase == Phase.COUNTDOWN && t < -coordsAt) ? "\u00a77координаты скоро" : "\u00a7fX " + cx + " Z " + cz;
        switch (phase) {
            case COUNTDOWN:
                bar.setColor(BarColor.PURPLE);
                bar.setTitle("\u00a75\u00a7lРазлом \u00a77откроется через \u00a7f" + Util.time(-t) + " \u00a77| " + coords);
                bar.setProgress(Math.max(0, Math.min(1, (double) (t - startT) / Math.max(1, -startT))));
                break;
            case ACTIVE:
                bar.setColor(BarColor.PURPLE);
                bar.setTitle("\u00a75\u00a7lРазлом \u00a77| " + coords + " \u00a77| \u00a7eЛут: " + Util.time(endAt - t));
                bar.setProgress(Math.max(0, Math.min(1, (double) (endAt - t) / endAt)));
                break;
            case WEAK:
                bar.setColor(BarColor.YELLOW);
                bar.setTitle("\u00a76\u00a7lРазлом нестабилен \u00a77| " + coords + " \u00a77| \u00a7eЛут: " + Util.time(endAt - t));
                bar.setProgress(Math.max(0, Math.min(1, (double) (endAt - t) / endAt)));
                break;
            case ENDED:
                bar.setColor(BarColor.RED);
                bar.setTitle("\u00a7c\u00a7lЛут закончился");
                bar.setProgress(1.0);
                break;
            case RUIN:
                bar.setColor(BarColor.PINK);
                int left = endAt + ruinDelay + ruinDur - t;
                bar.setTitle("\u00a76\u00a7lРуина \u00a77| \u00a7fX " + (cx + 2) + " Z " + cz + " \u00a77| \u00a7e" + Util.time(left));
                bar.setProgress(Math.max(0, Math.min(1, (double) left / Math.max(1, ruinDur))));
                break;
            default:
                break;
        }
    }

    private void updatePanel() {
        if (!plugin.getConfig().getBoolean("panel.enabled", true) || world == null || !coreBuilt) return;
        String bar = "\u00a78\u2502 \u00a76";
        int timeLeft = phase == Phase.RUIN ? endAt + ruinDelay + ruinDur - t : Math.max(0, endAt - t);
        String leader = "\u00a77нет";
        int best = 0;
        UUID bestId = null;
        for (Map.Entry<UUID, Integer> e : stats.entrySet()) {
            if (e.getValue() > best) { best = e.getValue(); bestId = e.getKey(); }
        }
        if (bestId != null) {
            Player lp = Bukkit.getPlayer(bestId);
            leader = "\u00a7f" + (lp != null ? lp.getName() : "???") + " \u00a77(" + best + ")";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(Util.c(plugin.getConfig().getString("panel.title", "&8[&6&l\u0420\u0410\u0417\u041b\u041e\u041c&8]"))).append("\n\n");
        sb.append(bar).append("\u00a7e\u0421\u0442\u0430\u0442\u0443\u0441: ").append(statusLabel()).append('\n');
        sb.append(bar).append("\u00a7e\u041b\u0443\u0442-\u0431\u043b\u043e\u043a\u043e\u0432: \u00a7f").append(loot.size()).append('\n');
        sb.append(bar).append("\u00a7e\u0420\u0430\u0434\u0438\u0443\u0441: \u00a7f").append(radius).append(" \u0431\u043b.\n");
        sb.append(bar).append("\u00a7e\u0412 \u0437\u043e\u043d\u0435: \u00a7f").append(playersInZone()).append('\n');
        sb.append(bar).append("\u00a7e\u041b\u0438\u0434\u0435\u0440: ").append(leader).append('\n');
        sb.append(bar).append("\u00a7e").append(phase == Phase.RUIN ? "\u0420\u0443\u0438\u043d\u0430: \u00a7f" : "\u0414\u043e \u043a\u043e\u043d\u0446\u0430: \u00a7f").append(Util.time(timeLeft));
        String text = sb.toString();
        try {
            if (panel == null || !panel.isValid()) {
                Location l = new Location(world, cx + 0.5, refY + plugin.getConfig().getDouble("panel.height", 4.0), cz + 0.5);
                panel = world.spawn(l, TextDisplay.class);
                panel.setBillboard(Display.Billboard.CENTER);
                panel.setAlignment(TextDisplay.TextAlignment.LEFT);
                panel.setLineWidth(220);
                panel.setViewRange((float) plugin.getConfig().getDouble("panel.view-range", 2.0));
                panel.setShadowed(true);\n                panel.setBackgroundColor(org.bukkit.Color.fromARGB(90, 12, 10, 6));
                panel.setPersistent(false);
                lastPanelText = "";
            }
            if (!text.equals(lastPanelText)) {
                panel.setText(text);
                lastPanelText = text;
            }
        } catch (Throwable ex) {
            plugin.getLogger().warning("Не удалось создать панель Разлома: " + ex);
            panel = null;
        }
    }

    private void removePanel() {
        try {
            if (panel != null) panel.remove();
        } catch (Throwable ignored) {
        }
        panel = null;
        lastPanelText = "";
    }

    private String statusLabel() {
        switch (phase) {
            case ACTIVE: return "\u00a7a\u0410\u043a\u0442\u0438\u0432\u0435\u043d";
            case WEAK: return "\u00a76\u041d\u0435\u0441\u0442\u0430\u0431\u0438\u043b\u0435\u043d";
            case ENDED: return "\u00a7c\u041b\u0443\u0442 \u0437\u0430\u043a\u043e\u043d\u0447\u0438\u043b\u0441\u044f";
            case RUIN: return "\u00a7d\u0420\u0443\u0438\u043d\u0430";
            default: return "\u00a77...";
        }
    }

    // ===================== завершение =====================

    private void finish(boolean immediate) {
        if (tickTask != null) tickTask.cancel();
        if (fxTask != null) fxTask.cancel();
        if (buildTask != null) buildTask.cancel();
        tickTask = fxTask = buildTask = null;
        queue.clear();
        runHooks("hooks.on-close");

        if (bar != null) {
            bar.removeAll();
            bar = null;
        }
        removePanel();

        for (UUID id : mobs) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        mobs.clear();
        removeAllLoot();

        phase = Phase.IDLE;

        final List<Saved> list = new ArrayList<>(saved.values());
        saved.clear();
        protectedKeys.clear();
        final boolean restore = plugin.getConfig().getBoolean("restore-terrain", true);
        final World w = world;
        final List<int[]> tk = new ArrayList<>(tickets);
        tickets.clear();

        if (!restore) {
            // оставляем воронку, но убираем маяк, бочку и стекло
            for (Saved s : list) {
                if (s.placed == Material.BEACON || s.placed == Material.PURPLE_STAINED_GLASS || s.placed == Material.BARREL) {
                    Block b = w.getBlockAt(s.x, s.y, s.z);
                    if (b.getType() == s.placed) b.setType(Material.AIR, false);
                }
            }
            for (int[] c : tk) w.removePluginChunkTicket(c[0], c[1], plugin);
            return;
        }

        if (immediate) {
            for (Saved s : list) restoreOne(w, s);
            for (int[] c : tk) w.removePluginChunkTicket(c[0], c[1], plugin);
            return;
        }

        restoring = true;
        final Iterator<Saved> it = list.iterator();
        final BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int n = 0;
            while (it.hasNext() && n < 700) {
                restoreOne(w, it.next());
                n++;
            }
            if (!it.hasNext()) {
                holder[0].cancel();
                for (int[] c : tk) w.removePluginChunkTicket(c[0], c[1], plugin);
                restoring = false;
            }
        }, 1L, 1L);
    }

    private void restoreOne(World w, Saved s) {
        Block b = w.getBlockAt(s.x, s.y, s.z);
        if (b.getType() == s.placed || s.placed == Material.BEACON || s.placed == Material.BARREL) {
            b.setBlockData(s.orig, false);
        }
    }
}
