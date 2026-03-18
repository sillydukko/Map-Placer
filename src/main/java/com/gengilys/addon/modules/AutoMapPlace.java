package com.gengilys.addon.modules;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.entity.EntityType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.MinecraftClient;

public class AutoMapPlace extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> randomDelay = sgGeneral.add(
        new BoolSetting.Builder()
            .name("random-delay")
            .description("Adds a small random delay before placing to look human.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> randomLook = sgGeneral.add(
        new BoolSetting.Builder()
            .name("random-look")
            .description("Adds a small random offset to where the player looks.")
            .defaultValue(true)
            .build()
    );

    private int pendingFrameId = -1;
    private int delayTicks = 0;

    public AutoMapPlace() {
        super(Categories.Player, "auto-map-place",
            "When you place an item frame, automatically places a map on it.");
    }

    @Override
    public void onActivate() {
        pendingFrameId = -1;
        delayTicks = 0;
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!isActive()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        if (event.packet instanceof EntitySpawnS2CPacket pkt) {
            if (pkt.getEntityType() != EntityType.ITEM_FRAME
                && pkt.getEntityType() != EntityType.GLOW_ITEM_FRAME) return;

            // Only react to frames the player just placed (within 6 blocks)
            Vec3d spawnPos = new Vec3d(pkt.getX(), pkt.getY(), pkt.getZ());
            if (mc.player.getPos().distanceTo(spawnPos) > 6.0) return;

            pendingFrameId = pkt.getEntityId();
            delayTicks = randomDelay.get() ? (2 + (int)(Math.random() * 4)) : 2;
        }
    }

    @EventHandler
    private void onTick(meteordevelopment.meteorclient.events.world.TickEvent.Pre event) {
        if (!isActive() || pendingFrameId < 0) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (delayTicks-- > 0) return;

        Entity entity = mc.world.getEntityById(pendingFrameId);
        pendingFrameId = -1;

        if (!(entity instanceof ItemFrameEntity frame)) return;
        if (!frame.getHeldItemStack().isEmpty()) return;
        if (mc.player.distanceTo(frame) > 5.0) return;

        int mapSlot = findMapInHotbar(mc);
        if (mapSlot < 0) return;

        // Look at the frame
        Vec3d target = frame.getPos().add(0, frame.getHeight() / 2.0, 0);
        Vec3d eye = mc.player.getEyePos();
        Vec3d diff = target.subtract(eye);
        double yaw   = Math.toDegrees(Math.atan2(-diff.x, diff.z));
        double pitch = Math.toDegrees(-Math.atan2(diff.y,
            Math.sqrt(diff.x * diff.x + diff.z * diff.z)));
        double yawOff   = randomLook.get() ? (Math.random() * 4 - 2) : 0;
        double pitchOff = randomLook.get() ? (Math.random() * 3 - 1.5) : 0;
        mc.player.setYaw((float)(yaw + yawOff));
        mc.player.setPitch((float)(pitch + pitchOff));

        // Silent swap → place → swap back
        int original = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(mapSlot);
        mc.interactionManager.interactEntity(mc.player, frame, Hand.MAIN_HAND);
        mc.player.getInventory().setSelectedSlot(original);
    }

    private int findMapInHotbar(MinecraftClient mc) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() == Items.MAP) return i;
        }
        return -1;
    }
}
