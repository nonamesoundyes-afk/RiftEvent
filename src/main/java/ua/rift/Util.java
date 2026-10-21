package ua.rift;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Method;
import java.util.Collection;

/** Вспомогательные методы. Константы Bukkit берём через рефлексию, чтобы плагин не ломался от смены enum/interface между версиями. */
public final class Util {
    private Util() {}

    @SuppressWarnings("unchecked")
    public static final PersistentDataType<String, String> STR = (PersistentDataType<String, String>) field(PersistentDataType.class, "STRING");
    @SuppressWarnings("unchecked")
    public static final PersistentDataType<Integer, Integer> INT = (PersistentDataType<Integer, Integer>) field(PersistentDataType.class, "INTEGER");

    private static Object field(Class<?> c, String name) {
        try {
            return c.getField(name).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static <T> T constant(Class<T> type, String name) {
        try {
            return type.cast(type.getField(name).get(null));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Достаёт элемент из реестра (ENCHANTMENT, EFFECT ...) по ключу minecraft:key. */
    public static Object reg(String registryField, String key) {
        try {
            Object r = Registry.class.getField(registryField).get(null);
            return ((Registry<?>) r).get(NamespacedKey.minecraft(key));
        } catch (Throwable t) {
            return null;
        }
    }

    public static String c(String s) {
        return s == null ? "" : s.replace('&', '\u00a7');
    }

    public static String time(int sec) {
        if (sec < 0) sec = 0;
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    public static void soundAll(String key, float vol, float pitch) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                p.playSound(p.getLocation(), key, vol, pitch);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void blankNumbers(Object objective) {
        try {
            Class<?> nf = Class.forName("io.papermc.paper.scoreboard.numbers.NumberFormat");
            Object blank = nf.getMethod("blank").invoke(null);
            for (Method m : objective.getClass().getMethods()) {
                if (m.getName().equals("numberFormat") && m.getParameterCount() == 1) {
                    m.invoke(objective, blank);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static String ruName(String material) {
        String ru = RU.get(material);
        if (ru != null) return ru;
        return material.toLowerCase().replace('_', ' ');
    }

    private static final java.util.Map<String, String> RU = new java.util.HashMap<>();
    static {
        RU.put("DIAMOND", "Алмаз");
        RU.put("DIAMOND_BLOCK", "Алмазный блок");
        RU.put("NETHERITE_SCRAP", "Незеритовый лом");
        RU.put("NETHERITE_INGOT", "Незеритовый слиток");
        RU.put("NETHERITE_BLOCK", "Незеритовый блок");
        RU.put("GOLDEN_APPLE", "Золотое яблоко");
        RU.put("ENCHANTED_GOLDEN_APPLE", "Зачарованное золотое яблоко");
        RU.put("ENDER_PEARL", "Жемчуг Края");
        RU.put("EXPERIENCE_BOTTLE", "Пузырёк опыта");
        RU.put("TNT", "Динамит");
        RU.put("OBSIDIAN", "Обсидиан");
        RU.put("IRON_INGOT", "Железный слиток");
        RU.put("GOLD_INGOT", "Золотой слиток");
        RU.put("EMERALD", "Изумруд");
        RU.put("COBWEB", "Паутина");
        RU.put("TOTEM_OF_UNDYING", "Тотем бессмертия");
        RU.put("ARROW", "Стрела");
        RU.put("COOKED_BEEF", "Жареная говядина");
        RU.put("FIREWORK_ROCKET", "Фейерверк");
        RU.put("GOLDEN_CARROT", "Золотая морковь");
        RU.put("GUNPOWDER", "Порох");
        RU.put("BLAZE_ROD", "Стержень ифрита");
        RU.put("ANCIENT_DEBRIS", "Древние обломки");
        RU.put("LAPIS_LAZULI", "Лазурит");
        RU.put("REDSTONE", "Редстоун");
    }
}
