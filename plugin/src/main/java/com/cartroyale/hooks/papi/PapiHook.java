package com.cartroyale.hooks.papi;

import com.cartroyale.CartRoyale;

/**
 * The only door to PlaceholderAPI. CartRoyale calls {@link #register} after checking the plugin is
 * enabled, so no PlaceholderAPI class is ever loaded on a server without it (see the soft-depend
 * note: a missing optional type in a commonly loaded class kills onEnable).
 */
public final class PapiHook {

    private PapiHook() {
    }

    public static boolean register(CartRoyale plugin) {
        return new CartZoneExpansion(plugin).register();
    }
}
