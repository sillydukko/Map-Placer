package com.gengilys.addon.modules;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

public class AutoMapPlace extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> placementDelay = sgGeneral.add(
        new IntSetting.Builder()
            .name("placement-delay")
            .description("Ticks to wait before placing a map on a new frame. 20 = 1 second.")
            .defaultValue(3)
            .min(1)
            .sliderMax(40)
            .build()
    );

    private final Setting<Boolean> randomDelay = sgGeneral.add(
        new BoolSetting.Builder()
            .name("random-delay")
            .description("Adds a small random extra delay before placing to look human.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> randomLook = sgGeneral.add(
        new BoolSetting.Builder()
            .name("random-look")
            .description("Adds a small random offset to the silent look direction.")
            .defaultValue(true)
            .build()
    );

    private int pendingFrameId = -1;
    private int delayTicks = 0;

    public AutoMapPlace() {
        super(Categories.Player, "auto-map-place",
            "When you place an item frame, automatically places a map on it silently.");
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

            Vec3d spawnPos = new Vec3d(pkt.getX(), pkt.getY(), pkt.getZ());
            if (mc.player.getPos().distanceTo(spawnPos) > 6.0) return;

            pendingFrameId = pkt.getEntityId();
            int base = placementDelay.get();
            delayTicks = randomDelay.get() ? base + (int)(Math.random() * 4) : base;
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

        // Calculate look direction toward the frame
        Vec3d target = frame.getPos().add(0, frame.getHeight() / 2.0, 0);
        Vec3d eye = mc.player.getEyePos();
        Vec3d diff = target.subtract(eye);
        double yaw   = Math.toDegrees(Math.atan2(-diff.x, diff.z));
        double pitch = Math.toDegrees(-Math.atan2(diff.y,
            Math.sqrt(diff.x * diff.x + diff.z * diff.z)));
        double yawOff   = randomLook.get() ? (Math.random() * 4 - 2)   : 0;
        double pitchOff = randomLook.get() ? (Math.random() * 3 - 1.5) : 0;
        float fakeYaw   = (float)(yaw + yawOff);
        float fakePitch = (float)(pitch + pitchOff);

        ClientPlayerEntity player = mc.player;
        int original = player.getInventory().getSelectedSlot();

        // Save real rotation
        float realYaw   = player.getYaw();
        float realPitch = player.getPitch();

        // Silently rotate, interact, then restore — player visually never moves
        player.setYaw(fakeYaw);
        player.setPitch(fakePitch);
        player.getInventory().setSelectedSlot(mapSlot);

        mc.interactionManager.interactEntity(player, frame, Hand.MAIN_HAND);

        // Restore everything immediately
        player.setYaw(realYaw);
        player.setPitch(realPitch);
        player.getInventory().setSelectedSlot(original);
    }

    /** Find first hotbar slot (0-8) with a blank or filled map. */
    private int findMapInHotbar(MinecraftClient mc) {
        var inv = mc.player.getInventory();
        // Prefer blank maps first
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() == Items.MAP) return i;
        }
        // Fall back to any filled map
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() instanceof FilledMapItem) return i;
        }
        return -1;
    }
}
