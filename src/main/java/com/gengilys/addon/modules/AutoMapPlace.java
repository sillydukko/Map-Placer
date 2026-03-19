package com.gengilys.addon.modules;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.ChunkPos;

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
        .description("Break a filled frame first, then place a new map (saves item frames).")
        .defaultValue(false)
        .build());

    private final Setting<Double> chunkDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("chunk-distance")
        .description("Minimum chunk distance between placements to prevent dumping all maps at once.")
        .defaultValue(2.0)
        .min(0.5)
        .max(16.0)
        .sliderMin(0.5)
        .sliderMax(8.0)
        .build());

    // Frame IDs queued from spawn packets
    private final Queue<Integer> spawnQueue = new LinkedList<>();
    // Chunk positions we've already placed in, with timestamps
    private final Map<Long, Long> placedChunks = new HashMap<>();
    // How long (ms) before a chunk is forgotten from history
    private static final long HISTORY_CLEAR_MS = 5 * 60 * 1000;
    // Cooldown between placements (ticks)
    private int cooldown = 0;

    public AutoMapPlace() {
        super(CATEGORY, "auto-map-place",
            "Automatically places maps on empty item frames near you.");
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
        // Queue the entity ID for processing on next tick (world may not have it yet)
        spawnQueue.add(packet.getEntityId());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!autoPlace.get()) return;
        if (mc.player == null || mc.world == null) return;

        // Expire old chunk history
        long now = System.currentTimeMillis();
        placedChunks.entrySet().removeIf(e -> now - e.getValue() > HISTORY_CLEAR_MS);

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        // Try frames from spawn queue first (freshly spawned)
        while (!spawnQueue.isEmpty()) {
            int id = spawnQueue.poll();
            var entity = mc.world.getEntityById(id);
            if (entity instanceof ItemFrameEntity frame && tryPlace(frame)) {
                cooldown = 5;
                return;
            }
        }

        // Scan all item frames within 32 blocks every tick
        for (var entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemFrameEntity frame)) continue;
            if (frame.getPos().squaredDistanceTo(mc.player.getPos()) > 32 * 32) continue;
            if (tryPlace(frame)) {
                cooldown = 5;
                return;
            }
        }
    }

    /**
     * Attempts to place a map in the given frame.
     * Returns true if a placement packet was sent.
     */
    private boolean tryPlace(ItemFrameEntity frame) {
        // Check if frame already has a filled map
        var heldItem = frame.getHeldItemStack();
        boolean hasMap = heldItem.getItem() instanceof FilledMapItem;

        if (hasMap && !stackFrames.get()) return false;

        // Chunk-distance throttle
        ChunkPos frameChunk = new ChunkPos(frame.getBlockPos());
        ChunkPos playerChunk = new ChunkPos(mc.player.getBlockPos());
        double dist = chunkDist(frameChunk, playerChunk);
        if (dist < chunkDistance.get()) return false;

        // Check if this chunk was placed recently
        long chunkKey = frameChunk.toLong();
        if (placedChunks.containsKey(chunkKey)) return false;

        // Find a map in hotbar (prefer filled maps over blank ones)
        int mapSlot = findMapSlot();
        if (mapSlot == -1) return false;

        // Switch to the map slot if needed
        if (mc.player.getInventory().getSelectedSlot() != mapSlot) {
            mc.player.getInventory().setSelectedSlot(mapSlot);
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(mapSlot));
        }

        // Send interact packet directly — works even when not looking at the frame
        mc.getNetworkHandler().sendPacket(
            PlayerInteractEntityC2SPacket.interact(frame, false, Hand.MAIN_HAND));

        placedChunks.put(chunkKey, System.currentTimeMillis());
        info("Placed map on frame " + frame.getId() + " at " + frame.getBlockPos());
        return true;
    }

    /**
     * Find a filled map (or blank map) in the hotbar, then in the full inventory.
     * Returns hotbar slot 0-8, or inventory slot mapped to hotbar swap space (9-35).
     * For simplicity, only uses hotbar here — move maps to hotbar before using.
     */
    private int findMapSlot() {
        var inv = mc.player.getInventory();
        // First pass: filled maps in hotbar
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() instanceof FilledMapItem) return i;
        }
        // Second pass: blank maps in hotbar (Maps that haven't been explored yet
        // are Items.FILLED_MAP in 1.21; Items.MAP was removed — FilledMapItem covers both)
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() == Items.MAP) return i;
        }
        return -1;
    }

    private double chunkDist(ChunkPos a, ChunkPos b) {
        int dx = a.x - b.x;
        int dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
