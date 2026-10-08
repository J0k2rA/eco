package net.elysium.economy;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class ElysiumEconomy extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX =
            "<gradient:#1E90FF:#00D4FF><bold>Elysium</bold></gradient> <dark_gray>»</dark_gray> ";

    /** UUID -> [money, shards] */
    private final Map<UUID, double[]> data = new ConcurrentHashMap<>();
    private File file;

    // ───────────── lifecycle ─────────────
    @Override
    public void onEnable() {
        saveDefaultConfig();
        file = new File(getDataFolder(), "data.yml");
        load();

        for (String c : List.of("balance", "shards", "pay", "baltop", "eco")) {
            PluginCommand cmd = getCommand(c);
            if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }
        }
        Bukkit.getPluginManager().registerEvents(this, this);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new Expansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::save, 6000L, 6000L);
        getLogger().info("Elysium Economy enabled.");
    }

    @Override
    public void onDisable() { save(); }

    // ───────────── storage ─────────────
    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            try {
                data.put(UUID.fromString(k), new double[]{y.getDouble(k + ".money"), y.getDouble(k + ".shards")});
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        data.forEach((u, d) -> { y.set(u + ".money", d[0]); y.set(u + ".shards", d[1]); });
        try { y.save(file); } catch (IOException e) { getLogger().severe("Could not save data: " + e.getMessage()); }
    }

    private double[] acc(UUID u) {
        return data.computeIfAbsent(u, k -> new double[]{
                getConfig().getDouble("starting-money", 1000), getConfig().getDouble("starting-shards", 0)});
    }

    public double getMoney(UUID u) { return acc(u)[0]; }
    public double getShards(UUID u) { return acc(u)[1]; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { acc(e.getPlayer().getUniqueId()); }

    // ───────────── formatting ─────────────
    public static String fmt(double v) {
        String[] s = {"", "K", "M", "B", "T", "Q"};
        int i = 0;
        while (Math.abs(v) >= 1000 && i < s.length - 1) { v /= 1000; i++; }
        String r = String.format(Locale.US, "%.2f", v);
        if (r.contains(".")) r = r.replaceAll("0+$", "").replaceAll("\\.$", "");
        return r + s[i];
    }

    /** Accepts 500, 1.5k, 2m, 3b, 1t */
    public static double parse(String in) {
        String s = in.toLowerCase(Locale.ROOT).replace(",", "");
        double m = 1;
        switch (s.charAt(s.length() - 1)) {
            case 'k' -> m = 1e3; case 'm' -> m = 1e6; case 'b' -> m = 1e9; case 't' -> m = 1e12;
            default -> { }
        }
        if (m != 1) s = s.substring(0, s.length() - 1);
        double v = Double.parseDouble(s) * m;
        if (Double.isNaN(v) || Double.isInfinite(v)) throw new NumberFormatException();
        return v;
    }

    private void msg(CommandSender s, String mm) { s.sendMessage(MM.deserialize(PREFIX + mm)); }

    private OfflinePlayer find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        return (off != null && data.containsKey(off.getUniqueId())) ? off : null;
    }

    // ───────────── commands ─────────────
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        switch (cmd.getName().toLowerCase()) {
            case "balance", "shards" -> {
                boolean shards = cmd.getName().equalsIgnoreCase("shards");
                OfflinePlayer t;
                if (a.length > 0) {
                    t = find(a[0]);
                    if (t == null) { msg(sender, "<red>Player not found."); return true; }
                } else if (sender instanceof Player p) t = p;
                else { msg(sender, "<red>Specify a player."); return true; }
                double v = shards ? getShards(t.getUniqueId()) : getMoney(t.getUniqueId());
                String who = t.getUniqueId().equals(sender instanceof Player p2 ? p2.getUniqueId() : null)
                        ? "Your" : t.getName() + "'s";
                msg(sender, "<gray>" + who + (shards ? " shards: <#B86BFF>" : " balance: <#00D4FF>$")
                        + fmt(v));
            }
            case "pay" -> {
                if (!(sender instanceof Player p)) { msg(sender, "<red>Players only."); return true; }
                if (a.length < 2) { msg(sender, "<gray>Usage: <#00D4FF>/pay <player> <amount>"); return true; }
                OfflinePlayer t = find(a[0]);
                if (t == null) { msg(sender, "<red>Player not found."); return true; }
                if (t.getUniqueId().equals(p.getUniqueId())) { msg(sender, "<red>You can't pay yourself."); return true; }
                double amt;
                try { amt = parse(a[1]); } catch (Exception e) { msg(sender, "<red>Invalid amount."); return true; }
                if (amt <= 0) { msg(sender, "<red>Amount must be positive."); return true; }
                synchronized (this) {
                    if (getMoney(p.getUniqueId()) < amt) { msg(sender, "<red>Not enough money."); return true; }
                    acc(p.getUniqueId())[0] -= amt;
                    acc(t.getUniqueId())[0] += amt;
                }
                msg(sender, "<gray>You paid <#00D4FF>" + t.getName() + " $" + fmt(amt));
                if (t instanceof Player tp) msg(tp, "<gray>You received <#00D4FF>$" + fmt(amt) + " <gray>from <#00D4FF>" + p.getName());
            }
            case "baltop" -> {
                List<Map.Entry<UUID, double[]>> top = data.entrySet().stream()
                        .sorted((x, y) -> Double.compare(y.getValue()[0], x.getValue()[0])).limit(10).toList();
                msg(sender, "<gradient:#1E90FF:#00D4FF><bold>Top 10 Richest</bold></gradient>");
                int i = 1;
                for (var e : top) {
                    String n = Optional.ofNullable(Bukkit.getOfflinePlayer(e.getKey()).getName()).orElse("Unknown");
                    sender.sendMessage(MM.deserialize("<dark_gray>" + i++ + ". <#00D4FF>" + n
                            + " <dark_gray>- <gray>$" + fmt(e.getValue()[0])));
                }
            }
            case "eco" -> {
                if (!sender.hasPermission("elysium.admin")) { msg(sender, "<red>No permission."); return true; }
                if (a.length < 3) { msg(sender, "<gray>Usage: <#00D4FF>/eco <give|take|set> <player> <amount> [money|shards]"); return true; }
                OfflinePlayer t = find(a[1]);
                if (t == null) { msg(sender, "<red>Player not found."); return true; }
                double amt;
                try { amt = parse(a[2]); } catch (Exception e) { msg(sender, "<red>Invalid amount."); return true; }
                int idx = (a.length > 3 && a[3].equalsIgnoreCase("shards")) ? 1 : 0;
                double[] d = acc(t.getUniqueId());
                switch (a[0].toLowerCase()) {
                    case "give" -> d[idx] += amt;
                    case "take" -> d[idx] = Math.max(0, d[idx] - amt);
                    case "set" -> d[idx] = Math.max(0, amt);
                    default -> { msg(sender, "<red>Use give, take or set."); return true; }
                }
                msg(sender, "<gray>" + t.getName() + " now has <#00D4FF>" + fmt(d[idx]) + (idx == 1 ? " shards" : " money"));
            }
            default -> { return false; }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        String n = c.getName().toLowerCase();
        List<String> out = new ArrayList<>();
        if (n.equals("eco")) {
            if (a.length == 1) out = List.of("give", "take", "set");
            else if (a.length == 2) out = names();
            else if (a.length == 4) out = List.of("money", "shards");
        } else if ((n.equals("pay") || n.equals("balance") || n.equals("shards")) && a.length == (n.equals("pay") ? 1 : 1)) {
            out = names();
        }
        String last = a[a.length - 1].toLowerCase();
        return out.stream().filter(x -> x.toLowerCase().startsWith(last)).collect(Collectors.toList());
    }

    private List<String> names() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }
}
