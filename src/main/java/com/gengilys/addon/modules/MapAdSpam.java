package com.gengilys.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.MathHelper;

import java.util.List;

import static com.gengilys.addon.MapAutoPlaceAddon.CATEGORY;

/**
 * MapAdSpam — sends chat messages at intervals to advertise your map art,
 * with optional movement modes to keep the player online/moving.
 */
public class MapAdSpam extends Module {

    private final SettingGroup sgMessages = settings.createGroup("Messages");
    private final SettingGroup sgMovement = settings.createGroup("Movement");

    private final Setting<List<String>> messages = sgMessages.add(new StringListSetting.Builder()
        .name("messages")
        .description("Messages to spam in chat. Sent in order (or randomly).")
        .defaultValue(List.of("Check out my map art! /warp maps"))
        .build());

    private final Setting<Integer> msgInterval = sgMessages.add(new IntSetting.Builder()
        .name("interval-seconds")
        .description("Seconds between each message.")
        .defaultValue(30).min(5).max(300).sliderMin(10).sliderMax(120)
        .build());

    private final Setting<Boolean> randomOrder = sgMessages.add(new BoolSetting.Builder()
        .name("random-order")
        .description("Send messages in a random order instead of sequentially.")
        .defaultValue(false)
        .build());

    private final Setting<MoveMode> moveMode = sgMovement.add(new EnumSetting.Builder<MoveMode>()
        .name("move-mode")
        .description("Sneak: bobs in place. Spin: rotates. Walk: walks in random directions.")
        .defaultValue(MoveMode.Sneak).build());

    private final Setting<Integer> wanderInterval = sgMovement.add(new IntSetting.Builder()
        .name("wander-interval-seconds").description("How often to pick a new walk direction.")
        .defaultValue(15).min(5).max(60).sliderMin(5).sliderMax(60)
        .visible(() -> moveMode.get() == MoveMode.Walk).build());

    public enum MoveMode { Walk, Sneak, Spin }

    private int msgIndex  = 0;
    private int msgTimer  = 0;
    private int moveTick  = 0;
    private boolean sneakOn = false;
    // Walk mode: direction change timer
    private int walkTimer = 0;

    public MapAdSpam() {
        super(CATEGORY, "map-ad-spam",
            "Spams chat messages and keeps you moving so you don't get kicked.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        msgTimer  = msgInterval.get() * 20; // send first message after one interval
        moveTick  = 0;
        sneakOn   = false;
        walkTimer = 0;
        if (!randomOrder.get()) msgIndex = 0;
        if (moveMode.get() == MoveMode.Walk) pickNewWalkDirection();
    }

    @Override
    public void onDeactivate() {
        stopAllKeys();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // --- Message sending ---
        msgTimer--;
        if (msgTimer <= 0) {
            msgTimer = msgInterval.get() * 20;
            List<String> msgs = messages.get();
            if (!msgs.isEmpty()) {
                String msg;
                if (randomOrder.get()) {
                    msg = msgs.get((int)(Math.random() * msgs.size()));
                } else {
                    msg = msgs.get(msgIndex % msgs.size());
                    msgIndex++;
                }
                mc.player.networkHandler.sendChatMessage(msg);
            }
        }

        // --- Movement ---
        moveTick++;
        switch (moveMode.get()) {
            case Walk -> {
                walkTimer--;
                if (walkTimer <= 0) pickNewWalkDirection();
            }
            case Sneak -> doSneak();
            case Spin  -> doSpin();
        }
    }

    private void pickNewWalkDirection() {
        if (mc.player == null) return;
        float yaw = (float)(Math.random() * 360.0);
        mc.player.setYaw(yaw);
        mc.options.forwardKey.setPressed(true);
        mc.options.sneakKey.setPressed(false);
        walkTimer = wanderInterval.get() * 20;
    }

    private void doSneak() {
        // Toggle sneak every 20 ticks to bob in place (keeps AFK kick away)
        if (moveTick % 20 == 0) {
            sneakOn = !sneakOn;
            mc.options.sneakKey.setPressed(sneakOn);
            mc.options.forwardKey.setPressed(false);
        }
    }

    private void doSpin() {
        mc.options.forwardKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        // Rotate the player's yaw by 5 degrees per tick
        if (mc.player != null) {
            mc.player.setYaw(mc.player.getYaw() + 5f);
        }
    }

    private void stopAllKeys() {
        if (mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        sneakOn = false;
    }
}
