package com.gengilys.addon;

import com.gengilys.addon.modules.*;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;

public class MapAutoPlaceAddon extends MeteorAddon {
    public static Category CATEGORY;

    @Override
    public String getPackage() {
        return "com.gengilys.addon";
    }

    @Override
    public void onRegisterCategories() {
        CATEGORY = new Category("MapAutoPlace", Items.FILLED_MAP.getDefaultStack());
    }

    @Override
    public void onInitialize() {
        Modules.get().add(new AutoMapPlace());
        Modules.get().add(new MapAdBot());
        Modules.get().add(new MapAdSpam());
    }
}
