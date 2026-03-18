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
import net.minecraft.util.Hand;
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

    private final Setting<Boolean> silentSwap = sgGeneral.add(
        new BoolSetting.Builder()
            .name("silent-swap")
            .description("Swaps slot via packet — server sees the right item, hotbar doesn't move.")
            .defaultValue(true)
            .build()
    );

    // Frames we've already filled or queued — don't re-process
    private final Set<Integer> processedFrames = new HashSet<>();
    // Frame queued for placement
    private int pendingFrameId = -1;
    private int delayTicks = 0;

    public AutoMapPlace() {
        super(Categories.Player, "auto-map-place",
            "When you place an item frame, silently places a map on it.");
    }

    @Override
    public void onActivate() {
        processedFrames.clear();
        pendingFrameId = -1;
        delayTicks = 0;
    }

    @Override
    public void onDeactivate() {
        processedFrames.clear();
    }

    @EventHandler
    private void onTick(meteordevelopment.meteorclient.events.world.TickEvent.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        // --- Process pending frame ---
        if (pendingFrameId >= 0) {
            if (delayTicks-- > 0) return;

            Entity entity = mc.world.getEntityById(pendingFrameId);
            int id = pendingFrameId;
            pendingFrameId = -1;

            if (entity instanceof ItemFrameEntity frame
                    && frame.getHeldItemStack().isEmpty()
                    && mc.player.distanceTo(frame) <= 5.0) {
                placeMap(mc, frame);
            }
            processedFrames.add(id);
            return;
        }

        // --- Scan for new empty frames nearby ---
        Vec3d playerPos = mc.player.getPos();
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemFrameEntity frame)) continue;
            if (processedFrames.contains(frame.getId())) continue;
            if (!frame.getHeldItemStack().isEmpty()) {
                // Frame already has something — mark as done
                processedFrames.add(frame.getId());
                continue;
            }
            if (entity.squaredDistanceTo(playerPos) > 6.0 * 6.0) continue;

            // Found a new empty nearby frame — queue it
            pendingFrameId = frame.getId();
            int base = placementDelay.get();
            delayTicks = randomDelay.get()
                ? base + (int)(Math.random() * Math.max(1, base / 2))
                : base;
            return;
        }

        // Clean up processed frames that are no longer loaded
        processedFrames.removeIf(id -> mc.world.getEntityById(id) == null);
    }

    private void placeMap(MinecraftClient mc, ItemFrameEntity frame) {
        int mapSlot = findMapInHotbar(mc);
        if (mapSlot < 0) return;

        ClientPlayerEntity player = mc.player;

        // Calculate silent look angles
        Vec3d target = frame.getPos().add(0, frame.getHeight() / 2.0, 0);
        Vec3d eye = player.getEyePos();
        Vec3d diff = target.subtract(eye);
        double yaw   = Math.toDegrees(Math.atan2(-diff.x, diff.z));
        double pitch = Math.toDegrees(-Math.atan2(diff.y,
            Math.sqrt(diff.x * diff.x + diff.z * diff.z)));
        double yawOff   = randomLook.get() ? (Math.random() * 4 - 2)   : 0;
        double pitchOff = randomLook.get() ? (Math.random() * 3 - 1.5) : 0;

        // Save real state
        float realYaw   = player.getYaw();
        float realPitch = player.getPitch();
        int   realSlot  = player.getInventory().getSelectedSlot();

        // Apply fake rotation (client only)
        player.setYaw((float)(yaw + yawOff));
        player.setPitch((float)(pitch + pitchOff));

        // Swap slot
        if (silentSwap.get()) {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(mapSlot));
        } else {
            player.getInventory().setSelectedSlot(mapSlot);
        }

        // Interact
        mc.interactionManager.interactEntity(player, frame, Hand.MAIN_HAND);

        // Restore real state
        player.setYaw(realYaw);
        player.setPitch(realPitch);
        if (silentSwap.get()) {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(realSlot));
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
