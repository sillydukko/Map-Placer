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
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

public class AutoMapPlace extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> placementDelay = sgGeneral.add(
        new IntSetting.Builder()
            .name("placement-delay")
            .description("Ticks to wait before placing. Increase for strict servers. 20 = 1s.")
            .defaultValue(10)
            .min(1)
            .sliderMax(60)
            .build()
    );

    private final Setting<Boolean> randomDelay = sgGeneral.add(
        new BoolSetting.Builder()
            .name("random-delay")
            .description("Randomizes delay slightly to avoid pattern detection.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> randomLook = sgGeneral.add(
        new BoolSetting.Builder()
            .name("random-look")
            .description("Adds slight random offset to look direction.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> silentSwap = sgGeneral.add(
        new BoolSetting.Builder()
            .name("silent-swap")
            .description("Swap slot silently via packet instead of visually.")
            .defaultValue(true)
            .build()
    );

    private int pendingFrameId = -1;
    private int delayTicks = 0;

    public AutoMapPlace() {
        super(Categories.Player, "auto-map-place",
            "When you place an item frame, silently places a map on it.");
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

            // Don't overwrite a pending frame — queue it only if we're free
            if (pendingFrameId >= 0) return;

            pendingFrameId = pkt.getEntityId();
            int base = placementDelay.get();
            delayTicks = randomDelay.get()
                ? base + (int)(Math.random() * (base / 2 + 1))
                : base;
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

        ClientPlayerEntity player = mc.player;

        // Calculate look angles toward frame
        Vec3d target = frame.getPos().add(0, frame.getHeight() / 2.0, 0);
        Vec3d eye = player.getEyePos();
        Vec3d diff = target.subtract(eye);
        double yaw   = Math.toDegrees(Math.atan2(-diff.x, diff.z));
        double pitch = Math.toDegrees(-Math.atan2(diff.y,
            Math.sqrt(diff.x * diff.x + diff.z * diff.z)));
        double yawOff   = randomLook.get() ? (Math.random() * 4 - 2)   : 0;
        double pitchOff = randomLook.get() ? (Math.random() * 3 - 1.5) : 0;
        float fakeYaw   = (float)(yaw + yawOff);
        float fakePitch = (float)(pitch + pitchOff);

        // Save real state
        float realYaw   = player.getYaw();
        float realPitch = player.getPitch();
        int   realSlot  = player.getInventory().getSelectedSlot();

        // Apply fake state client-side only
        player.setYaw(fakeYaw);
        player.setPitch(fakePitch);

        if (silentSwap.get()) {
            // Send slot change packet without changing visual hotbar
            mc.getNetworkHandler().sendPacket(
                new UpdateSelectedSlotC2SPacket(mapSlot));
        } else {
            player.getInventory().setSelectedSlot(mapSlot);
        }

        // Send interact packet
        mc.interactionManager.interactEntity(player, frame, Hand.MAIN_HAND);

        // Restore real state immediately
        player.setYaw(realYaw);
        player.setPitch(realPitch);

        if (silentSwap.get()) {
            mc.getNetworkHandler().sendPacket(
                new UpdateSelectedSlotC2SPacket(realSlot));
        } else {
            player.getInventory().setSelectedSlot(realSlot);
        }
    }

    private int findMapInHotbar(MinecraftClient mc) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() == Items.MAP) return i;
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() instanceof FilledMapItem) return i;
        }
        return -1;
    }
}
