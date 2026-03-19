package com.gengilys.addon.modules;

import com.gengilys.addon.MapAutoPlaceAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.MathHelper;

import java.util.List;

public class MapAdSpam extends Module {

    private final SettingGroup sgAds      = settings.getDefaultGroup();
    private final SettingGroup sgMovement = settings.createGroup("Movement");

    private final Setting<List<String>> messages = sgAds.add(new StringListSetting.Builder()
        .name("messages")
        .description("Messages to cycle. Use {x} {y} {z} for your coords.")
        .defaultValue(List.of(
            "buying/selling map art! msg me",
            "rare map art for sale at spawn, msg me",
            "map art shop at 0 0! best prices on 2b2t"
        ))
        .build());

    private final Setting<Integer> interval = sgAds.add(new IntSetting.Builder()
        .name("interval-seconds").description("Seconds between messages.")
        .defaultValue(30).min(5).max(300).sliderMin(5).sliderMax(120).build());

    private final Setting<Boolean> randomOrder = sgAds.add(new BoolSetting.Builder()
        .name("random-order").description("Randomise message order instead of cycling.")
        .defaultValue(false).build());

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

    public MapAdSpam() {
        super(MapAutoPlaceAddon.CATEGORY, "map-ad-spam",
            "Spams map ads in chat while keeping you from being AFK kicked.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        msgTimer = 20;
        moveTick = 0;
        sneakOn  = false;
        if (!randomOrder.get()) msgIndex = 0;
        if (moveMode.get() == MoveMode.Walk) wander();
    }

    @Override
    public void onDeactivate() {
        if (sneakOn && mc.player != null) {
            mc.options.sneakKey.setPressed(false);
            sneakOn = false;
        }
        if (mc.options != null) mc.options.forwardKey.setPressed(false);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        moveTick++;

        switch (moveMode.get()) {
            case Walk  -> {
                int wanderTicks = wanderInterval.get() * 20;
                if (moveTick % wanderTicks == 0) wander();
            }
            case Sneak -> doSneak();
            case Spin  -> doSpin();
        }

        msgTimer--;
        if (msgTimer > 0) return;
        msgTimer = interval.get() * 20;

        List<String> msgs = messages.get();
        if (msgs.isEmpty()) return;

        int idx = randomOrder.get()
            ? (int)(Math.random() * msgs.size())
            : (msgIndex++ % msgs.size());

        String msg = msgs.get(idx)
            .replace("{x}", String.valueOf((int) mc.player.getX()))
            .replace("{y}", String.valueOf((int) mc.player.getY()))
            .replace("{z}", String.valueOf((int) mc.player.getZ()));

        sendChat(msg);
    }

    private void wander() {
        if (mc.player == null) return;
        float yaw = (float)(Math.random() * 360);
        mc.player.setYaw(yaw);
        mc.options.forwardKey.setPressed(true);
    }

    private void doSneak() {
        if (moveTick % 20 == 0) {
            sneakOn = !sneakOn;
            mc.options.sneakKey.setPressed(sneakOn);
        }
    }

    private void doSpin() {
        float yaw = (moveTick * 2) % 360f;
        mc.player.setYaw(yaw);
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
            yaw, mc.player.getPitch(),
            mc.player.isOnGround(), mc.player.horizontalCollision));
    }

    private void sendChat(String message) {
        if (message.startsWith("/"))
            mc.player.networkHandler.sendChatCommand(message.substring(1));
        else
            mc.player.networkHandler.sendChatMessage(message);
    }
}
