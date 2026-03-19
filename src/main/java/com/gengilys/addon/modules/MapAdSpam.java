package com.gengily.map.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.ICustomGoalProcess;
import com.gengily.map.GengilyMap;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public class MapAdSpam extends Module {

    private final SettingGroup sgAds      = settings.getDefaultGroup();
    private final SettingGroup sgMovement = settings.createGroup("Movement");

    // ── Ad settings ───────────────────────────────────────────────────────
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
        .name("interval-seconds")
        .description("Seconds between messages.")
        .defaultValue(30).min(5).max(300).sliderMin(5).sliderMax(120)
        .build());

    private final Setting<Boolean> randomOrder = sgAds.add(new BoolSetting.Builder()
        .name("random-order")
        .description("Randomise message order instead of cycling.")
        .defaultValue(false)
        .build());

    // ── Movement settings ─────────────────────────────────────────────────
    private final Setting<MoveMode> moveMode = sgMovement.add(new EnumSetting.Builder<MoveMode>()
        .name("move-mode")
        .description("Baritone: wanders around. Sneak: bobs in place. Spin: just rotates.")
        .defaultValue(MoveMode.Baritone)
        .build());

    private final Setting<Double> wanderRadius = sgMovement.add(new DoubleSetting.Builder()
        .name("wander-radius")
        .description("How far Baritone wanders from your starting position.")
        .defaultValue(10.0).min(3.0).max(50.0).sliderMin(3.0).sliderMax(30.0)
        .visible(() -> moveMode.get() == MoveMode.Baritone)
        .build());

    private final Setting<Integer> wanderInterval = sgMovement.add(new IntSetting.Builder()
        .name("wander-interval-seconds")
        .description("How often Baritone picks a new random destination.")
        .defaultValue(15).min(5).max(60).sliderMin(5).sliderMax(60)
        .visible(() -> moveMode.get() == MoveMode.Baritone)
        .build());

    public enum MoveMode { Baritone, Sneak, Spin }

    // ── State ─────────────────────────────────────────────────────────────
    private int msgIndex  = 0;
    private int msgTimer  = 0;
    private int moveTick  = 0;
    private boolean sneakOn = false;
    private Vec3d origin  = null;

    public MapAdSpam() {
        super(GengilyMap.CATEGORY, "map-ad-spam",
            "Spams map ads in chat at spawn while keeping you from being AFK kicked.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        msgTimer  = 20;
        moveTick  = 0;
        sneakOn   = false;
        origin    = mc.player.getPos();
        if (!randomOrder.get()) msgIndex = 0;
        if (moveMode.get() == MoveMode.Baritone) wander();
    }

    @Override
    public void onDeactivate() {
        if (sneakOn && mc.player != null) {
            mc.options.sneakKey.setPressed(false);
            sneakOn = false;
        }
        if (moveMode.get() == MoveMode.Baritone) stopBaritone();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        moveTick++;

        // ── Movement ──────────────────────────────────────────────────────
        switch (moveMode.get()) {
            case Baritone -> {
                int wanderTicks = wanderInterval.get() * 20;
                if (moveTick % wanderTicks == 0) wander();
            }
            case Sneak -> doSneak();
            case Spin  -> doSpin();
        }

        // ── Message ───────────────────────────────────────────────────────
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

    // ── Baritone ──────────────────────────────────────────────────────────
    private void wander() {
        if (mc.player == null || origin == null) return;
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            ICustomGoalProcess cgp = baritone.getCustomGoalProcess();

            double angle = Math.random() * Math.PI * 2;
            double r = wanderRadius.get();
            int tx = (int)(origin.x + Math.cos(angle) * r);
            int tz = (int)(origin.z + Math.sin(angle) * r);

            cgp.setGoalAndPath(new GoalXZ(tx, tz));
        } catch (Exception e) {
            // Baritone unavailable at runtime — fall back to spin silently
        }
    }

    private void stopBaritone() {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            baritone.getCustomGoalProcess().onLostControl();
        } catch (Exception ignored) {}
    }

    // ── Fallback movement ─────────────────────────────────────────────────
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

    // ── Chat ──────────────────────────────────────────────────────────────
    private void sendChat(String message) {
        if (message.startsWith("/"))
            mc.player.networkHandler.sendChatCommand(message.substring(1));
        else
            mc.player.networkHandler.sendChatMessage(message);
    }
}
