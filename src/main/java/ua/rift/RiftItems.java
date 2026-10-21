package ua.rift;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/** Донат-предметы из Разлома (облегчённые, сбалансированные версии магазинных). */
public final class RiftItems {
    public static final String MIDAS = "midas";
    public static final String CALM = "calm";
    public static final String BOOTS = "boots";
    public static final String LEGGINGS = "leggings";
    public static final List<String> ALL = Arrays.asList(MIDAS, CALM, BOOTS, LEGGINGS);

    private final RiftPlugin plugin;
    private final NamespacedKey idKey;
    private final NamespacedKey timeKey;
    private final Map<UUID, Integer> pending = new HashMap<>();

    public RiftItems(RiftPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "rift_item");
        this.timeKey = new NamespacedKey(plugin, "rift_time");
    }

    public int orbSeconds() {
        return Math.max(30, plugin.getConfig().getInt("donate.orb-seconds", 600));
    }

    public ItemStack create(String type) {
        switch (type) {
            case MIDAS: return orb(MIDAS, orbSeconds());
            case CALM: return orb(CALM, orbSeconds());
            case BOOTS: return armor(BOOTS);
            case LEGGINGS: return armor(LEGGINGS);
            default: return null;
        }
    }

    public ItemStack random(Random r) {
        return create(ALL.get(r.nextInt(ALL.size())));
    }

    // ---------- создание ----------

    private ItemStack orb(String type, int seconds) {
        boolean midas = type.equals(MIDAS);
        Material mat = Material.matchMaterial(plugin.getConfig().getString(midas ? "donate.midas-material" : "donate.calm-material", midas ? "GLOWSTONE_DUST" : "PRISMARINE_SHARD"));
        if (mat == null) mat = Material.matchMaterial("HEART_OF_THE_SEA");
        ItemStack it = new ItemStack(mat, 1);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(midas ? "\u00a76\u00a7lШар Мидаса \u00a77(Осколок)" : "\u00a7f\u00a7lШар Покоя \u00a77(Осколок)");
        m.setLore(orbLore(type, seconds));
        m.getPersistentDataContainer().set(idKey, Util.STR, type);
        m.getPersistentDataContainer().set(timeKey, Util.INT, seconds);
        try {
            m.setEnchantmentGlintOverride(Boolean.TRUE);
        } catch (Throwable ignored) {
        }
        it.setItemMeta(m);
        return it;
    }

    private List<String> orbLore(String type, int seconds) {
        List<String> l = new ArrayList<>();
        String bar = "\u00a76\u2503 ";
        l.add(bar + "\u00a7fПри применении во второй руке:");
        l.add(bar + "\u00a7fДаёт эффект \u00a7aСила I");
        l.add(bar + "\u00a7fДаёт эффект \u00a7aСкорость I");
        l.add(bar + "\u00a7fДаёт эффект \u00a7aОгнестойкость I");
        if (type.equals(CALM)) l.add(bar + "\u00a7fДаёт эффект \u00a7aПрилив здоровья I");
        l.add(bar);
        l.add(bar + "\u00a7fЗаряд: \u00a7e" + Util.time(seconds) + " \u00a77(тратится в руке)");
        l.add(bar + "\u00a78Добыто в Разломе");
        return l;
    }

    private ItemStack armor(String type) {
        boolean boots = type.equals(BOOTS);
        ItemStack it = new ItemStack(boots ? Material.DIAMOND_BOOTS : Material.DIAMOND_LEGGINGS, 1);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(boots ? "\u00a7e\u00a7lСолнечные ботинки \u00a77(Рифт)" : "\u00a7e\u00a7lСолнечные поножи \u00a77(Рифт)");
        List<String> l = new ArrayList<>();
        String bar = "\u00a76\u2503 ";
        l.add(bar + "\u00a7fЗащита III");
        l.add(bar + "\u00a7fПрочность II");
        if (boots) {
            l.add(bar + "\u00a7fНевесомость III");
            l.add(bar + "\u00a7fПодводная ходьба II");
        }
        l.add(bar);
        l.add(bar + "\u00a78Добыто в Разломе");
        m.setLore(l);
        enchant(m, "protection", 3);
        enchant(m, "unbreaking", 2);
        if (boots) {
            enchant(m, "feather_falling", 3);
            enchant(m, "depth_strider", 2);
        }
        m.getPersistentDataContainer().set(idKey, Util.STR, type);
        it.setItemMeta(m);
        return it;
    }

    private void enchant(ItemMeta m, String key, int lvl) {
        Object e = Util.reg("ENCHANTMENT", key);
        if (e != null) m.addEnchant((Enchantment) e, lvl, true);
    }

    // ---------- чтение ----------

    public String idOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(idKey, Util.STR);
    }

    private int timeOf(ItemStack it) {
        Integer v = it.getItemMeta().getPersistentDataContainer().get(timeKey, Util.INT);
        return v == null ? 0 : v;
    }

    // ---------- эффекты шаров (вызывается раз в секунду) ----------

    public void tickOrbs() {
        Set<UUID> seen = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack off = p.getInventory().getItemInOffHand();
            String id = idOf(off);
            if (id == null || !(id.equals(MIDAS) || id.equals(CALM))) continue;
            seen.add(p.getUniqueId());
            apply(p, "strength");
            apply(p, "speed");
            apply(p, "fire_resistance");
            if (id.equals(CALM)) apply(p, "health_boost");
            int used = pending.merge(p.getUniqueId(), 1, Integer::sum);
            if (used >= 30) {
                pending.put(p.getUniqueId(), 0);
                int left = timeOf(off) - used;
                if (left <= 0) {
                    p.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
                    p.sendMessage(Util.c(plugin.getConfig().getString("messages.orb-broken", "&cШар рассыпался в пыль.")));
                    try {
                        p.playSound(p.getLocation(), "entity.item.break", 1f, 1f);
                    } catch (Throwable ignored) {
                    }
                } else {
                    ItemMeta m = off.getItemMeta();
                    m.getPersistentDataContainer().set(timeKey, Util.INT, left);
                    m.setLore(orbLore(id, left));
                    off.setItemMeta(m);
                    p.getInventory().setItemInOffHand(off);
                }
            }
        }
        pending.keySet().retainAll(seen);
    }

    private void apply(Player p, String effect) {
        Object t = Util.reg("EFFECT", effect);
        if (t != null) p.addPotionEffect(new PotionEffect((PotionEffectType) t, 60, 0, false, false, true));
    }
}
