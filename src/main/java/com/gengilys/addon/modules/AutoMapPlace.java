package com.gengilys.addon.modules;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import java.util.HashSet;
import java.util.Set;

public class AutoMapPlace extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> placementDelay = sgGeneral.add(
        new IntSetting.Builder()
            .name("placement-delay")
            .description("Ticks to wait after detecting an empty frame. 20 = 1 second.")
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
            .description("Adds slight random offset to silent look direction.")
            .defaultValue(true)
            .build()
    );

    private final Set<Integer> processedFrames = new HashSet<>();
    private int pendingFrameId = -1;
    private int delayTicks = 0;

    // State for two-tick place sequence
    private boolean placing = false;
    private int placingFrameId = -1;
    private int placingMapSlot = -1;
    private float placingYaw, placingPitch;
    private float realYaw, realPitch;
    private int realSlot;

    public AutoMapPlace() {
        super(Categories.Player, "auto-map-place",
            "When you place an item frame, silently places a map on it.");
    }

    @Override
    public void onActivate() {
        processedFrames.clear();
        pendingFrameId = -1;
        delayTicks = 0;
        placing = false;
        info("AutoMapPlace activated.");
    }

    @Override
    public void onDeactivate() {
        processedFrames.clear();
        placing = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options != null) mc.options.useKey.setPressed(false);
    }

    @EventHandler
    private void onTick(meteordevelopment.meteorclient.events.world.TickEvent.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        // ── Tick 2: release key and restore state ─────────────────────────
        if (placing) {
            placing = false;
            mc.options.useKey.setPressed(false);
            mc.player.setYaw(realYaw);
            mc.player.setPitch(realPitch);
            mc.player.getInventory().setSelectedSlot(realSlot);
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(realSlot));
            info("Released use key, restored state.");
            return;
        }

        // ── Process pending frame ──────────────────────────────────────────
        if (pendingFrameId >= 0) {
            if (delayTicks-- > 0) return;

            Entity entity = mc.world.getEntityById(pendingFrameId);
            int id = pendingFrameId;
            pendingFrameId = -1;
            processedFrames.add(id);

            if (!(entity instanceof ItemFrameEntity frame)) {
                info("Frame " + id + " not found.");
                return;
            }
            if (!frame.getHeldItemStack().isEmpty()) {
                info("Frame " + id + " already filled.");
                return;
            }
            if (mc.player.distanceTo(frame) > 5.0) {
                info("Frame " + id + " too far.");
                return;
            }

            int mapSlot = findMapInHotbar(mc);
            if (mapSlot < 0) {
                info("No map in hotbar!");
                return;
            }

            // Calculate look direction
            Vec3d target = frame.getPos().add(0, frame.getHeight() / 2.0, 0);
            Vec3d eye = mc.player.getEyePos();
            Vec3d diff = target.subtract(eye);
            double yaw   = Math.toDegrees(Math.atan2(-diff.x, diff.z));
            double pitch = Math.toDegrees(-Math.atan2(diff.y,
                Math.sqrt(diff.x * diff.x + diff.z * diff.z)));
            double yawOff   = randomLook.get() ? (Math.random() * 4 - 2)   : 0;
            double pitchOff = randomLook.get() ? (Math.random() * 3 - 1.5) : 0;

            // Save real state
            realYaw   = mc.player.getYaw();
            realPitch = mc.player.getPitch();
            realSlot  = mc.player.getInventory().getSelectedSlot();
            placingYaw   = (float)(yaw + yawOff);
            placingPitch = (float)(pitch + pitchOff);
            placingMapSlot = mapSlot;
            placingFrameId = id;

            // ── Tick 1: apply state and press use key ──────────────────────
            mc.player.setYaw(placingYaw);
            mc.player.setPitch(placingPitch);
            mc.player.getInventory().setSelectedSlot(mapSlot);
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(mapSlot));
            mc.options.useKey.setPressed(true);
            placing = true;

            info("Tick 1: pressing use key on frame " + id + " slot " + mapSlot);
            return;
        }

        // ── Scan for new empty frames nearby ──────────────────────────────
        Vec3d playerPos = mc.player.getPos();
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemFrameEntity frame)) continue;
            if (processedFrames.contains(frame.getId())) continue;
            if (!frame.getHeldItemStack().isEmpty()) {
                processedFrames.add(frame.getId());
                continue;
            }
            double dist = Math.sqrt(entity.squaredDistanceTo(playerPos));
            if (dist > 6.0) continue;

            info("Found empty frame " + frame.getId() + " at " + String.format("%.1f", dist) + " blocks.");
            pendingFrameId = frame.getId();
            int base = placementDelay.get();
            delayTicks = randomDelay.get()
                ? base + (int)(Math.random() * Math.max(1, base / 2))
                : base;
            return;
        }

        processedFrames.removeIf(id -> mc.world.getEntityById(id) == null);
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
