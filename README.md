# PluginReloader

A legacy CraftBukkit plugin that adds `/plreload` with timeout protection, single-plugin reload, cancellation, and `/plcheck` for hot-loading new jars.

## Commands

- `/plreload` — Reload all plugins sequentially (default 1-second interval)
- `/plreload <pluginName>` — Reload a single plugin immediately (e.g., `/plreload HeroicDeath`)
- `/plreload <number>` — Reload a single plugin by alphabetical index (e.g., `/plreload 3`, `/plreload 25`)
- `/plreload <start-end>` — Reload only a range by alphabetical plugin index (e.g., `/plreload 1-10`, `/plreload 6-17`)
- `/plreload cancel` — Stop a running reload sequence
- `/plcheck` — Scan `plugins/` for new jars and load+enable those not currently loaded
- `/plr` — Reload `PluginReloader` itself from the current jar on disk (detects swapped version)

## Features

- **Single-plugin reload** — Target one plugin: `/plreload HeroicDeath`
- **Sequential reload** — Reloads all plugins one by one to reduce crash risk
- **Per-plugin timeout** — Aborts if a plugin hangs (default 30 seconds, configurable)
- **Cancel command** — Immediately stop a stuck reload sequence
- **Real-time progress** — Shows which plugin is reloading and current position
- **Configuration file** — `plugins/PluginReloader/plreload.properties` for timeout tuning
- **Hot-load new jars** — `/plcheck` attempts to load new plugin jars without restart or `/reload`

## Behavior

**Single-plugin reload:**
- Immediately disables and enables the specified plugin
- Reports success or error with exact class name of the failure
- Broadcasts to all players who reloaded it

**Sequential reload (all plugins):**
- Tries to reload plugins in sequence
- Skips itself (`PluginReloader`) to avoid interrupting the queue
- Default interval is `20` ticks (1 second) between reload attempts
- If a plugin takes longer than `pluginTimeoutSeconds`, reload halts and you can investigate

**Indexed reload (`/plreload <number>` and `/plreload <start-end>`):**
- Uses alphabetical plugin order (case-insensitive)
- If selected index points to `PluginReloader`, it uses safe self-reload behavior

**Plugin check (`/plcheck`):**
- Scans the `plugins/` directory for `.jar` files with `plugin.yml`
- Skips plugins already loaded in memory
- Attempts loading in multiple passes to resolve dependency order
- Reports loaded count and remaining unloaded plugins

## Configuration

On first startup, the plugin creates `plugins/PluginReloader/plreload.properties`:

```properties
pluginTimeoutSeconds=30
defaultReloadIntervalTicks=20
```

- `pluginTimeoutSeconds` controls how long one plugin may take before the sequence is cancelled.
- `defaultReloadIntervalTicks` controls the delay between plugins during sequential/range reloads.
- `20` ticks = `1` second.

## Important Safety Note

Reloading plugins can still be unsafe for some plugins, especially older ones.
This tool reduces load spikes and adds safeguards, but cannot guarantee crash-free behavior.

## Build

```bash
bash ./build.sh
```

Output:

- `build/PluginReloader.jar`

## Install

1. Copy `build/PluginReloader.jar` to your server `plugins/` folder.
2. Restart the server.
3. First run creates config at `plugins/PluginReloader/plreload.properties`.
4. Use `/plreload` as OP (or with `pluginreloader.reload`).

## Permissions

- `pluginreloader.*` (default: op) — Grants all PluginReloader permissions
- `pluginreloader.reload` (default: op) — Use `/plreload`, `/plreload cancel`, and `/plr`
- `pluginreloader.check` (default: op) — Use `/plcheck`
