package com.gengilys.addon.modules;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.MinecraftClient;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

public class AutoMapPlace extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> autoPlace = sgGeneral.add(
        new BoolSetting.Builder()
            .name("auto-place")
            .description("Automatically place maps on nearby empty item frames.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> stackFrames = sgGeneral.add(
        new BoolSetting.Builder()
            .name("stack-frames")
            .description("Break filled frames first, then place a new map. Saves item frames!")
            .defaultValue(false)
            .build()
    );

    private final Setting<Double> chunkDistance = sgGeneral.add(
        new DoubleSetting.Builder()
            .name("chunk-distance")
            .description("Minimum distance in chunks between placements (0.5-16).")
            .defaultValue(2.0)
            .min(0.5)
            .sliderMax(16.0)
            .build()
    );

    private static final double SCAN_RANGE = 32.0;
    private static final int PLACEMENT_COOLDOWN_TICKS = 10;

    private final Queue<Integer> pendingFrames = new ArrayDeque<>();
    private final Map<Long, Long> lastPlacementTick = new HashMap<>();
    private long lastPlacedTick = 0L;

    public AutoMapPlace() {
        super(Categories.Player, "auto-map-place",
            "Places maps on empty item frames within 32 blocks.");
    }

    @Override
    public void onActivate() {
        pendingFrames.clear();
        lastPlacementTick.clear();
        lastPlacedTick = 0L;
    }

    @Override
    public void onDeactivate() {
        pendingFrames.clear();
    }

    @EventHandler
    private void onEntitySpawn(PacketEvent.Receive event) {
        if (!isActive() || !autoPlace.get()) return;
        if (event.packet instanceof EntitySpawnS2CPacket pkt) {
            if (pkt.getEntityType() == EntityType.ITEM_FRAME
                    || pkt.getEntityType() == EntityType.GLOW_ITEM_FRAME) {
                pendingFrames.offer(pkt.getEntityId());
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!isActive() || !autoPlace.get()) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        long currentTick = mc.world.getTime();
        if (currentTick - lastPlacedTick < PLACEMENT_COOLDOWN_TICKS) return;

        while (!pendingFrames.isEmpty()) {
            int id = pendingFrames.poll();
            Entity entity = mc.world.getEntityById(id);
            if (entity instanceof ItemFrameEntity frame) {
                if (tryPlaceOnFrame(mc, frame, currentTick)) return;
            }
        }

        Vec3d playerPos = mc.player.getPos();
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemFrameEntity frame)) continue;
            if (entity.squaredDistanceTo(playerPos) > SCAN_RANGE * SCAN_RANGE) continue;
            if (tryPlaceOnFrame(mc, frame, currentTick)) return;
        }
    }

    private boolean tryPlaceOnFrame(MinecraftClient mc, ItemFrameEntity frame, long currentTick) {
        ItemStack held = frame.getHeldItemStack();
        if (held.isEmpty()) return placeMap(mc, frame, currentTick);
        if (held.getItem() instanceof FilledMapItem && stackFrames.get()) {
            mc.interactionManager.attackEntity(mc.player, frame);
            return true;
        }
        return false;
    }

    private boolean placeMap(MinecraftClient mc, ItemFrameEntity frame, long currentTick) {
        ChunkPos frameChunk = new ChunkPos(frame.getBlockPos());
        if (!isChunkAllowed(frameChunk)) return false;

        int mapSlot = findMapSlot(mc);
        if (mapSlot < 0) return false;

        int originalSlot = mc.player.getInventory().selectedSlot;
        if (mapSlot < 9) mc.player.getInventory().selectedSlot = mapSlot;

        EntityHitResult hit = new EntityHitResult(frame);
        ActionResult result = mc.interactionManager.interactEntityAtLocation(
            mc.player, frame, hit, Hand.MAIN_HAND);

        mc.player.getInventory().selectedSlot = originalSlot;

        if (result.isAccepted()) {
            recordPlacement(frameChunk, currentTick);
            lastPlacedTick = currentTick;
            return true;
        }
        return false;
    }

    private int findMapSlot(MinecraftClient mc) {
        var inventory = mc.player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).getItem() == Items.MAP) return i;
        }
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).getItem() == Items.FILLED_MAP) return i;
        }
        return -1;
    }

    private boolean isChunkAllowed(ChunkPos target) {
        double minDist = chunkDistance.get();
        for (long key : lastPlacementTick.keySet()) {
            ChunkPos placed = new ChunkPos(BlockPos.fromLong(key));
            double dx = target.x - placed.x;
            double dz = target.z - placed.z;
            if (Math.sqrt(dx * dx + dz * dz) < minDist) return false;
        }
        return true;
    }

    private void recordPlacement(ChunkPos chunk, long tick) {
        long key = BlockPos.asLong(chunk.x, 0, chunk.z);
        lastPlacementTick.put(key, tick);
        lastPlacementTick.entrySet().removeIf(e -> tick - e.getValue() > 6000L);
    }
}
