package com.gengily.map.modules;

import com.gengily.map.GengilyMap;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;

import java.util.*;

public class MapAura extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range").description("Block reach range.")
        .defaultValue(4.0).min(1.0).max(6.0).sliderMin(1.0).sliderMax(6.0).build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Ticks between each placement.")
        .defaultValue(2).min(0).max(20).sliderMin(0).sliderMax(10).build());

    private final Setting<Boolean> placeFrames = sgGeneral.add(new BoolSetting.Builder()
        .name("place-frames").description("Place item frames on bare blocks.")
        .defaultValue(true).build());

    private final Setting<Boolean> placeMaps = sgGeneral.add(new BoolSetting.Builder()
        .name("place-maps").description("Place filled maps into empty frames.")
        .defaultValue(true).build());

    private int tickTimer = 0;

    public MapAura() {
        super(GengilyMap.CATEGORY, "map-aura",
            "Places item frames and maps on every reachable block face.");
    }

    @Override public void onActivate() { tickTimer = 0; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (tickTimer > 0) { tickTimer--; return; }

        if (placeFrames.get() && tryPlaceFrame()) { tickTimer = delay.get(); return; }
        if (placeMaps.get()   && tryPlaceMap())   { tickTimer = delay.get(); }
    }

    private boolean tryPlaceFrame() {
        int slot = findHotbar(Items.ITEM_FRAME);
        if (slot == -1) return false;

        Vec3d eye = mc.player.getEyePos();
        double r = range.get();

        for (BlockPos solid : getSphere()) {
            // Must have a collision box (walls, slabs, full blocks, etc)
            BlockState solidState = mc.world.getBlockState(solid);
            if (solidState.isAir()) continue;
            if (solidState.getCollisionShape(mc.world, solid).isEmpty()) continue;

            // Prefer vertical faces (wall-mounted maps) over floor/ceiling
for (Direction face : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN}) {
                BlockPos empty = solid.offset(face);

                // Space for frame must be empty
                BlockState emptyState = mc.world.getBlockState(empty);
                if (!emptyState.isAir() && !emptyState.isReplaceable()) continue;

                // Skip if frame already exists nearby
                if (frameExistsNear(empty)) continue;

                Vec3d hitVec = Vec3d.ofCenter(solid).add(
                    face.getOffsetX() * 0.5,
                    face.getOffsetY() * 0.5,
                    face.getOffsetZ() * 0.5
                );
                if (hitVec.distanceTo(eye) > r) continue;

                place(slot, solid, face, hitVec);
                return true;
            }
        }
        return false;
    }

    private boolean tryPlaceMap() {
        int slot = findMapHotbar();
        if (slot == -1) return false;

        Vec3d eye = mc.player.getEyePos();

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof ItemFrameEntity frame)) continue;
            if (!frame.getHeldItemStack().isEmpty()) continue;
            Vec3d c = frame.getPos().add(0, frame.getHeight() / 2.0, 0);
            if (c.distanceTo(eye) > range.get()) continue;

            int prev = mc.player.getInventory().selectedSlot;
            mc.player.getInventory().selectedSlot = slot;
            var hit = new net.minecraft.util.hit.EntityHitResult(frame, c);
            var res = mc.interactionManager.interactEntityAtLocation(mc.player, frame, hit, Hand.MAIN_HAND);
            if (res == net.minecraft.util.ActionResult.PASS)
                mc.interactionManager.interactEntity(mc.player, frame, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
            mc.player.getInventory().selectedSlot = prev;
            return true;
        }
        return false;
    }

    private boolean frameExistsNear(BlockPos pos) {
        Box box = new Box(pos).expand(0.6);
        return !mc.world.getEntitiesByClass(ItemFrameEntity.class, box, f -> true).isEmpty();
    }

    private List<BlockPos> getSphere() {
        int r = (int) Math.ceil(range.get());
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
        int prev = mc.player.getInventory().selectedSlot;
        mc.player.getInventory().selectedSlot = slot;
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
            new BlockHitResult(hitVec, face, pos, false));
        mc.player.swingHand(Hand.MAIN_HAND);
        mc.player.getInventory().selectedSlot = prev;
    }

    private int findHotbar(net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).getItem() == item) return i;
        return -1;
    }

    private int findMapHotbar() {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() instanceof FilledMapItem) return i;
        }
        return -1;
    }

    private void lookAt(Vec3d target) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d d = target.subtract(eye);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw   = MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90f);
        float pitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(d.y, h)), -90f, 90f);
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
            yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
    }
}
