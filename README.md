# PluginReloader

A legacy CraftBukkit plugin that adds `/plreload` with timeout protection, single-plugin reload, cancellation, and `/plcheck` for hot-loading new jars.

Current release: `v1.2.2`

## Commands

- `/plreload` — Reload all plugins sequentially (default 1-second interval)
- `/plreload <pluginName>` — Reload a single plugin immediately (e.g., `/plreload HeroicDeath`)
- `/plreload <number>` — Reload a single plugin by alphabetical index (e.g., `/plreload 3`, `/plreload 25`)
- `/plreload <start-end>` — Reload only a range by alphabetical plugin index (e.g., `/plreload 1-10`, `/plreload 6-17`)
- `/plreload cancel` — Stop a running reload sequence
- `/plcheck` — Scan `plugins/` and report plugin sync status (unloaded/modified/disabled/enabled)
- `/pldisable <pluginName>` — Disable one loaded plugin by name
- `/plr` — Reload `PluginReloader` itself from the current jar on disk (detects swapped version)

## Features

- **Single-plugin reload** — Target one plugin: `/plreload HeroicDeath`
- **Sequential reload** — Reloads all plugins one by one to reduce crash risk
- **Per-plugin timeout** — Aborts if a plugin hangs (default 30 seconds, configurable)
- **Cancel command** — Immediately stop a stuck reload sequence
- **Disable command** — Disable one plugin quickly with `/pldisable <pluginName>`
- **Real-time progress** — Shows which plugin is reloading and current position
- **Configuration file** — `plugins/PluginReloader/plreload.properties` for timeout tuning
- **Plugin sync check** — `/plcheck` detects unloaded jars, disabled loaded plugins, and on-disk modifications

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
- Attempts to load jars not currently loaded
- Re-enables already loaded plugins that are currently disabled
- Detects already loaded plugins whose on-disk jar appears modified (version mismatch or changed jar fingerprint since last check)
- Auto-reloads modified plugins by default (`autoReloadModifiedPlugins=true`)
- Disables loaded plugins whose jars were removed from `plugins/`
- Attempts loading in multiple passes to resolve dependency order

Current `/plcheck` summary output fields:
- `Remaining Plugins unloaded` — Candidate jars still not loadable after attempts, plus plugins still listed but already disabled even though their jar is missing from `plugins/` (to avoid double counting with `Plugins disabled`)
- `Plugins modified` — Already loaded plugins that look changed on disk and likely need reload
- `Plugins disabled` — Loaded plugins disabled because their jar was removed
- `Plugins enabled` — Total of newly loaded plugins plus already-loaded plugins that were re-enabled during this check

**Plugin disable (`/pldisable <pluginName>`):**
- Finds a loaded plugin by name (case-insensitive) and disables it
- Rejects disabling `PluginReloader` itself
- Can be re-enabled with `/plreload <pluginName>` (reload does disable+enable)

## Configuration

On first startup, the plugin creates `plugins/PluginReloader/plreload.properties`:

```properties
pluginTimeoutSeconds=30
defaultReloadIntervalTicks=20
autoReloadModifiedPlugins=true
```

- `pluginTimeoutSeconds` controls how long one plugin may take before the sequence is cancelled.
- `defaultReloadIntervalTicks` controls the delay between plugins during sequential/range reloads.
- `autoReloadModifiedPlugins` controls whether `/plcheck` immediately reloads already loaded plugins that are detected as modified on disk.
- `20` ticks = `1` second.

## Important Safety Note

Reloading plugins can still be unsafe for some plugins, especially older ones.
This tool reduces load spikes and adds safeguards, but cannot guarantee crash-free behavior.

## Build

Prerequisite: a CraftBukkit/Spigot API jar must be available at:

- `../libs/craftbukkit-1060 bukkit.jar`

```bash
bash ./build.sh
```

Output:

- `build/PluginReloader.jar`
- release artifact example: `builds/PluginReloader-v1.2.2.jar`

## Install

1. Copy `build/PluginReloader.jar` to your server `plugins/` folder.
2. Restart the server.
3. First run creates config at `plugins/PluginReloader/plreload.properties`.
4. Use `/plreload` as OP (or with `pluginreloader.reload`).

## Permissions

- `pluginreloader.*` (default: op) — Grants all PluginReloader permissions
- `pluginreloader.reload` (default: op) — Use `/plreload`, `/plreload cancel`, and `/plr`
- `pluginreloader.check` (default: op) — Use `/plcheck`
- `pluginreloader.disable` (default: op) — Use `/pldisable <pluginName>`
