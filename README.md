# PluginReloader

Legacy CraftBukkit plugin source for safe plugin reload workflows.

Project files live in `PluginReloader/`:

- `PluginReloader/src/` - Java source
- `PluginReloader/plugin.yml` - Bukkit plugin metadata
- `PluginReloader/build.sh` - build script
- `PluginReloader/README.md` - plugin usage and command documentation

## Build

```bash
cd PluginReloader
bash ./build.sh
```

Build output:

- `PluginReloader/build/PluginReloader.jar`

The `libs/` folder is intentionally excluded from version control because it contains local server jars used only for compilation.