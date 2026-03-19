package com.gengilys.addon.modules;

import com.gengilys.addon.MapAutoPlaceAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class MapPlacer extends Module {

    private final SettingGroup sgGeneral   = settings.getDefaultGroup();
    private final SettingGroup sgReplace   = settings.createGroup("Replace");
    private final SettingGroup sgFilter    = settings.createGroup("Map Filter");
    private final SettingGroup sgHighlight = settings.createGroup("Highlight");

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Ticks between placements.")
        .defaultValue(6).min(1).max(20).sliderMin(1).sliderMax(20).build());

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range").description("Max reach to item frames.")
        .defaultValue(4.0).min(1.0).max(4.5).sliderMin(1.0).sliderMax(4.5).build());

    private final Setting<Boolean> faceFrame = sgGeneral.add(new BoolSetting.Builder()
        .name("face-frame").description("Look at frame server-side before interacting.")
        .defaultValue(true).build());

    private final Setting<Boolean> returnToFrame = sgGeneral.add(new BoolSetting.Builder()
        .name("return-to-frame-slot").description("Switch back to item frame slot after placing.")
        .defaultValue(true).build());

    private final Setting<Boolean> replaceMode = sgReplace.add(new BoolSetting.Builder()
        .name("replace-filled-frames").description("Break filled frames and replace with your map.")
        .defaultValue(false).build());

    private final Setting<FilterMode> filterMode = sgReplace.add(new EnumSetting.Builder<FilterMode>()
        .name("filter-mode")
        .description("Whitelist = only break maps with these IDs. Blacklist = never break maps with these IDs.")
        .defaultValue(FilterMode.Blacklist)
        .visible(replaceMode::get).build());

    private final Setting<String> mapIdList = sgReplace.add(new StringSetting.Builder()
        .name("map-ids")
        .description("Comma-separated map IDs to whitelist or blacklist.")
        .defaultValue("")
        .visible(replaceMode::get).build());

    private final Setting<Boolean> protectOwn = sgReplace.add(new BoolSetting.Builder()
        .name("protect-own-placed")
        .description("Never break maps that this session's MapPlacer placed.")
        .defaultValue(true)
        .visible(replaceMode::get).build());

    private final Setting<FilterMode> hotbarFilterMode = sgFilter.add(new EnumSetting.Builder<FilterMode>()
        .name("hotbar-filter-mode")
        .description("Whitelist = only place these map IDs. Blacklist = skip these map IDs.")
        .defaultValue(FilterMode.Blacklist).build());

    private final Setting<String> hotbarMapIds = sgFilter.add(new StringSetting.Builder()
        .name("hotbar-map-ids")
        .description("Comma-separated map IDs to filter in your hotbar.")
        .defaultValue("").build());

    private final Setting<Boolean> highlightEnabled = sgHighlight.add(new BoolSetting.Builder()
        .name("highlight").defaultValue(true).build());

    private final Setting<SettingColor> emptyColor = sgHighlight.add(new ColorSetting.Builder()
        .name("empty-frame-color").defaultValue(new SettingColor(0, 255, 100, 160))
        .visible(highlightEnabled::get).build());

    private final Setting<SettingColor> filledColor = sgHighlight.add(new ColorSetting.Builder()
        .name("filled-frame-color").defaultValue(new SettingColor(255, 180, 0, 120))
        .visible(highlightEnabled::get).build());

    private final Setting<SettingColor> protectedColor = sgHighlight.add(new ColorSetting.Builder()
        .name("protected-frame-color").description("Color for frames protected by filter.")
        .defaultValue(new SettingColor(80, 80, 255, 120))
        .visible(highlightEnabled::get).build());

    private final Setting<ShapeMode> shapeMode = sgHighlight.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").defaultValue(ShapeMode.Both)
        .visible(highlightEnabled::get).build());

    public enum FilterMode { Whitelist, Blacklist }
    private enum State { IDLE, WAIT_AFTER_BREAK, SWITCH_BACK }

    private State state = State.IDLE;
    private int waitTicks = 0;
    private int prevSlot = -1;
    private Vec3d targetBreakPos = null;
    private final Random random = new Random();
    private final Set<Integer> ownPlacedMapIds = new HashSet<>();
    private final Set<Integer> placedFrameIds  = new HashSet<>();

    public MapPlacer() {
        super(MapAutoPlaceAddon.CATEGORY, "map-placer",
            "Automatically places maps into nearby item frames.");
    }

    @Override
    public void onActivate() {
        state = State.IDLE;
        waitTicks = 0;
        prevSlot = -1;
        targetBreakPos = null;
        placedFrameIds.clear();
    }

    @Override
    public void onDeactivate() {
        if (prevSlot != -1 && mc.player != null)
            mc.player.getInventory().setSelectedSlot(prevSlot);
        prevSlot = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        placedFrameIds.removeIf(id -> {
            for (Entity e : mc.world.getEntities())
                if (e.getId() == id && e instanceof ItemFrameEntity f)
                    return isMapItem(f.getHeldItemStack());
            return true;
        });

        switch (state) {
            case IDLE -> {
                waitTicks--;
                if (waitTicks > 0) return;

                int mapSlot = findMapInHotbar();
                if (mapSlot == -1) return;

                ItemFrameEntity target = findTarget(mapSlot);
                if (target == null) return;

                if (faceFrame.get()) lookAt(center(target));

                boolean filled = !target.getHeldItemStack().isEmpty();

                if (replaceMode.get() && filled) {
                    if (!shouldBreak(target)) return;
                    mc.interactionManager.attackEntity(mc.player, target);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    targetBreakPos = target.getPos();
                    state = State.WAIT_AFTER_BREAK;
                    waitTicks = 10;
                } else if (!filled) {
                    prevSlot = mc.player.getInventory().getSelectedSlot();
                    mc.player.getInventory().setSelectedSlot(mapSlot);
                    int placedId = getMapId(mc.player.getInventory().getStack(mapSlot));
                    if (placedId >= 0) ownPlacedMapIds.add(placedId);
                    doPlace(target);
                    state = State.SWITCH_BACK;
                    waitTicks = 2;
                }
            }

            case WAIT_AFTER_BREAK -> {
                waitTicks--;
                if (waitTicks > 0) return;

                int mapSlot = findMapInHotbar();
                if (mapSlot == -1) { state = State.IDLE; waitTicks = computeDelay(); return; }

                ItemFrameEntity newFrame = findFrameAt(targetBreakPos);
                if (newFrame == null || !newFrame.getHeldItemStack().isEmpty()) {
                    waitTicks = 5; return;
                }

                if (faceFrame.get()) lookAt(center(newFrame));

                prevSlot = mc.player.getInventory().getSelectedSlot();
                mc.player.getInventory().setSelectedSlot(mapSlot);
                int placedId = getMapId(mc.player.getInventory().getStack(mapSlot));
                if (placedId >= 0) ownPlacedMapIds.add(placedId);
                doPlace(newFrame);
                targetBreakPos = null;
                state = State.SWITCH_BACK;
                waitTicks = 2;
            }

            case SWITCH_BACK -> {
                waitTicks--;
                if (waitTicks > 0) return;
                if (returnToFrame.get()) {
                    int fs = findItemFrameInHotbar();
                    mc.player.getInventory().setSelectedSlot(
                        fs != -1 ? fs : (prevSlot != -1 ? prevSlot : mc.player.getInventory().getSelectedSlot()));
                } else if (prevSlot != -1) {
                    mc.player.getInventory().setSelectedSlot(prevSlot);
                }
                prevSlot = -1;
                state = State.IDLE;
                waitTicks = computeDelay();
            }
        }
    }

    private boolean shouldBreak(ItemFrameEntity frame) {
        ItemStack held = frame.getHeldItemStack();
        if (!isMapItem(held)) return true;
        int mapId = getMapId(held);
        if (protectOwn.get() && mapId >= 0 && ownPlacedMapIds.contains(mapId)) return false;
        Set<Integer> ids = parseIds(mapIdList.get());
        return switch (filterMode.get()) {
            case Blacklist -> !ids.contains(mapId);
            case Whitelist ->  ids.contains(mapId);
        };
    }

    private boolean hotbarMapAllowed(ItemStack stack) {
        if (!isMapItem(stack)) return false;
        int mapId = getMapId(stack);
        Set<Integer> ids = parseIds(hotbarMapIds.get());
        if (ids.isEmpty()) return true;
        return switch (hotbarFilterMode.get()) {
            case Blacklist -> !ids.contains(mapId);
            case Whitelist ->  ids.contains(mapId);
        };
    }

    private ItemFrameEntity findTarget(int mapSlot) {
        Vec3d pos = mc.player.getPos();
        ItemFrameEntity bestEmpty = null, bestFilled = null;
        double distEmpty = Double.MAX_VALUE, distFilled = Double.MAX_VALUE;

        for (ItemFrameEntity frame : getNearbyFrames()) {
            if (placedFrameIds.contains(frame.getId())) continue;
            double dist = frame.getPos().distanceTo(pos);
            if (frame.getHeldItemStack().isEmpty()) {
                if (dist < distEmpty) { distEmpty = dist; bestEmpty = frame; }
            } else if (replaceMode.get() && shouldBreak(frame)) {
                if (dist < distFilled) { distFilled = dist; bestFilled = frame; }
            }
        }
        return bestEmpty != null ? bestEmpty : bestFilled;
    }

    private ItemFrameEntity findFrameAt(Vec3d pos) {
        for (Entity e : mc.world.getEntities())
            if (e instanceof ItemFrameEntity f && f.getPos().distanceTo(pos) < 0.5)
                return f;
        return null;
    }

    private List<ItemFrameEntity> getNearbyFrames() {
        Vec3d pos = mc.player.getPos();
        double r = range.get();
        List<ItemFrameEntity> result = new ArrayList<>();
        for (Entity e : mc.world.getEntities())
            if (e instanceof ItemFrameEntity f && f.getPos().distanceTo(pos) <= r)
                result.add(f);
        return result;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!highlightEnabled.get() || mc.player == null || mc.world == null) return;
        for (ItemFrameEntity frame : getNearbyFrames()) {
            boolean hasMap = isMapItem(frame.getHeldItemStack());
            SettingColor color;
            if (!hasMap) {
                color = emptyColor.get();
            } else if (replaceMode.get() && !shouldBreak(frame)) {
                color = protectedColor.get();
            } else {
                color = filledColor.get();
            }
            var box = frame.getBoundingBox().expand(0.02);
            event.renderer.box(box.minX, box.minY, box.minZ,
                               box.maxX, box.maxY, box.maxZ,
                               color, new Color(color.r, color.g, color.b, 255),
                               shapeMode.get(), 0);
        }
    }

    private void doPlace(ItemFrameEntity frame) {
        Vec3d c = center(frame);
        var hit = new EntityHitResult(frame, c);
        var result = mc.interactionManager.interactEntityAtLocation(mc.player, frame, hit, Hand.MAIN_HAND);
        if (result == ActionResult.PASS)
            mc.interactionManager.interactEntity(mc.player, frame, Hand.MAIN_HAND);
        mc.player.swingHand(Hand.MAIN_HAND);
        placedFrameIds.add(frame.getId());
    }

    private int findMapInHotbar() {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (hotbarMapAllowed(s)) return i;
        }
        return -1;
    }

    private int findItemFrameInHotbar() {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() == Items.ITEM_FRAME) return i;
        }
        return -1;
    }

    private boolean isMapItem(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof FilledMapItem;
    }

    private int getMapId(ItemStack stack) {
        if (!isMapItem(stack)) return -1;
        try {
            MapIdComponent comp = stack.get(DataComponentTypes.MAP_ID);
            return comp != null ? comp.id() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private Set<Integer> parseIds(String raw) {
        Set<Integer> ids = new HashSet<>();
        if (raw == null || raw.isBlank()) return ids;
        for (String part : raw.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try { ids.add(Integer.parseInt(part)); } catch (NumberFormatException ignored) {}
        }
        return ids;
    }

    private Vec3d center(ItemFrameEntity frame) {
        return frame.getPos().add(0, frame.getHeight() / 2.0, 0);
    }

    private void lookAt(Vec3d target) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d diff = target.subtract(eye);
        double hDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float yaw   = MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90f);
        float pitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(diff.y, hDist)), -90f, 90f);
        mc.player.networkHandler.sendPacket(
            new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(
                yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
    }

    private int computeDelay() {
        int d = delay.get();
        return d <= 2 ? d : d + random.nextInt(4);
    }
}
