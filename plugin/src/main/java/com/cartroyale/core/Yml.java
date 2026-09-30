package com.cartroyale.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** A data-folder YAML file that is copied from the jar on first run and can be saved back. */
public final class Yml {

    private final JavaPlugin plugin;
    private final String name;
    private final File file;
    private YamlConfiguration cfg;

    public Yml(JavaPlugin plugin, String name, boolean copyDefault) {
        this.plugin = plugin;
        this.name = name;
        this.file = new File(plugin.getDataFolder(), name);
        if (!file.exists() && copyDefault && plugin.getResource(name) != null) plugin.saveResource(name, false);
        reload();
    }

    public void reload() {
        cfg = YamlConfiguration.loadConfiguration(file);
        var in = plugin.getResource(name);
        if (in != null) cfg.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    public YamlConfiguration get() {
        return cfg;
    }

    public File file() {
        return file;
    }

    public void save() {
        try {
            file.getParentFile().mkdirs();
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save " + name + ": " + e.getMessage());
        }
    }
}
