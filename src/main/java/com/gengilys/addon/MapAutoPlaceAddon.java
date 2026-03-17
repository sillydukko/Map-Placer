package com.gengilys.addon;

import com.gengilys.addon.modules.AutoMapPlace;
import meteordevelopment.meteor.api.MeteorAddon;
import meteordevelopment.meteor.systems.modules.Modules;

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
