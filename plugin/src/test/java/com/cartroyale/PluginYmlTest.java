package com.cartroyale;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Every permission's children must be node -> true/false; a broken one is silently dropped by the server. */
class PluginYmlTest {

    @Test
    void permissionsAreWellFormed() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File("src/main/resources/plugin.yml"));
        var perms = y.getConfigurationSection("permissions");
        assertNotNull(perms);
        for (String node : perms.getKeys(false)) {
            var sec = perms.getConfigurationSection(node);
            assertNotNull(sec, node + " is not a section");
            var children = sec.getConfigurationSection("children");
            if (children == null) continue;
            for (Map.Entry<String, Object> c : children.getValues(false).entrySet()) {
                assertInstanceOf(Boolean.class, c.getValue(), node + " child " + c.getKey() + " is not true/false");
            }
        }
    }
}
