package com.gengilys.addon.modules;

import com.gengilys.addon.MapAutoPlaceAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;

import java.util.*;

public class MapAdBot extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWander  = settings.createGroup("Wander");

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay").description("Ticks between placements.")
        .defaultValue(3).min(1).max(20).sliderMin(1).sliderMax(20).build());

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-range").description("Block reach.")
        .defaultValue(4.0).min(1.0).max(6.0).sliderMin(1.0).sliderMax(6.0).build());

    private final Setting<Double> wanderRadius = sgWander.add(new DoubleSetting.Builder()
        .name("radius").description("How far to wander.")
        .defaultValue(50.0).min(10.0).max(500.0).sliderMin(10.0).sliderMax(200.0).build());

    private final Setting<Integer> newGoalInterval = sgWander.add(new IntSetting.Builder()
        .name("new-goal-seconds").description("Seconds before picking a new direction.")
        .defaultValue(20).min(5).max(120).sliderMin(5).sliderMax(60).build());

    private int placeTick  = 0;
    private int wanderTick = 0;

    public MapAdBot() {
        super(MapAutoPlaceAddon.CATEGORY, "map-ad-bot",
            "Wanders around and plasters item frames on every surface. Enable Map Placer to fill them.");
    }

    @Override
    public void onActivate() {
        placeTick = 0;
        wanderTick = 0;
        newWanderGoal();
    }

    @Override
    public void onDeactivate() {
        if (mc.options != null) mc.options.forwardKey.setPressed(false);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (placeTick > 0) placeTick--;
        else if (tryPlaceFrame()) placeTick = placeDelay.get();

        wanderTick++;
        if (wanderTick >= newGoalInterval.get() * 20) {
            wanderTick = 0;
            newWanderGoal();
        }
    }

    private void newWanderGoal() {
        if (mc.player == null) return;
        float yaw = (float)(Math.random() * 360);
        mc.player.setYaw(yaw);
        mc.options.forwardKey.setPressed(true);
    }

    private boolean tryPlaceFrame() {
        int slot = findHotbar(Items.ITEM_FRAME);
        if (slot == -1) return false;

        Vec3d eye = mc.player.getEyePos();

        for (BlockPos solid : getNearbyBlocks()) {
            BlockState solidState = mc.world.getBlockState(solid);
            if (solidState.isAir()) continue;
            if (solidState.getCollisionShape(mc.world, solid).isEmpty()) continue;

            for (Direction face : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN}) {
                BlockPos empty = solid.offset(face);
                BlockState emptyState = mc.world.getBlockState(empty);
                if (!emptyState.isAir() && !emptyState.isReplaceable()) continue;
                if (frameExistsNear(empty)) continue;

                Vec3d hitVec = Vec3d.ofCenter(solid).add(
                    face.getOffsetX() * 0.5,
                    face.getOffsetY() * 0.5,
                    face.getOffsetZ() * 0.5
                );
                if (hitVec.distanceTo(eye) > placeRange.get()) continue;

                place(slot, solid, face, hitVec);
                return true;
            }
        }
        return false;
    }

    private boolean frameExistsNear(BlockPos pos) {
        return !mc.world.getEntitiesByClass(ItemFrameEntity.class,
            new Box(pos).expand(0.6), f -> true).isEmpty();
    }

    private List<BlockPos> getNearbyBlocks() {
        int r = (int) Math.ceil(placeRange.get());
        BlockPos origin = mc.player.getBlockPos();
        List<BlockPos> list = new ArrayList<>();
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++)
                for (int z = -r; z <= r; z++)
                    list.add(origin.add(x, y, z));
        list.sort(Comparator.comparingDouble(p ->
            mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p))));
        return list;
    }

    private void place(int slot, BlockPos pos, Direction face, Vec3d hitVec) {
        int prev = mc.player.getInventory().getSelectedSlot();
        mc.player.getInventory().setSelectedSlot(slot);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
            new BlockHitResult(hitVec, face, pos, false));
        mc.player.swingHand(Hand.MAIN_HAND);
        mc.player.getInventory().setSelectedSlot(prev);
    }

    private int findHotbar(net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).getItem() == item) return i;
        return -1;
    }
}
