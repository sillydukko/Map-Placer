package com.gengilys.addon;

import com.gengilys.addon.modules.*;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class MapAutoPlaceAddon extends MeteorAddon {
    public static final Category CATEGORY = Categories.MISC;

    @Override
    public String getPackage() {
        return "com.gengilys.addon";
    }

    @Override
    public void onInitialize() {
        Modules.get().add(new AutoMapPlace());
        Modules.get().add(new MapAdBot());
        Modules.get().add(new MapAdSpam());
    }
}
