package org.rankplugin.rankplugin;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.sql.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RankPlugin extends JavaPlugin implements Listener {

    private Connection connection;
    private final Map<UUID, Integer> scores = new ConcurrentHashMap<>();
    private FileConfiguration cfg;

    // Configurable point parameters
    private int pointsToAdd;
    private int pointsToSubtract;
    private int initialPoints;

    @Override
    public void onEnable() {
        // Load configuration
        saveDefaultConfig();
        cfg = getConfig();
        loadSettings();

        // Check supported server version
        String bukkitVersion = Bukkit.getBukkitVersion();
        if (!isSupportedVersion(bukkitVersion)) {
            getLogger().warning("Этот плагин предназначен для версий от 1.16 до 1.21.5. Ваша версия (" + bukkitVersion + ") может работать некорректно.");
        }

        // Establish database connection
        if (!initializeDatabase()) {
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Register events and admin commands
        registerEventsAndCommands();

        // Register PlaceholderAPI expansion if available
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new RankPlaceholderExpansion(this).register();
            getLogger().info("PAPI expansion %ranksystem_score% registered!");
        } else {
            getLogger().warning("PlaceholderAPI not found! Placeholders will not work.");
        }

        // Load data for online players
        Bukkit.getOnlinePlayers().forEach(this::loadPlayer);
        getLogger().info("RankPlugin enabled!");
    }

    @Override
    public void onDisable() {
        // Save all players' data
        Bukkit.getOnlinePlayers().forEach(this::savePlayer);
        try {
            if (connection != null) connection.close();
        } catch (SQLException ignored) { }
        getLogger().info("RankPlugin disabled!");
    }

    // --- Version check ---
    private boolean isSupportedVersion(@NotNull String bukkitVersion) {
        // Expecting version format like "1.16.5-R0.1-SNAPSHOT" etc.
        String versionNumber = bukkitVersion.split("-")[0]; // "1.16.5"
        String[] parts = versionNumber.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return major == 1 && minor >= 16 && minor <= 21;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // --- Load settings from config ---
    private void loadSettings() {
        pointsToAdd = cfg.getInt("points.add", 1);
        pointsToSubtract = cfg.getInt("points.subtract", 1);
        initialPoints = cfg.getInt("database.initial-points", 0);
    }

    // --- Initialize database connection and create table if needed ---
    private boolean initializeDatabase() {
        String dbType = cfg.getString("database.type", "sqlite");
        try {
            if (dbType.equalsIgnoreCase("sqlite")) {
                File dataFolder = getDataFolder();
                if (!dataFolder.exists()) dataFolder.mkdirs();
                String dbFileName = cfg.getString("database.file", "data.db");
                File dbFile = new File(dataFolder, dbFileName);
                String url = "jdbc:sqlite:" + dbFile.getPath();
                connection = DriverManager.getConnection(url);
                getLogger().info("Connected to SQLite: " + dbFile.getPath());
            } else if (dbType.equalsIgnoreCase("mysql")) {
                String host = cfg.getString("database.host", "localhost");
                int port = cfg.getInt("database.port", 3306);
                String database = cfg.getString("database.database", "rankplugin");
                String username = cfg.getString("database.username", "user");
                String password = cfg.getString("database.password", "pass");
                String url = "jdbc:mysql://" + host + ":" + port + "/" + database +
                        "?autoReconnect=true&useSSL=false";
                connection = DriverManager.getConnection(url, username, password);
                getLogger().info("Connected to MySQL: " + host + ":" + port + "/" + database);
            } else {
                getLogger().severe("Unsupported database type: " + dbType);
                return false;
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS player_scores (" +
                        "uuid TEXT PRIMARY KEY, score INTEGER)");
            }
            return true;
        } catch (SQLException e) {
            getLogger().severe("Database connection error: " + e.getMessage());
            return false;
        }
    }

    // --- Register events and admin commands (all commands check for "rankplugin.admin") ---
    private void registerEventsAndCommands() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("rank").setExecutor(new AdminCommand(new RankCommand()));
        getCommand("rankscore").setExecutor(new AdminCommand(new RankScoreCommand()));
        getCommand("ranksystem").setExecutor(new AdminCommand(new RankSystemCommand()));
        getCommand("reset").setExecutor(new AdminCommand(new ResetCommand()));
    }

    // --- Database operations ---
    private void loadPlayer(@NotNull Player p) {
        UUID id = p.getUniqueId();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT score FROM player_scores WHERE uuid = ?")) {
            ps.setString(1, id.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                scores.put(id, rs.getInt("score"));
            } else {
                scores.put(id, initialPoints);
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO player_scores(uuid, score) VALUES(?,?)")) {
                    insert.setString(1, id.toString());
                    insert.setInt(2, initialPoints);
                    insert.executeUpdate();
                }
            }
        } catch (SQLException e) {
            p.sendMessage(cfg.getString("messages.db_error")
                    .replace("%error%", e.getMessage()));
        }
    }

    private void savePlayer(@NotNull Player p) {
        UUID id = p.getUniqueId();
        int score = scores.getOrDefault(id, 0);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO player_scores(uuid, score) VALUES(?,?) " +
                        "ON CONFLICT(uuid) DO UPDATE SET score = excluded.score")) {
            ps.setString(1, id.toString());
            ps.setInt(2, score);
            ps.executeUpdate();
        } catch (SQLException e) {
            p.sendMessage(cfg.getString("messages.db_error")
                    .replace("%error%", e.getMessage()));
        }
    }

    // --- Events ---
    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent e) {
        Player p = e.getPlayer();
        loadPlayer(p);
        int sc = scores.get(p.getUniqueId());
        p.sendMessage(format("join")
                .replace("%player%", p.getName())
                .replace("%score%", String.valueOf(sc)));
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent e) {
        savePlayer(e.getPlayer());
    }

    @EventHandler
    public void onDeath(@NotNull PlayerDeathEvent e) {
        Player dead = e.getEntity();
        Player killer = dead.getKiller();
        if (killer != null && !killer.equals(dead)) {
            UUID kid = killer.getUniqueId(), did = dead.getUniqueId();
            scores.merge(kid, pointsToAdd, Integer::sum);
            scores.compute(did, (u, v) -> Math.max(0, (v == null ? 0 : v) - pointsToSubtract));
            killer.sendMessage(format("kill")
                    .replace("%killer%", killer.getName())
                    .replace("%killer_score%", String.valueOf(scores.get(kid)))
                    .replace("%points_add%", String.valueOf(pointsToAdd)));
            dead.sendMessage(format("death")
                    .replace("%dead%", dead.getName())
                    .replace("%dead_score%", String.valueOf(scores.get(did)))
                    .replace("%points_subtract%", String.valueOf(pointsToSubtract)));
        }
    }

    // --- Commands ---
    // AdminCommand wrapper to restrict command use to players with "rankplugin.admin" permission
    private class AdminCommand implements CommandExecutor {
        private final CommandExecutor executor;

        public AdminCommand(@NotNull CommandExecutor executor) {
            this.executor = executor;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
            if (!sender.hasPermission("rankplugin.admin")) {
                sender.sendMessage(format("no_permission"));
                return true;
            }
            return executor.onCommand(sender, command, label, args);
        }
    }

    private class RankCommand implements CommandExecutor {
        @Override
        public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String label, String[] args) {
            if (!(s instanceof Player)) return true;
            Player p = (Player) s;
            s.sendMessage(format("rank").replace("%score%", String.valueOf(scores.get(p.getUniqueId()))));
            return true;
        }
    }

    private class RankScoreCommand implements CommandExecutor {
        @Override
        public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String label, String[] args) {
            if (s instanceof Player) {
                Player p = (Player) s;
                s.sendMessage(String.valueOf(scores.get(p.getUniqueId())));
            }
            return true;
        }
    }

    // Command /ranksystem reload
    private class RankSystemCommand implements CommandExecutor {
        @Override
        public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String label, String[] args) {
            if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
                s.sendMessage(format("usage_ranksystem"));
                return true;
            }
            reloadConfig();
            cfg = getConfig();
            loadSettings();
            s.sendMessage(format("reload"));
            return true;
        }
    }

    // Command /reset <nick> – resets player's score to initialPoints
    private class ResetCommand implements CommandExecutor {
        @Override
        public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String label, String[] args) {
            if (args.length != 1) {
                s.sendMessage(format("reset_usage"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                s.sendMessage(ChatColor.RED + "Игрок не найден.");
                return true;
            }
            UUID targetId = target.getUniqueId();
            scores.put(targetId, initialPoints);
            savePlayer(target);
            s.sendMessage(format("reset_success")
                    .replace("%player%", target.getName())
                    .replace("%initial%", String.valueOf(initialPoints)));
            target.sendMessage(format("reset_target")
                    .replace("%initial%", String.valueOf(initialPoints)));
            return true;
        }
    }

    // --- Message utility ---
    private String format(@NotNull String key) {
        return ChatColor.translateAlternateColorCodes('&', cfg.getString("messages." + key, ""));
    }

    // --- PlaceholderAPI Expansion ---
    public static class RankPlaceholderExpansion extends PlaceholderExpansion {
        private final RankPlugin plugin;
        public RankPlaceholderExpansion(@NotNull RankPlugin plugin) {
            this.plugin = plugin;
        }
        @Override
        public boolean canRegister() {
            return true;
        }
        @Override
        @NotNull
        public String getIdentifier() {
            return "ranksystem";
        }
        @Override
        @NotNull
        public String getAuthor() {
            return String.join(",", plugin.getDescription().getAuthors());
        }
        @Override
        @NotNull
        public String getVersion() {
            return plugin.getDescription().getVersion();
        }
        @Override
        public String onPlaceholderRequest(@NotNull Player player, @NotNull String identifier) {
            if (identifier.equalsIgnoreCase("score")) {
                return String.valueOf(plugin.scores.getOrDefault(player.getUniqueId(), 0));
            }
            return null;
        }
    }
}