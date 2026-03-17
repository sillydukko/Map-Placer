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
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.Blocks;
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

    private final Setting<Boolean> placeFrames = sgGeneral.add(
        new BoolSetting.Builder()
            .name("place-frames")
            .description("Automatically place item frames on nearby wall blocks.")
            .defaultValue(false)
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

    private final Setting<Integer> placementDelay = sgGeneral.add(
        new IntSetting.Builder()
            .name("placement-delay")
            .description("Ticks between each placement. 20 ticks = 1 second.")
            .defaultValue(40)
            .min(5)
            .sliderMax(100)
            .build()
    );

    private final Setting<Boolean> randomDelay = sgGeneral.add(
        new BoolSetting.Builder()
            .name("random-delay")
            .description("Randomizes placement delay slightly to look more human.")
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

    private static final double SCAN_RANGE = 32.0;

    private final Queue<Integer> pendingFrames = new ArrayDeque<>();
    private final Map<Long, Long> lastPlacementTick = new HashMap<>();
    private long lastPlacedTick = 0L;

    public AutoMapPlace() {
        super(Categories.Player, "auto-map-place",
            "Places item frames and maps on nearby wall blocks.");
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
        if (!isActive()) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        long currentTick = mc.world.getTime();

        int delay = placementDelay.get();
        if (randomDelay.get()) delay += (int)(Math.random() * 20) - 10;
        if (currentTick - lastPlacedTick < Math.max(5, delay)) return;

        // Try placing maps on existing frames first
        if (autoPlace.get()) {
            if (!pendingFrames.isEmpty()) {
                int id = pendingFrames.poll();
                Entity entity = mc.world.getEntityById(id);
                if (entity instanceof ItemFrameEntity frame) {
                    tryPlaceOnFrame(mc, frame, currentTick);
                }
                return;
            }

            Vec3d playerPos = mc.player.getPos();
            for (Entity entity : mc.world.getEntities()) {
                if (!(entity instanceof ItemFrameEntity frame)) continue;
                if (entity.squaredDistanceTo(playerPos) > SCAN_RANGE * SCAN_RANGE) continue;
                if (tryPlaceOnFrame(mc, frame, currentTick)) return;
            }
        }

        // Try placing item frames on wall blocks
        if (placeFrames.get()) {
            tryPlaceFrame(mc, currentTick);
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

    // ── Place map on frame ────────────────────────────────────────────────────

    private boolean placeMap(MinecraftClient mc, ItemFrameEntity frame, long currentTick) {
        ChunkPos frameChunk = new ChunkPos(frame.getBlockPos());
        if (!isChunkAllowed(frameChunk)) return false;
        if (mc.player.distanceTo(frame) > 4.5) return false;

        int mapSlot = findItemSlot(mc, Items.MAP, Items.FILLED_MAP);
        if (mapSlot < 0) return false;

        lookAt(mc, frame.getPos().add(0, frame.getHeight() / 2, 0));

        silentSwap(mc, mapSlot, () -> {
            mc.interactionManager.interactEntity(mc.player, frame, Hand.MAIN_HAND);
        });

        recordPlacement(frameChunk, currentTick);
        lastPlacedTick = currentTick;
        return true;
    }

    // ── Place item frame on wall ──────────────────────────────────────────────

    private void tryPlaceFrame(MinecraftClient mc, long currentTick) {
        int frameSlot = findItemSlot(mc, Items.ITEM_FRAME, Items.GLOW_ITEM_FRAME);
        if (frameSlot < 0) return;

        Vec3d playerPos = mc.player.getPos();

        // Scan nearby wall blocks for a suitable face to place a frame on
        int range = 4;
        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = BlockPos.ofFloored(playerPos).add(x, y, z);
                    if (!mc.world.getBlockState(pos).isSolidBlock(mc.world, pos)) continue;

                    for (Direction dir : Direction.values()) {
                        if (dir == Direction.DOWN) continue;
                        BlockPos face = pos.offset(dir);
                        if (!mc.world.getBlockState(face).isAir()) continue;

                        Vec3d hitVec = Vec3d.ofCenter(pos).add(
                            Vec3d.of(dir.getVector()).multiply(0.5));

                        if (mc.player.getEyePos().distanceTo(hitVec) > 4.5) continue;

                        ChunkPos chunkPos = new ChunkPos(pos);
                        if (!isChunkAllowed(chunkPos)) continue;

                        lookAt(mc, hitVec);

                        final int slot = frameSlot;
                        silentSwap(mc, slot, () -> {
                            BlockHitResult hit = new BlockHitResult(
                                hitVec, dir, pos, false);
                            mc.interactionManager.interactBlock(
                                mc.player, Hand.MAIN_HAND, hit);
                        });

                        recordPlacement(chunkPos, currentTick);
                        lastPlacedTick = currentTick;
                        return;
                    }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Look at a world position, with optional random offset. */
    private void lookAt(MinecraftClient mc, Vec3d target) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d diff = target.subtract(eye);
        double yaw   = Math.toDegrees(Math.atan2(-diff.x, diff.z));
        double pitch = Math.toDegrees(-Math.atan2(diff.y,
            Math.sqrt(diff.x * diff.x + diff.z * diff.z)));

        double yawOff   = randomLook.get() ? (Math.random() * 6 - 3)   : 0;
        double pitchOff = randomLook.get() ? (Math.random() * 4 - 2)   : 0;
        mc.player.setYaw((float)(yaw + yawOff));
        mc.player.setPitch((float)(pitch + pitchOff));
    }

    /** Swap to slot silently, run action, then swap back. */
    private void silentSwap(MinecraftClient mc, int slot, Runnable action) {
        int original = mc.player.getInventory().getSelectedSlot();
        if (slot < 9) mc.player.getInventory().setSelectedSlot(slot);
        action.run();
        mc.player.getInventory().setSelectedSlot(original);
    }

    /** Find first inventory slot containing either of the given items. */
    private int findItemSlot(MinecraftClient mc, Items... items) {
        var inventory = mc.player.getInventory();
        for (Items item : items) {
            for (int i = 0; i < inventory.size(); i++) {
                if (inventory.getStack(i).getItem() == item) return i;
            }
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
