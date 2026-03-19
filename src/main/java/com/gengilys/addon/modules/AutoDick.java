package com.gengily.map.modules;

import com.gengily.map.GengilyMap;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class AutoDick extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between block placements.")
        .defaultValue(2).min(0).max(10).sliderMin(0).sliderMax(10)
        .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Look at block server-side before placing.")
        .defaultValue(true)
        .build());

    /*
     * Shape (relative to player feet, facing north):
     *
     *   .•.   y+2
     *   .•.   y+1
     *   •••   y+0
     *
     * x: -1, 0, +1
     * Build bottom row first so we always have a surface to click.
     */
    private static final int[][] SHAPE = {
        // Base row (y=0)
        {-1, 0, 0},
        { 0, 0, 0},
        { 1, 0, 0},
        // Shaft (y=1, y=2) — center column only
        { 0, 1, 0},
        { 0, 2, 0},
    };

    private List<BlockPos> targets = new ArrayList<>();
    private int step = 0;
    private int tickTimer = 0;

    public AutoDick() {
        super(GengilyMap.CATEGORY, "auto-dick",
            "Builds an obsidian structure in front of you. You know what it looks like.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;

        // Anchor: 1 block in front of player feet
        BlockPos anchor = mc.player.getBlockPos()
            .offset(mc.player.getHorizontalFacing(), 1);

        targets.clear();
        for (int[] o : SHAPE)
            targets.add(anchor.add(o[0], o[1], o[2]));

        step = 0;
        tickTimer = 0;
    }

    @Override
    public void onDeactivate() {
        targets.clear();
        step = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (step >= targets.size()) { toggle(); return; }

        if (tickTimer > 0) { tickTimer--; return; }

        int slot = findObsidian();
        if (slot == -1) {
            mc.player.sendMessage(
                net.minecraft.text.Text.literal("§c[AutoDick] Need obsidian!"), true);
            toggle(); return;
        }

        BlockPos target = targets.get(step);

        // Skip if already obsidian
        if (mc.world.getBlockState(target).getBlock() == Blocks.OBSIDIAN) {
            step++; return;
        }

        // Find a solid neighbor to click against
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                             Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction face : order) {
            BlockPos neighbor = target.offset(face);
            if (!mc.world.getBlockState(neighbor).isSolidBlock(mc.world, neighbor)) continue;

            Direction hitFace = face.getOpposite();
            Vec3d hitVec = Vec3d.ofCenter(neighbor).add(
                hitFace.getOffsetX() * 0.5,
                hitFace.getOffsetY() * 0.5,
                hitFace.getOffsetZ() * 0.5
            );

            if (rotate.get()) lookAt(hitVec);

            int prev = mc.player.getInventory().selectedSlot;
            mc.player.getInventory().selectedSlot = slot;
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                new BlockHitResult(hitVec, hitFace, neighbor, false));
            mc.player.swingHand(Hand.MAIN_HAND);
            mc.player.getInventory().selectedSlot = prev;

            step++;
            tickTimer = delay.get();
            return;
        }

        // No solid neighbor yet — skip for now, retry next tick
        tickTimer = 1;
    }

    private int findObsidian() {
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).getItem() == Items.OBSIDIAN) return i;
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
