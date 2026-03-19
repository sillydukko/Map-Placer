package com.gengilys.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import static com.gengilys.addon.MapAutoPlaceAddon.CATEGORY;

/**
 * MapAdBot — wanders around and automatically places item frames on walls/surfaces.
 * Use together with AutoMapPlace to fill frames with maps.
 */
public class MapAdBot extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWander  = settings.createGroup("Wander");

    private final Setting<Double> placeDelay = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-delay-seconds")
        .description("Seconds to wait between frame placements.")
        .defaultValue(4.0).min(1.0).max(6.0).sliderMin(1.0).sliderMax(6.0).build());

    private final Setting<Double> wanderRadius = sgWander.add(new DoubleSetting.Builder()
        .name("radius")
        .description("How far to walk before picking a new random direction.")
        .defaultValue(50.0).min(10.0).max(500.0).sliderMin(10.0).sliderMax(200.0).build());

    private final Setting<Integer> newGoalInterval = sgWander.add(new IntSetting.Builder()
        .name("new-goal-seconds")
        .description("Seconds before picking a new direction.")
        .defaultValue(20).min(5).max(120).sliderMin(5).sliderMax(60).build());

    private int placeTick  = 0;
    private int wanderTick = 0;
    // Track distance walked in current direction
    private Vec3d startPos = null;

    public MapAdBot() {
        super(CATEGORY, "map-ad-bot",
            "Wanders around and places item frames. Use with auto-map-place to fill them.");
    }

    @Override
    public void onActivate() {
        placeTick  = 0;
        wanderTick = 0;
        startPos   = mc.player != null ? mc.player.getPos() : null;
        newWanderGoal();
    }

    @Override
    public void onDeactivate() {
        stopMoving();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // Handle placement cooldown
        if (placeTick > 0) placeTick--;
        else if (tryPlaceFrame()) placeTick = (int)(placeDelay.get() * 20);

        // Handle wander
        wanderTick++;
        boolean walkedFarEnough = startPos != null &&
            mc.player.getPos().distanceTo(startPos) >= wanderRadius.get();

        if (wanderTick >= newGoalInterval.get() * 20 || walkedFarEnough) {
            wanderTick = 0;
            newWanderGoal();
        }
    }

    private void newWanderGoal() {
        if (mc.player == null) return;
        startPos = mc.player.getPos();

        // Pick a random yaw and start walking in that direction
        float yaw = (float)(Math.random() * 360.0);
        mc.player.setYaw(yaw);
        mc.options.forwardKey.setPressed(true);
        mc.options.sneakKey.setPressed(false);
    }

    private void stopMoving() {
        if (mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }

    /** Tries to place an item frame on the surface the player is looking at. */
    private boolean tryPlaceFrame() {
        if (mc.player == null || mc.crosshairTarget == null) return false;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit)) return false;

        int slot = findFrameInHotbar();
        if (slot == -1) return false;

        // Switch to item frame slot
        if (mc.player.getInventory().getSelectedSlot() != slot) {
            mc.player.getInventory().setSelectedSlot(slot);
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }

        mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(
            Hand.MAIN_HAND, hit, 0));

        return true;
    }

    private int findFrameInHotbar() {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() == Items.ITEM_FRAME) return i;
        }
        return -1;
    }
}
