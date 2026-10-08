package net.elysium.economy;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/** %elysium_money% %elysium_money_formatted% %elysium_shards% %elysium_shards_formatted% */
public final class Expansion extends PlaceholderExpansion {
    private final ElysiumEconomy plugin;
    public Expansion(ElysiumEconomy plugin) { this.plugin = plugin; }

    @Override public @NotNull String getIdentifier() { return "elysium"; }
    @Override public @NotNull String getAuthor() { return "Elysium"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer p, @NotNull String params) {
        if (p == null) return "";
        return switch (params.toLowerCase()) {
            case "money" -> String.valueOf((long) plugin.getMoney(p.getUniqueId()));
            case "money_formatted" -> ElysiumEconomy.fmt(plugin.getMoney(p.getUniqueId()));
            case "shards" -> String.valueOf((long) plugin.getShards(p.getUniqueId()));
            case "shards_formatted" -> ElysiumEconomy.fmt(plugin.getShards(p.getUniqueId()));
            default -> null;
        };
    }
}
