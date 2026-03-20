package com.patschgo.pluginreloader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public class PluginReloaderPlugin extends JavaPlugin {

    private boolean reloadInProgress = false;
    private int reloadTaskId = -1;
    private int pluginTimeoutSeconds = 30;
    private int defaultReloadIntervalTicks = 20;
    private Properties config = new Properties();
    private File configFile;
    private final Set<String> lastPresentPluginNames = new HashSet<String>();
    private boolean hasRunPlcheck = false;

    @Override
    public void onEnable() {
        loadConfig();
        System.out.println("[PluginReloader] enabled. Timeout: " + pluginTimeoutSeconds
            + "s per plugin. Default reload interval: " + defaultReloadIntervalTicks + " ticks.");
    }

    @Override
    public void onDisable() {
        if (reloadTaskId != -1) {
            getServer().getScheduler().cancelTask(reloadTaskId);
            reloadTaskId = -1;
        }
        System.out.println("[PluginReloader] disabled.");
    }

    @Override
    public boolean onCommand(final CommandSender sender, Command command, String label, String[] args) {
        if ("plr".equalsIgnoreCase(command.getName())) {
            if (!canUseSelfReload(sender)) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                return true;
            }

            if (args.length != 0) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /plr");
                return true;
            }

            reloadSelf(sender);
            return true;
        }

        if ("plcheck".equalsIgnoreCase(command.getName())) {
            if (!canUseCheck(sender)) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                return true;
            }

            if (args.length != 0) {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /plcheck");
                return true;
            }

            runPluginCheck(sender);
            return true;
        }

        if (!"plreload".equalsIgnoreCase(command.getName())) {
            return false;
        }

        if (!canUseReload(sender)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length > 0 && "cancel".equalsIgnoreCase(args[0])) {
            if (!reloadInProgress) {
                sender.sendMessage(ChatColor.YELLOW + "No reload sequence is running.");
                return true;
            }
            cancelReload(sender);
            return true;
        }

        if (args.length > 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /plreload [arg]");
            sender.sendMessage(ChatColor.YELLOW + "  /plreload                 — Reload all plugins");
            sender.sendMessage(ChatColor.YELLOW + "  /plreload <number>        — Reload one plugin by alphabetical index");
            sender.sendMessage(ChatColor.YELLOW + "  /plreload <start-end>     — Reload plugin range by alphabetical order");
            sender.sendMessage(ChatColor.YELLOW + "  /plreload <pluginName>    — Reload single plugin (e.g., HeroicDeath)");
            sender.sendMessage(ChatColor.YELLOW + "  /plreload cancel          — Cancel running reload");
            return true;
        }

        if (args.length == 1) {
            String arg = args[0];

            if (isRangeArg(arg)) {
                if (reloadInProgress) {
                    sender.sendMessage(ChatColor.YELLOW + "A plugin reload sequence is already running. Use /plreload cancel to stop.");
                    return true;
                }

                int[] range = parseRangeArg(arg);
                if (range == null) {
                    sender.sendMessage(ChatColor.RED + "Invalid range. Use /plreload <start-end>, for example /plreload 1-10.");
                    return true;
                }

                startRangeReload(sender, range[0], range[1], defaultReloadIntervalTicks);
                return true;
            }
            
            try {
                int pluginIndex = Integer.parseInt(arg);
                if (pluginIndex < 1) {
                    sender.sendMessage(ChatColor.RED + "Plugin index must be 1 or greater.");
                    return true;
                }

                if (reloadPluginByAlphabeticalIndex(sender, pluginIndex)) {
                    return true;
                }

                sender.sendMessage(ChatColor.RED + "Plugin index out of bounds.");
                return true;
            } catch (NumberFormatException ex) {
                if (searchAndReloadPlugin(sender, arg)) {
                    return true;
                }
                sender.sendMessage(ChatColor.RED + "Plugin '" + arg + "' not found. Check /plugins for exact name.");
                return true;
            }
        }

        if (reloadInProgress) {
            sender.sendMessage(ChatColor.YELLOW + "A plugin reload sequence is already running. Use /plreload cancel to stop.");
            return true;
        }

        startSequentialReload(sender, defaultReloadIntervalTicks);
        return true;
    }

    private boolean reloadPluginByAlphabeticalIndex(CommandSender sender, int pluginIndex) {
        PluginManager pluginManager = getServer().getPluginManager();
        Plugin[] installed = pluginManager.getPlugins();
        List<Plugin> sorted = new ArrayList<Plugin>();

        for (int i = 0; i < installed.length; i++) {
            if (installed[i] != null) {
                sorted.add(installed[i]);
            }
        }

        Collections.sort(sorted, new Comparator<Plugin>() {
            @Override
            public int compare(Plugin a, Plugin b) {
                String an = a.getDescription().getName();
                String bn = b.getDescription().getName();
                return an.compareToIgnoreCase(bn);
            }
        });

        if (pluginIndex > sorted.size()) {
            sender.sendMessage(ChatColor.RED + "Plugin index out of bounds. Max index is " + sorted.size() + ".");
            return false;
        }

        Plugin target = sorted.get(pluginIndex - 1);
        String name = target.getDescription().getName();
        if (name.equalsIgnoreCase(getDescription().getName())) {
            sender.sendMessage(ChatColor.YELLOW + "Index " + pluginIndex + " is PluginReloader. Using safe self-reload.");
            reloadSelf(sender);
            return true;
        }

        return searchAndReloadPlugin(sender, name);
    }

    private boolean isRangeArg(String arg) {
        if (arg == null) {
            return false;
        }
        return arg.matches("\\d+\\s*-\\s*\\d+");
    }

    private int[] parseRangeArg(String arg) {
        if (!isRangeArg(arg)) {
            return null;
        }

        String[] parts = arg.split("-");
        if (parts.length != 2) {
            return null;
        }

        try {
            int start = Integer.parseInt(parts[0].trim());
            int end = Integer.parseInt(parts[1].trim());
            if (start < 1 || end < 1 || end < start) {
                return null;
            }
            return new int[] { start, end };
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean searchAndReloadPlugin(final CommandSender sender, String pluginName) {
        PluginManager pluginManager = getServer().getPluginManager();
        Plugin[] installed = pluginManager.getPlugins();

        Plugin targetPlugin = null;
        for (Plugin plugin : installed) {
            if (plugin != null && plugin.getDescription().getName().equalsIgnoreCase(pluginName)) {
                targetPlugin = plugin;
                break;
            }
        }

        if (targetPlugin == null) {
            return false;
        }

        String name = targetPlugin.getDescription().getName();
        sender.sendMessage(ChatColor.GRAY + "Reloading " + name + "...");

        try {
            if (targetPlugin.isEnabled()) {
                pluginManager.disablePlugin(targetPlugin);
            }

            pluginManager.enablePlugin(targetPlugin);
            sender.sendMessage(ChatColor.GREEN + "Successfully reloaded " + name + ".");
            getServer().broadcastMessage(ChatColor.GREEN + name + " was reloaded by " + getSenderName(sender) + ".");
        } catch (Throwable t) {
            sender.sendMessage(ChatColor.RED + "Failed to reload " + name + ": " + t.getClass().getSimpleName());
            System.out.println("[PluginReloader] Failed to reload " + name + ": " + t.getMessage());
        }

        return true;
    }

    private String getSenderName(CommandSender sender) {
        if (sender instanceof Player) {
            return ((Player) sender).getName();
        }
        return "Console";
    }

    private void startSequentialReload(final CommandSender sender, int intervalTicks) {
        final PluginManager pluginManager = getServer().getPluginManager();
        Plugin[] installed = pluginManager.getPlugins();
        final List<Plugin> queue = new ArrayList<Plugin>();

        String selfName = getDescription().getName();
        for (int i = 0; i < installed.length; i++) {
            Plugin plugin = installed[i];
            if (plugin == null) {
                continue;
            }
            if (selfName.equalsIgnoreCase(plugin.getDescription().getName())) {
                continue;
            }
            queue.add(plugin);
        }

        if (queue.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No other plugins were found to reload.");
            return;
        }

        reloadInProgress = true;
        final int total = queue.size();
        final int[] index = new int[] { 0 };

        getServer().broadcastMessage(ChatColor.GOLD + "Starting sequential plugin reload (" + total + " plugins)...");

        reloadTaskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override
            public void run() {
                if (index[0] >= total) {
                    finishReload(sender, total);
                    return;
                }

                Plugin plugin = queue.get(index[0]);
                int position = index[0] + 1;
                index[0]++;
                reloadSinglePluginWithTimeout(pluginManager, plugin, sender, position, total);
            }
        }, 1L, intervalTicks);
    }

    private void startRangeReload(final CommandSender sender, int startIndex, int endIndex, int intervalTicks) {
        final PluginManager pluginManager = getServer().getPluginManager();
        Plugin[] installed = pluginManager.getPlugins();
        final List<Plugin> queue = new ArrayList<Plugin>();
        List<Plugin> sortedPlugins = new ArrayList<Plugin>();

        for (int i = 0; i < installed.length; i++) {
            if (installed[i] != null) {
                sortedPlugins.add(installed[i]);
            }
        }

        Collections.sort(sortedPlugins, new Comparator<Plugin>() {
            @Override
            public int compare(Plugin a, Plugin b) {
                String an = a.getDescription().getName();
                String bn = b.getDescription().getName();
                return an.compareToIgnoreCase(bn);
            }
        });

        int maxIndex = sortedPlugins.size();
        if (startIndex > maxIndex) {
            sender.sendMessage(ChatColor.RED + "Range start is out of bounds. Max plugin index is " + maxIndex + ".");
            return;
        }

        int effectiveEnd = endIndex;
        if (effectiveEnd > maxIndex) {
            effectiveEnd = maxIndex;
            sender.sendMessage(ChatColor.YELLOW + "Range end was clamped to " + effectiveEnd + " (max plugin index).");
        }

        String selfName = getDescription().getName();
        boolean skippedSelf = false;
        for (int idx = startIndex; idx <= effectiveEnd; idx++) {
            Plugin plugin = sortedPlugins.get(idx - 1);
            if (selfName.equalsIgnoreCase(plugin.getDescription().getName())) {
                skippedSelf = true;
                continue;
            }
            queue.add(plugin);
        }

        if (skippedSelf) {
            sender.sendMessage(ChatColor.YELLOW + "Skipped PluginReloader in selected range.");
        }

        if (queue.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No reloadable plugins found in selected range " + startIndex + "-" + effectiveEnd + ".");
            return;
        }

        reloadInProgress = true;
        final int total = queue.size();
        final int[] index = new int[] { 0 };

        getServer().broadcastMessage(ChatColor.GOLD + "Starting range plugin reload (" + startIndex + "-" + effectiveEnd
                + ", " + total + " plugins)...");

        reloadTaskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override
            public void run() {
                if (index[0] >= total) {
                    finishReload(sender, total);
                    return;
                }

                Plugin plugin = queue.get(index[0]);
                int position = index[0] + 1;
                index[0]++;
                reloadSinglePluginWithTimeout(pluginManager, plugin, sender, position, total);
            }
        }, 1L, intervalTicks);
    }

    private void reloadSinglePluginWithTimeout(final PluginManager pluginManager, final Plugin plugin, 
            final CommandSender sender, final int position, final int total) {
        final String pluginName = plugin.getDescription().getName();
        final long startTime = System.currentTimeMillis();
        final int timeoutMs = pluginTimeoutSeconds * 1000;

        sender.sendMessage(ChatColor.GRAY + "[" + position + "/" + total + "] Reloading " + pluginName + "...");

        final int[] watchTaskId = new int[] { -1 };
        watchTaskId[0] = getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override
            public void run() {
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > timeoutMs) {
                    if (watchTaskId[0] != -1) {
                        getServer().getScheduler().cancelTask(watchTaskId[0]);
                        watchTaskId[0] = -1;
                    }
                    sender.sendMessage(ChatColor.RED + "[" + position + "/" + total + "] " + pluginName 
                            + " reload TIMEOUT (" + pluginTimeoutSeconds + "s exceeded). Cancelling sequence.");
                    getServer().broadcastMessage(ChatColor.RED + "PluginReloader: " + pluginName + " timeout - halting reload.");
                    cancelReload(sender);
                }
            }
        }, 0L, 10L);

        try {
            if (plugin.isEnabled()) {
                pluginManager.disablePlugin(plugin);
            }

            pluginManager.enablePlugin(plugin);
            sender.sendMessage(ChatColor.GREEN + "[" + position + "/" + total + "] Reloaded " + pluginName + ".");
        } catch (Throwable t) {
            sender.sendMessage(ChatColor.RED + "[" + position + "/" + total + "] Failed to reload " + pluginName 
                    + ": " + t.getClass().getSimpleName());
            System.out.println("[PluginReloader] Failed to reload " + pluginName + ": " + t.getMessage());
        } finally {
            if (watchTaskId[0] != -1) {
                getServer().getScheduler().cancelTask(watchTaskId[0]);
            }
        }
    }

    private void cancelReload(CommandSender sender) {
        if (reloadTaskId != -1) {
            getServer().getScheduler().cancelTask(reloadTaskId);
            reloadTaskId = -1;
        }
        reloadInProgress = false;
        if (sender != null) {
            sender.sendMessage(ChatColor.YELLOW + "Plugin reload sequence cancelled.");
        }
        getServer().broadcastMessage(ChatColor.YELLOW + "Plugin reload sequence was cancelled.");
    }

    private void finishReload(CommandSender sender, int total) {
        if (reloadTaskId != -1) {
            getServer().getScheduler().cancelTask(reloadTaskId);
            reloadTaskId = -1;
        }
        reloadInProgress = false;
        getServer().broadcastMessage(ChatColor.GREEN + "Sequential plugin reload finished (" + total + " attempted).");
    }

    private boolean canUseReload(CommandSender sender) {
        if (sender.hasPermission("pluginreloader.reload")) {
            return true;
        }

        if (sender instanceof Player) {
            return ((Player) sender).isOp();
        }

        return true;
    }

    private boolean canUseCheck(CommandSender sender) {
        if (sender.hasPermission("pluginreloader.check")) {
            return true;
        }

        if (sender instanceof Player) {
            return ((Player) sender).isOp();
        }

        return true;
    }

    private boolean canUseSelfReload(CommandSender sender) {
        if (sender.hasPermission("pluginreloader.reload")) {
            return true;
        }

        if (sender instanceof Player) {
            return ((Player) sender).isOp();
        }

        return true;
    }

    private void reloadSelf(CommandSender sender) {
        final PluginManager pluginManager = getServer().getPluginManager();
        String selfName = getDescription().getName();

        PluginCandidate selfCandidate = findPluginJarCandidate(selfName);
        if (selfCandidate == null || selfCandidate.file == null) {
            sender.sendMessage(ChatColor.RED + "Could not find PluginReloader jar in plugins folder.");
            return;
        }

        String runningVersion = getDescription().getVersion();
        String fileVersion = selfCandidate.version == null ? "unknown" : selfCandidate.version;
        sender.sendMessage(ChatColor.GOLD + "PluginReloader jar version on disk: " + fileVersion + ". Running version: "
            + runningVersion + ".");
        if (!runningVersion.equals(fileVersion)) {
            sender.sendMessage(ChatColor.GOLD + "Detected PluginReloader version change: running " + runningVersion
                    + " -> jar " + fileVersion + ".");
        }
        sender.sendMessage(ChatColor.YELLOW + "To change the running version, restart the server/plugins so the new jar is loaded.");

        sender.sendMessage(ChatColor.GRAY + "Running safe self-reload...");
        getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            @Override
            public void run() {
                Plugin currentlyLoaded = pluginManager.getPlugin(selfName);
                if (currentlyLoaded == null) {
                    sender.sendMessage(ChatColor.RED + "PluginReloader instance not found for self-reload.");
                    return;
                }

                try {
                    pluginManager.disablePlugin(currentlyLoaded);
                    pluginManager.enablePlugin(currentlyLoaded);
                    sender.sendMessage(ChatColor.GREEN + "PluginReloader reloaded successfully. Active version: "
                            + currentlyLoaded.getDescription().getVersion());
                } catch (Throwable t) {
                    sender.sendMessage(ChatColor.RED + "Self-reload failed: " + t.getClass().getSimpleName());
                    tryReEnableExisting(pluginManager, currentlyLoaded);
                }
            }
        }, 1L);
    }

    private void tryReEnableExisting(PluginManager pluginManager, Plugin existing) {
        if (existing == null) {
            return;
        }

        try {
            if (!existing.isEnabled()) {
                pluginManager.enablePlugin(existing);
            }
        } catch (Throwable ignored) {
            // no-op
        }
    }

    private void runPluginCheck(CommandSender sender) {
        PluginManager pluginManager = getServer().getPluginManager();
        File pluginsDir = new File("plugins");

        if (!pluginsDir.exists() || !pluginsDir.isDirectory()) {
            sender.sendMessage(ChatColor.RED + "plugins folder not found.");
            return;
        }

        File[] files = pluginsDir.listFiles();
        if (files == null) {
            sender.sendMessage(ChatColor.RED + "Could not read plugins folder.");
            return;
        }

        List<PluginCandidate> candidates = new ArrayList<PluginCandidate>();
        Set<String> presentPluginNames = new HashSet<String>();
        int reEnabledCount = 0;
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (file == null || !file.isFile() || !file.getName().toLowerCase().endsWith(".jar")) {
                continue;
            }

            PluginCandidate candidate = readCandidate(file);
            if (candidate == null) {
                continue;
            }

            presentPluginNames.add(candidate.name.toLowerCase());

            Plugin existing = pluginManager.getPlugin(candidate.name);
            if (existing != null) {
                if (!existing.isEnabled()) {
                    try {
                        pluginManager.enablePlugin(existing);
                        reEnabledCount++;
                        sender.sendMessage(ChatColor.GREEN + "Re-enabled: " + existing.getDescription().getName());
                    } catch (Throwable t) {
                        sender.sendMessage(ChatColor.RED + "Failed to re-enable " + existing.getDescription().getName() + ": "
                                + t.getClass().getSimpleName());
                    }
                }
                continue;
            }

            candidates.add(candidate);
        }

        int addedSinceLastCheck = 0;
        int removedSinceLastCheck = 0;
        if (hasRunPlcheck) {
            for (String name : presentPluginNames) {
                if (!lastPresentPluginNames.contains(name)) {
                    addedSinceLastCheck++;
                }
            }
            for (String name : lastPresentPluginNames) {
                if (!presentPluginNames.contains(name)) {
                    removedSinceLastCheck++;
                }
            }
        }

        int disabledRemovedCount = 0;
        Plugin[] loadedPlugins = pluginManager.getPlugins();
        String selfName = getDescription().getName();
        for (int i = 0; i < loadedPlugins.length; i++) {
            Plugin loadedPlugin = loadedPlugins[i];
            if (loadedPlugin == null) {
                continue;
            }

            String loadedName = loadedPlugin.getDescription().getName();
            if (loadedName == null || loadedName.equalsIgnoreCase(selfName)) {
                continue;
            }

            if (!presentPluginNames.contains(loadedName.toLowerCase()) && loadedPlugin.isEnabled()) {
                try {
                    pluginManager.disablePlugin(loadedPlugin);
                    disabledRemovedCount++;
                    sender.sendMessage(ChatColor.YELLOW + "Disabled removed plugin: " + loadedName);
                } catch (Throwable t) {
                    sender.sendMessage(ChatColor.RED + "Failed to disable removed plugin " + loadedName + ": "
                            + t.getClass().getSimpleName());
                }
            }
        }

        if (candidates.isEmpty()) {
            sender.sendMessage(ChatColor.GREEN + "No new plugin jars to load.");
            sender.sendMessage(ChatColor.GOLD + "Plugin check complete. Re-enabled: " + reEnabledCount
                    + ". Disabled removed plugins: " + disabledRemovedCount
                    + ". Added since last check: " + addedSinceLastCheck
                    + ". Removed since last check: " + removedSinceLastCheck + ".");
            lastPresentPluginNames.clear();
            lastPresentPluginNames.addAll(presentPluginNames);
            hasRunPlcheck = true;
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "Found " + candidates.size() + " unloaded plugin(s). Trying to load...");

        int loadedCount = 0;
        Set<String> failed = new HashSet<String>();

        // Multi-pass loading helps when dependencies are loaded earlier in the same run.
        for (int pass = 0; pass < 6; pass++) {
            boolean progress = false;

            for (int i = 0; i < candidates.size(); i++) {
                PluginCandidate candidate = candidates.get(i);
                if (candidate == null || candidate.attempted || failed.contains(candidate.name)) {
                    continue;
                }

                if (pluginManager.getPlugin(candidate.name) != null) {
                    candidate.attempted = true;
                    continue;
                }

                try {
                    Plugin loaded = pluginManager.loadPlugin(candidate.file);
                    if (loaded != null) {
                        pluginManager.enablePlugin(loaded);
                        sender.sendMessage(ChatColor.GREEN + "Loaded: " + loaded.getDescription().getName());
                        loadedCount++;
                        candidate.attempted = true;
                        progress = true;
                    } else {
                        failed.add(candidate.name);
                        sender.sendMessage(ChatColor.RED + "Failed to load " + candidate.name + " (unknown error).");
                    }
                } catch (InvalidDescriptionException ex) {
                    failed.add(candidate.name);
                    sender.sendMessage(ChatColor.RED + "Invalid description: " + candidate.name);
                } catch (InvalidPluginException ex) {
                    failed.add(candidate.name);
                    sender.sendMessage(ChatColor.RED + "Invalid plugin: " + candidate.name + " (dependencies or jar issue).");
                } catch (Throwable t) {
                    failed.add(candidate.name);
                    sender.sendMessage(ChatColor.RED + "Error loading " + candidate.name + ": " + t.getClass().getSimpleName());
                }
            }

            if (!progress) {
                break;
            }
        }

        int stillUnloaded = 0;
        for (int i = 0; i < candidates.size(); i++) {
            PluginCandidate candidate = candidates.get(i);
            if (pluginManager.getPlugin(candidate.name) == null) {
                stillUnloaded++;
            }
        }

        sender.sendMessage(ChatColor.GOLD + "Plugin check complete. Loaded " + loadedCount
                + ". Re-enabled: " + reEnabledCount
                + ". Remaining unloaded: " + stillUnloaded
                + ". Disabled removed plugins: " + disabledRemovedCount
                + ". Added since last check: " + addedSinceLastCheck
                + ". Removed since last check: " + removedSinceLastCheck + ".");
        if (stillUnloaded > 0) {
            sender.sendMessage(ChatColor.YELLOW + "Some plugins may require missing dependencies or a full restart.");
        }

        lastPresentPluginNames.clear();
        lastPresentPluginNames.addAll(presentPluginNames);
        hasRunPlcheck = true;
    }

    private PluginCandidate readCandidate(File file) {
        JarFile jar = null;
        java.io.InputStream in = null;

        try {
            jar = new JarFile(file);
            JarEntry entry = jar.getJarEntry("plugin.yml");
            if (entry == null) {
                return null;
            }

            in = jar.getInputStream(entry);
            PluginDescriptionFile description = new PluginDescriptionFile(in);
            if (description == null || description.getName() == null) {
                return null;
            }

            return new PluginCandidate(description.getName(), description.getVersion(), file);
        } catch (Exception ex) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // no-op
                }
            }

            if (jar != null) {
                try {
                    jar.close();
                } catch (Exception ignored) {
                    // no-op
                }
            }
        }
    }

    private PluginCandidate findPluginJarCandidate(String pluginName) {
        if (pluginName == null) {
            return null;
        }

        File pluginsDir = new File("plugins");
        if (!pluginsDir.exists() || !pluginsDir.isDirectory()) {
            return null;
        }

        File[] files = pluginsDir.listFiles();
        if (files == null) {
            return null;
        }

        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (file == null || !file.isFile() || !file.getName().toLowerCase().endsWith(".jar")) {
                continue;
            }

            PluginCandidate candidate = readCandidate(file);
            if (candidate != null && pluginName.equalsIgnoreCase(candidate.name)) {
                return candidate;
            }
        }

        return null;
    }

    private static class PluginCandidate {
        private final String name;
        private final String version;
        private final File file;
        private boolean attempted;

        private PluginCandidate(String name, String version, File file) {
            this.name = name;
            this.version = version;
            this.file = file;
            this.attempted = false;
        }
    }

    private void loadConfig() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        configFile = new File(dataFolder, "plreload.properties");
        if (!configFile.exists()) {
            writeDefaultConfig();
        }

        FileInputStream in = null;
        try {
            in = new FileInputStream(configFile);
            config.load(in);
            String timeoutStr = config.getProperty("pluginTimeoutSeconds", "30");
            String intervalStr = config.getProperty("defaultReloadIntervalTicks", "20");
            try {
                pluginTimeoutSeconds = Integer.parseInt(timeoutStr);
                if (pluginTimeoutSeconds < 1) {
                    pluginTimeoutSeconds = 30;
                }
            } catch (NumberFormatException ex) {
                pluginTimeoutSeconds = 30;
            }

            try {
                defaultReloadIntervalTicks = Integer.parseInt(intervalStr);
                if (defaultReloadIntervalTicks < 1) {
                    defaultReloadIntervalTicks = 20;
                }
            } catch (NumberFormatException ex) {
                defaultReloadIntervalTicks = 20;
            }
        } catch (IOException ex) {
            System.out.println("[PluginReloader] Failed to load config: " + ex.getMessage());
            pluginTimeoutSeconds = 30;
            defaultReloadIntervalTicks = 20;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void writeDefaultConfig() {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(configFile);
            StringBuilder content = new StringBuilder();
            content.append("# PluginReloader configuration\n");
            content.append("# pluginTimeoutSeconds = maximum seconds allowed for one plugin reload\n");
            content.append("pluginTimeoutSeconds=30\n");
            content.append("\n");
            content.append("# defaultReloadIntervalTicks = delay between plugins during sequential/range reloads\n");
            content.append("# Tick meaning: 20 = 1 second, 40 = 2 seconds, 10 = 0.5 seconds\n");
            content.append("defaultReloadIntervalTicks=20\n");
            out.write(content.toString().getBytes("UTF-8"));
        } catch (IOException ex) {
            System.out.println("[PluginReloader] Failed to write config: " + ex.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
