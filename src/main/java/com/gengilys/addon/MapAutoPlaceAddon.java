package com.gengilys.addon;

import com.gengilys.addon.modules.*;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class MapAutoPlaceAddon extends MeteorAddon {

    public static final Category CATEGORY = new Category("Map Art");

    @Override
    public void onInitialize() {
        Modules.get().add(new AutoMapPlace());
        Modules.get().add(new MapPlacer());
        Modules.get().add(new MapAura());
        Modules.get().add(new AutoDick());
        Modules.get().add(new MapAdBot());
        Modules.get().add(new MapAdSpam());
    }

    @Override
    public String getPackage() {
        return "com.gengilys.addon";
    }
}
