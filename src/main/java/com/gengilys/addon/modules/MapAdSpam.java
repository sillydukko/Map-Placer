package com.gengilys.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

import java.util.List;

import static com.gengilys.addon.MapAutoPlaceAddon.CATEGORY;

public class MapAdSpam extends Module {

    private final SettingGroup sgMessages = settings.createGroup("Messages");
    private final SettingGroup sgMovement = settings.createGroup("Movement");

    private final Setting<List<String>> messages = sgMessages.add(new StringListSetting.Builder()
        .name("messages")
        .description("Messages to send in chat.")
        .defaultValue(List.of("Check out my map art! /warp maps"))
        .build());

    private final Setting<Integer> msgInterval = sgMessages.add(new IntSetting.Builder()
        .name("interval-seconds")
        .description("Seconds between each message.")
        .defaultValue(30).min(5).max(300).sliderMin(10).sliderMax(120)
        .build());

    private final Setting<Boolean> randomOrder = sgMessages.add(new BoolSetting.Builder()
        .name("random-order").description("Send messages in random order.").defaultValue(false).build());

    private final Setting<MoveMode> moveMode = sgMovement.add(new EnumSetting.Builder<MoveMode>()
        .name("move-mode")
        .description("Sneak: bobs in place. Spin: rotates silently. Walk: walks in random directions.")
        .defaultValue(MoveMode.Sneak).build());

    private final Setting<Integer> wanderInterval = sgMovement.add(new IntSetting.Builder()
        .name("wander-interval-seconds").description("How often to pick a new walk direction.")
        .defaultValue(15).min(5).max(60).sliderMin(5).sliderMax(60)
        .visible(() -> moveMode.get() == MoveMode.Walk).build());

    public enum MoveMode { Walk, Sneak, Spin }

    private int msgIndex = 0;
    private int msgTimer = 0;
    private boolean sneakOn = false;
    private int walkTimer = 0;
    private float spinYaw = 0;

    public MapAdSpam() {
        super(CATEGORY, "map-ad-spam", "Spams chat messages and keeps you moving.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        msgTimer = msgInterval.get() * 20;
        sneakOn = false;
        walkTimer = 0;
        spinYaw = mc.player.getYaw();
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

        // Messages
        msgTimer--;
        if (msgTimer <= 0) {
            msgTimer = msgInterval.get() * 20;
            List<String> msgs = messages.get();
            if (!msgs.isEmpty()) {
                String msg = randomOrder.get()
                    ? msgs.get((int)(Math.random() * msgs.size()))
                    : msgs.get(msgIndex++ % msgs.size());
                mc.player.networkHandler.sendChatMessage(msg);
            }
        }

        // Movement
        switch (moveMode.get()) {
            case Walk -> {
                if (--walkTimer <= 0) pickNewWalkDirection();
            }
            case Sneak -> doSneak();
            case Spin -> doSpin();
        }
    }

    private void pickNewWalkDirection() {
        if (mc.player == null) return;
        // Send silent yaw to server so movement direction changes
        float yaw = (float)(Math.random() * 360.0);
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
            yaw, mc.player.getPitch(), mc.player.isOnGround(), mc.player.horizontalCollision));
        // Also set client yaw so forwardKey actually moves in that direction
        mc.player.setYaw(yaw);
        mc.options.forwardKey.setPressed(true);
        mc.options.sneakKey.setPressed(false);
        walkTimer = wanderInterval.get() * 20;
    }

    private void doSneak() {
        if (mc.player == null) return;
        // Toggle sneak every 20 ticks
        if ((int)(System.currentTimeMillis() / 50) % 20 == 0) {
            sneakOn = !sneakOn;
            mc.options.sneakKey.setPressed(sneakOn);
            mc.options.forwardKey.setPressed(false);
        }
    }

    private void doSpin() {
        if (mc.player == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        // Rotate 5 degrees per tick server-side only (silent)
        spinYaw += 5f;
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
            spinYaw, mc.player.getPitch(), mc.player.isOnGround(), mc.player.horizontalCollision));
    }

    private void stopAllKeys() {
        if (mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        sneakOn = false;
    }
}
