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

    private final Setting<Integer> placementDelay = sgGeneral.add(
        new IntSetting.Builder()
            .name("placement-delay")
            .description("Ticks between each map placement. 20 ticks = 1 second.")
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
            .description("Adds a small random offset to where the player looks at the frame.")
            .defaultValue(true)
            .build()
    );

    private static final double SCAN_RANGE = 32.0;

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

        int delay = placementDelay.get();
        if (randomDelay.get()) delay += (int)(Math.random() * 20) - 10;
        if (currentTick - lastPlacedTick < Math.max(5, delay)) return;

        if (!pendingFrames.isEmpty()) {
            int id = pendingFrames.poll();
            Entity entity = mc.world.getEntityById(id);
            if (entity instanceof ItemFrameEntity frame) {
                tryPlac
