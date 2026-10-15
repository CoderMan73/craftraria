package com.craftraria.client.ui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Optional Mod Menu entry point: puts the settings screen behind Mod Menu's config button when Mod
 * Menu is installed.
 *
 * <p>Mod Menu is a compile-only dependency, so this class is only ever loaded when Mod Menu is
 * present and the player asks for it. Without Mod Menu, {@code /craftraria config} opens the exact
 * same screen, which is why the mod list greys the button instead of failing.
 */
public class CraftrariaModMenuApi implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return CraftrariaConfigScreen::create;
    }
}
