package com.gengilys.addon;

import com.gengilys.addon.modules.AutoMapPlace;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class MapAutoPlaceAddon extends MeteorAddon {
    @Override
    public void onInitialize() {
        Modules.get().add(new AutoMapPlace());
    }

    @Override
    public String getPackage() {
        return "com.gengilys.addon";
    }
}
