package com.gengilys.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;

import static com.gengilys.addon.MapAutoPlaceAddon.CATEGORY;

public class AutoMapPlace extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> autoPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-place")
        .description("Automatically places maps on empty item frames near you.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> stackFrames = sgGeneral.add(new BoolSetting.Builder()
        .name("stack-frames")
        .description("Break a filled frame first, then place a new map.")
        .defaultValue(false)
        .build());

    private final Setting<Double> chunkDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("chunk-distance")
        .description("Minimum chunk distance between placements.")
        .defaultValue(2.0).min(0.5).max(16.0).sliderMin(0.5).sliderMax(8.0)
        .build());

    private final Queue<Integer> spawnQueue = new LinkedList<>();
    private final Map<Long, Long> placedChunks = new HashMap<>();
    private static final long HISTORY_CLEAR_MS = 5 * 60 * 1000;
    private int cooldown = 0;

    public AutoMapPlace() {
        super(CATEGORY, "auto-map-place", "Automatically places maps on empty item frames.");
    }

    @Override
    public void onActivate() {
        spawnQueue.clear();
        placedChunks.clear();
        cooldown = 0;
    }

    @Override
    public void onDeactivate() {
        spawnQueue.clear();
    }

    @EventHandler
    private void onEntitySpawn(PacketEvent.Receive event) {
        if (!(event.packet instanceof EntitySpawnS2CPacket packet)) return;
        if (mc.world == null) return;
        spawnQueue.add(packet.getEntityId());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!autoPlace.get()) return;
        if (mc.player == null || mc.world == null) return;

        long now = System.currentTimeMillis();
        placedChunks.entrySet().removeIf(e -> now - e.getValue() > HISTORY_CLEAR_MS);

        if (cooldown > 0) { cooldown--; return; }

        // Try spawn queue first
        while (!spawnQueue.isEmpty()) {
            int id = spawnQueue.poll();
            var entity = mc.world.getEntityById(id);
            if (entity instanceof ItemFrameEntity frame && tryPlace(frame)) {
                cooldown = 10;
                return;
            }
        }

        // Scan nearby frames
        for (var entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemFrameEntity frame)) continue;
            if (frame.getPos().squaredDistanceTo(mc.player.getPos()) > 16 * 16) continue;
            if (tryPlace(frame)) { cooldown = 10; return; }
        }
    }

    private boolean tryPlace(ItemFrameEntity frame) {
        var heldItem = frame.getHeldItemStack();
        boolean hasMap = heldItem.getItem() instanceof FilledMapItem;
        if (hasMap && !stackFrames.get()) return false;

        // Must be within reach
        double dist = frame.getPos().distanceTo(mc.player.getPos());
        if (dist > 4.5) return false;

        // Chunk-distance throttle
        ChunkPos frameChunk = new ChunkPos(frame.getBlockPos());
        ChunkPos playerChunk = new ChunkPos(mc.player.getBlockPos());
        double chunkDist = Math.sqrt(Math.pow(frameChunk.x - playerChunk.x, 2) + Math.pow(frameChunk.z - playerChunk.z, 2));
        if (chunkDist < chunkDistance.get()) return false;

        long chunkKey = frameChunk.toLong();
        if (placedChunks.containsKey(chunkKey)) return false;

        int mapSlot = findMapSlot();
        if (mapSlot == -1) return false;

        // Switch to map slot
        if (mc.player.getInventory().getSelectedSlot() != mapSlot) {
            mc.player.getInventory().setSelectedSlot(mapSlot);
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(mapSlot));
        }

        // Silent rotation toward frame
        Vec3d framePos = frame.getPos();
        Vec3d playerEye = mc.player.getEyePos();
        double dx = framePos.x - playerEye.x;
        double dy = framePos.y - playerEye.y;
        double dz = framePos.z - playerEye.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, hDist));

        // Send server-side rotation only (silent)
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
            MathHelper.wrapDegrees(yaw), MathHelper.clamp(pitch, -90f, 90f), mc.player.isOnGround(), mc.player.horizontalCollision));

        // Interact with frame
        mc.getNetworkHandler().sendPacket(
            PlayerInteractEntityC2SPacket.interact(frame, false, Hand.MAIN_HAND));

        // Restore real rotation silently
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
            mc.player.getYaw(), mc.player.getPitch(), mc.player.isOnGround(), mc.player.horizontalCollision));

        placedChunks.put(chunkKey, System.currentTimeMillis());
        info("Placed map on frame " + frame.getId() + " at " + frame.getBlockPos());
        return true;
    }

    private int findMapSlot() {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() instanceof FilledMapItem) return i;
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() == Items.MAP) return i;
        }
        return -1;
    }
}
