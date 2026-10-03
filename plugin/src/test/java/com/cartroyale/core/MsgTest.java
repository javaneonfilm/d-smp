package com.cartroyale.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MsgTest {

    @Test
    void smallCapsKeepsDigitsAndSymbols() {
        assertEquals("ᴢᴏɴᴇ 1/4 · ʀ520", Msg.smallCaps("Zone 1/4 · r520"));
        assertEquals("ᴄᴀʀᴛᴢᴏɴᴇ.ᴠɪᴘ", Msg.smallCaps("cartzone.vip"));
    }
}
