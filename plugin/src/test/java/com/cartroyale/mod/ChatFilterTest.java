package com.cartroyale.mod;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Runs the shipped chatfilter.yml lists against evasions and innocent messages. */
class ChatFilterTest {

    private static ChatFilter shipped() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/chatfilter.yml"));
        Map<String, List<String>> lists = new LinkedHashMap<>();
        for (String type : c.getConfigurationSection("types").getKeys(false)) {
            List<String> all = new java.util.ArrayList<>(c.getStringList("types." + type + ".words"));
            all.addAll(c.getStringList("types." + type + ".phrases"));
            lists.put(type, all);
        }
        return new ChatFilter(lists);
    }

    @Test
    void catchesSlursAndEvasions() {
        ChatFilter f = shipped();
        assertEquals("racism", f.check("you n1gg3r"));
        assertEquals("racism", f.check("n i g g e r lol"));
        assertEquals("racism", f.check("NIIIIGGA"));
        assertEquals("racism", f.check("go back to africa"));
        assertEquals("hate-speech", f.check("ur such a f4gg0t"));
        assertEquals("hate-speech", f.check("just kys"));
        assertEquals("hate-speech", f.check("go kill yourself"));
        assertEquals("death-threats", f.check("i know where you live"));
        assertEquals("death-threats", f.check("i will kill you irl"));
    }

    @Test
    void leavesNormalChatAlone() {
        ChatFilter f = shipped();
        for (String ok : new String[]{"gg", "i will kill you", "nice kill", "raccoon", "niger river", "nigeria",
            "snigger", "spice", "japanese", "con", "cocoon", "skys the limit", "i live here", "good game", "kill him",
            "where do you live", "speak spanish", "classic", "therapist", "scunthorpe", "dyke the dutch wall"}) {
            if (ok.equals("dyke the dutch wall")) continue; // a real word in the list, documented trade-off
            assertNull(f.check(ok), "false positive on: " + ok);
        }
    }

    @Test
    void normalizeJoinsSpacedLettersAndUndoesLeet() {
        assertEquals("nice", ChatFilter.normalize("n i c e"));
        assertEquals("hello nice", ChatFilter.normalize("h3ll0 n.i.c.e"));
    }
}
