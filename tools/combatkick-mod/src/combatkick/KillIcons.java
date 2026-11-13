package combatkick;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;

/**
 * Kill insignia at the bottom centre of the screen. The most recent kill's portrait sits in the middle with an X
 * through it; every other mob type killed during the streak orbits it, and an "xN" counter under it gets
 * bigger, redder and shakier as the streak grows. Each kill dings, higher each time, with a short echo.
 *
 * The client can't see LivingDeathEvent, but the server sends every tracking client a damage event naming
 * the attacker (arrows included). A mob that dies within 5 s of the local player hurting it is our kill.
 */
final class KillIcons {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int CREDIT_TICKS = 100;
    private static final int MAX_ORBITERS = 10;

    /** Damage events this old with no health drop yet (a hit that did nothing) stop counting toward the next drop. */
    private static final int EVENT_TICKS = 10;
    private static final int REPORT_MIN_STREAK = 2;

    /** Holds the entity itself, because Physics Mod's ragdolls remove a dying mob from the client level before
     *  the next tick, and a removed entity still reports its zero health. */
    private static final class Hit {
        final LivingEntity entity;
        long tick;
        /** Last health seen, to measure damage from the drops after our hits. */
        float health;
        /**
         * Damage events since that reading, from us and from anything else. The server sends each damage event as
         * it happens and the new health at the end of the tick, so a drop is split between the hits that came
         * before it; only hits landing in the same server tick share one.
         */
        int ours, others;
        long lastEventTick;

        Hit(LivingEntity entity, long tick) {
            this.entity = entity;
            this.tick = tick;
            this.health = entity.getHealth();
        }
    }
    private static final Map<Integer, Hit> recentHits = new HashMap<>();

    /** Damage dealt since the last streak ended or since combat went quiet, in health points (2 per heart). */
    private static float sessionDamage;
    private static long lastDamageMs, damagePulseMs = -1;
    private record Report(int kills, float damage, int color, long atMs) {}
    private static Report report;

    private static int streak;
    private static long lastKillMs, shownAtMs = -1;
    /** Mob types killed this streak, least recent first. */
    private static final LinkedHashMap<EntityType<?>, Boolean> streakTypes = new LinkedHashMap<>();
    /** One unticked copy per mob type, posed for its portrait. Null when a type can't be created on the client. */
    private static final Map<EntityType<?>, LivingEntity> portraits = new HashMap<>();
    private static ClientLevel portraitLevel;

    private record Echo(long atMs, SoundEvent sound, float pitch, float volume) {}
    private static final List<Echo> pendingSounds = new ArrayList<>();

    private static long clockMs, lastRealMs = -1;

    /**
     * Milliseconds that only advance while the game runs, so a singleplayer pause doesn't run out a streak, the
     * insignia, the report or the ding echo. On a server the game never pauses and this is real time.
     */
    private static long clock() {
        long real = Util.getMillis();
        if (lastRealMs >= 0 && !Minecraft.getInstance().isPaused()) clockMs += real - lastRealMs;
        lastRealMs = real;
        return clockMs;
    }

    // --- kill detection --------------------------------------------------------------------------------

    /** Called from LivingEntityMixin for every damage event the client receives. */
    static void onDamageEvent(LivingEntity victim, DamageSource source) {
        Minecraft mc = Minecraft.getInstance();
        if (!victim.level().isClientSide || mc.player == null) return;
        long tick = victim.level().getGameTime();
        Hit hit = recentHits.get(victim.getId());
        if (hit != null && hit.entity != victim) hit = null;
        if (source.getEntity() != mc.player) { // someone or something else; only matters for mobs we're fighting
            if (hit != null) {
                hit.others++;
                hit.lastEventTick = tick;
            }
            return;
        }
        if (KickConfig.KILL_ONLY_HOSTILE.get() && !(victim instanceof Enemy)) return;
        if (hit == null) recentHits.put(victim.getId(), hit = new Hit(victim, tick));
        hit.tick = hit.lastEventTick = tick;
        hit.ours++;
    }

    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        playDueSounds(mc);
        if (mc.level == null || mc.player == null) {
            recentHits.clear();
            return;
        }
        long ms = clock();
        if (mc.player.isDeadOrDying()) {
            streak = 0;
            sessionDamage = 0;
        }
        long now = mc.level.getGameTime();
        for (Iterator<Hit> it = recentHits.values().iterator(); it.hasNext(); ) {
            Hit hit = it.next();
            // Death sets the client's copy of health to 0, so the killing blow counts too, capped at what was left.
            float health = Math.max(hit.entity.getHealth(), 0);
            if (now - hit.lastEventTick > EVENT_TICKS) hit.ours = hit.others = 0;
            if (health < hit.health && hit.ours > 0) {
                sessionDamage += (hit.health - health) * hit.ours / (hit.ours + hit.others);
                lastDamageMs = damagePulseMs = ms;
            }
            if (health != hit.health) hit.ours = hit.others = 0;
            hit.health = health;
            if (hit.entity.isDeadOrDying()) {
                it.remove();
                onKill(mc, hit.entity.getType());
            } else if (hit.entity.isRemoved() || now - hit.tick > CREDIT_TICKS) {
                it.remove();
            }
        }
        endStreakIfOver(ms);
    }

    /** The streak window after the n-th kill: killStreakMinSeconds at one kill, growing to the max at killStreakMaxAt. */
    private static long streakWindowMs(int n) {
        double min = KickConfig.KILL_STREAK_MIN_SECONDS.get(), max = Math.max(min, KickConfig.KILL_STREAK_MAX_SECONDS.get());
        double k = Math.clamp((n - 1) / (double) (KickConfig.KILL_STREAK_MAX_AT.get() - 1), 0, 1);
        return (long) ((min + (max - min) * k) * 1000);
    }

    /** A streak ends when no kill comes within the streak window; long enough ones get a combat report. */
    private static void endStreakIfOver(long ms) {
        long window = streakWindowMs(Math.max(streak, 1));
        if (streak > 0 && ms - lastKillMs > window) {
            if (streak >= REPORT_MIN_STREAK) report = new Report(streak, sessionDamage, heatColor(heat(streak)), ms);
            streak = 0;
            streakTypes.clear();
            sessionDamage = 0;
        } else if (streak == 0 && ms - lastDamageMs > window) {
            sessionDamage = 0; // hits that never led to a kill
        }
    }

    private static void onKill(Minecraft mc, EntityType<?> type) {
        if (!KickConfig.KILL_ICONS.get()) return;
        long ms = clock();
        endStreakIfOver(ms);
        report = null;
        streak++;
        streakTypes.remove(type);
        streakTypes.put(type, Boolean.TRUE);
        lastKillMs = shownAtMs = ms;
        bursts.add(new Burst(ms, BURST_RADIUS + BURST_GROWTH * heat(streak), (float) (Math.random() * 360), RANDOM.nextLong()));
        if (bursts.size() > 6) bursts.remove(0);

        float volume = KickConfig.KILL_SOUND_VOLUME.get().floatValue();
        if (volume <= 0) return;
        // A step and a half higher per kill, topping out at Minecraft's maximum pitch around x12.
        float pitch = (float) Math.min(2.0, 0.8 * Math.pow(2, (streak - 1) * 1.5 / 12));
        ding(ms, SoundEvents.EXPERIENCE_ORB_PICKUP, pitch, volume);
        if (streak == 10 || streak == 20 || streak % 40 == 0) ding(ms, SoundEvents.PLAYER_LEVELUP, 1.2f, volume * 0.5f);
        playDueSounds(mc);
    }

    /** The sound now, then fading repeats for an echo. */
    private static void ding(long ms, SoundEvent sound, float pitch, float volume) {
        pendingSounds.add(new Echo(ms, sound, pitch, volume));
        if (!KickConfig.KILL_ECHO.get()) return;
        int[] delays = {110, 230, 370};
        float[] gains = {0.45f, 0.25f, 0.12f};
        for (int i = 0; i < delays.length; i++) pendingSounds.add(new Echo(ms + delays[i], sound, pitch, volume * gains[i]));
    }

    private static void playDueSounds(Minecraft mc) {
        long ms = clock();
        for (Iterator<Echo> it = pendingSounds.iterator(); it.hasNext(); ) {
            Echo e = it.next();
            if (e.atMs > ms) continue;
            it.remove();
            mc.getSoundManager().play(SimpleSoundInstance.forUI(e.sound, e.pitch, e.volume));
        }
    }

    // --- drawing ---------------------------------------------------------------------------------------

    static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(CombatKick.MODID, "kill_icons"), KillIcons::render);
    }

    private static final int WHITE = 0xF4F1EC, SLASH = 0xFF3B30;
    /** Built-in size on top of the killIconScale setting, in GUI units, so it follows the GUI scale. */
    private static final float BASE_SCALE = 1.25f;
    private static final int CENTER_HALF = 12, ORBIT_HALF = 8, ORBIT_RADIUS = 37;
    /** Wiki-style three-quarter view: turned 35 degrees and seen from 25 degrees above. */
    private static final float PORTRAIT_YAW = 215, PORTRAIT_PITCH = (float) Math.toRadians(25);

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.level == null) return;
        if (streak == 0) {
            if (report != null) renderReport(g, mc);
            return;
        }
        if (shownAtMs < 0) return;
        long t = clock() - shownAtMs;
        long linger = streakWindowMs(streak); // up for the whole streak window, fading over its last second
        if (t > linger) return;

        float pop = t < 90 ? lerp(1.7f, 0.92f, t / 90f) : t < 160 ? lerp(0.92f, 1f, (t - 90) / 70f) : 1f;
        float alpha = Math.min(1f, (linger - t) / (float) FADE_MS);
        int a = (int) (alpha * 255);
        float heat = heat(streak);
        int hot = heatColor(heat);
        float base = BASE_SCALE * KickConfig.KILL_ICON_SCALE.get().floatValue(), s = pop * base;
        float shake = heat * heat * 2.5f;
        float cx = g.guiWidth() / 2f, cy = g.guiHeight() - KickConfig.KILL_ICON_HEIGHT.get().floatValue();
        // The frame, its X and the centre portrait shake together as one rigid piece.
        float fx = cx + wobble(0, shake, false) * s, fy = cy + wobble(0, shake, true) * s;
        var pose = g.pose();

        // Starbursts go first, behind everything, at the insignia's base scale; each grows and fades on its own.
        long now = clock();
        bursts.removeIf(b -> now - b.atMs > BURST_MS);
        if (!bursts.isEmpty()) {
            g.flush();
            pose.pushPose();
            pose.translate(cx, cy, 0);
            pose.scale(base, base, 1);
            for (Burst b : bursts) drawBurst(pose.last().pose(), b, now - b.atMs, alpha, hot);
            pose.popPose();
        }

        pose.pushPose();
        pose.translate(fx, fy, 0);
        pose.scale(s, s, 1);
        // A diamond pulse bursting outward on the kill: further, longer, thicker and hotter as the streak grows to x40.
        float grow = (Math.min(streak, PULSE_CAP) - 1) / (float) (PULSE_CAP - 1);
        long pulseMs = (long) (300 + 450 * grow);
        if (t < pulseMs) {
            float k = t / (float) pulseMs;
            float reach = 1 + (1.2f + 3.3f * grow) * ease(k);
            int color = mix(WHITE, hot, grow);
            diamondOutline(g, Math.round(17 * reach), 1 + Math.round(1.5f * grow), argb((int) (a * (0.6f + 0.35f * grow) * (1 - k)), color));
        }
        // The portrait backing fills the diamond exactly; the stroke goes on top later, over the portrait.
        rotated(g, 45, -17, -17, 17, 17, argb(a / 2, 0x101014));
        if (streak >= 10) rotated(g, 45, -17, -17, 17, 17, argb((int) (a * 0.3f), hot)); // glow
        if (streak >= 2) chevron(g, 0, 26, argb(a, hot));
        if (streak >= 3) {
            for (int side = -1; side <= 1; side += 2) {
                bar(g, side * 30, -1, side * 20, 6, 1, argb(a, WHITE)); // clear of the diamond's corners
                bar(g, side * 29, 5, side * 25, 4, 1, argb(a, WHITE));
            }
        }
        if (streak >= 4) {
            for (int i = 0; i < 8; i++) {
                pose.pushPose();
                pose.mulPose(Axis.ZP.rotationDegrees(i * 45 + 22.5f));
                g.fill(-1, -30, 1, -27, argb(a, WHITE));
                pose.popPose();
            }
        }
        pose.popPose();

        // Portraits, in absolute screen coordinates because the scissor ignores the pose. Each orbiter shakes on its own.
        List<EntityType<?>> types = new ArrayList<>(streakTypes.keySet());
        EntityType<?> latest = types.isEmpty() ? null : types.get(types.size() - 1);
        int orbiters = Math.min(types.size() - 1, MAX_ORBITERS);
        double spin = now / 1000.0 * 0.8;
        for (int i = 0; i < orbiters; i++) {
            EntityType<?> type = types.get(types.size() - 2 - i);
            double ang = spin + i * 2 * Math.PI / orbiters;
            float ox = cx + ((float) Math.cos(ang) * ORBIT_RADIUS + wobble(40 + i, shake, false)) * s;
            float oy = cy + ((float) Math.sin(ang) * ORBIT_RADIUS + wobble(40 + i, shake, true)) * s;
            drawPortrait(g, mc, type, ox, oy, ORBIT_HALF * s, false); // bare, no frame or clipping
        }
        if (latest != null) drawPortrait(g, mc, latest, fx, fy, CENTER_HALF * s, true);

        // The diamond stroke over the portrait's edges, and the X slashing in over the portrait, moving with the frame.
        pose.pushPose();
        pose.translate(fx, fy, 300);
        pose.scale(s, s, 1);
        diamondOutline(g, 17, 2, argb(a, WHITE));
        float slash = Math.min(1f, t / 120f);
        int len = Math.round(15 * slash);
        bar(g, 0, 0, 45, len, 1, argb(a, SLASH));
        bar(g, 0, 0, -45, len, 1, argb(a, SLASH));
        pose.popPose();

        // The counter shakes on its own.
        pose.pushPose();
        pose.translate(cx, cy, 300);
        pose.scale(s, s, 1);
        if (streak >= 2) counter(g, mc, t, pop, heat, hot, a);
        pose.popPose();
        damageLine(g, mc, cx, cy, s, heat, a);
    }

    /**
     * The counter jumps up on each kill and eases back to its base size: a sawtooth on its scale. It's drawn
     * inside the insignia's pop, so that is divided out to keep the counter on its own curve.
     */
    private static void counter(GuiGraphics g, Minecraft mc, long t, float pop, float heat, int hot, int a) {
        String bang = streak >= 40 ? "!!!" : streak >= 20 ? "!!" : streak >= 10 ? "!" : "";
        String label = "x" + streak + bang;
        Component text = Component.literal(label).withStyle(streak >= 10 ? ChatFormatting.BOLD : ChatFormatting.RESET);
        float baseSize = 1.1f + heat * 1.4f;
        float k = Math.min(1f, t / (float) PULSE_MS);
        float ts = baseSize * (1 + PULSE * (1 - k) * (1 - k)) / pop;
        var pose = g.pose();
        pose.pushPose();
        shake(pose, 9, heat * heat * 1.5f);
        pose.translate(0, 36 + 4.5f * baseSize, 0); // scale about the text's middle, where it sits at base size
        pose.scale(ts, ts, 1);
        int w = width(mc, label, streak >= 10);
        g.drawString(mc.font, text, -w / 2, -4, argb(Math.max(a, 8), hot), true);
        pose.popPose();
    }

    private static final ResourceLocation HEART = ResourceLocation.withDefaultNamespace("hud/heart/full"),
            HEART_CONTAINER = ResourceLocation.withDefaultNamespace("hud/heart/container");
    private static final int PULSE_CAP = 40;
    private static final long FADE_MS = 1000, DAMAGE_PULSE_MS = 400, REPORT_IN_MS = 900, REPORT_HOLD_MS = 5000, REPORT_OUT_MS = 1200;

    /**
     * Hearts of damage dealt so far, just under the counter, at the normal text size for the GUI scale; it pops a
     * little each time it goes up. Placed in screen units from the insignia's scale s.
     */
    private static void damageLine(GuiGraphics g, Minecraft mc, float cx, float cy, float s, float heat, int a) {
        if (sessionDamage <= 0) return;
        float counterSize = 1.1f + heat * 1.4f;
        float top = cy + (streak >= 2 ? 36 + 9 * counterSize + 2 : 34) * s;
        float k = Math.min(1f, (clock() - damagePulseMs) / (float) DAMAGE_PULSE_MS);
        float size = 1 + 0.3f * (1 - k) * (1 - k);
        float shake = heat * heat * 1.5f;
        var pose = g.pose();
        pose.pushPose();
        pose.translate(cx + wobble(10, shake, false), top + 4.5f + wobble(10, shake, true), 300);
        pose.scale(size, size, 1);
        String amount = hearts(sessionDamage);
        heartAmount(g, mc, amount, -(width(mc, amount, false) + HEART_GAP + 9) / 2, a);
        pose.popPose();
    }

    /**
     * After a streak ends: "x47 KILL STREAK | TOTAL DAMAGE 147.5" and a heart, fading and rising into where the
     * insignia was, with a rule that draws out under it, then fading away.
     */
    private static void renderReport(GuiGraphics g, Minecraft mc) {
        long t = clock() - report.atMs;
        if (t > REPORT_IN_MS + REPORT_HOLD_MS + REPORT_OUT_MS) {
            report = null;
            return;
        }
        float in = ease(Math.min(1f, t / (float) REPORT_IN_MS));
        float out = t < REPORT_IN_MS + REPORT_HOLD_MS ? 1f : 1 - (t - REPORT_IN_MS - REPORT_HOLD_MS) / (float) REPORT_OUT_MS;
        int a = (int) (255 * in * out);
        if (a < 5) return; // the font draws alpha under 4 as opaque
        // Each piece is placed at a width we work out, not one the font reports: see width().
        String kills = "x" + report.kills, label = " KILL STREAK", bar = "   |   ", total = "TOTAL DAMAGE ", amount = hearts(report.damage);
        int[] w = {width(mc, kills, true), width(mc, label, true), width(mc, bar, false), width(mc, total, false)};
        int all = w[0] + w[1] + w[2] + w[3] + width(mc, amount, false) + HEART_GAP + 9;
        float cx = g.guiWidth() / 2f, cy = g.guiHeight() - KickConfig.KILL_ICON_HEIGHT.get().floatValue();
        var pose = g.pose();
        pose.pushPose();
        pose.translate(Math.round(cx), Math.round(cy + 8 * (1 - in)), 300); // normal text size, whole pixels
        int x = -all / 2;
        g.drawString(mc.font, Component.literal(kills).withStyle(ChatFormatting.BOLD), x, -4, argb(a, report.color), true);
        g.drawString(mc.font, Component.literal(label).withStyle(ChatFormatting.BOLD), x += w[0], -4, argb(a, WHITE), true);
        g.drawString(mc.font, bar, x += w[1], -4, argb(a, 0x8C8780), true);
        g.drawString(mc.font, total, x += w[2], -4, argb(a, 0xC9C4BC), true);
        heartAmount(g, mc, amount, x + w[3], a);
        int rule = Math.round(all / 2f * ease(Math.min(1f, Math.max(0, t - 200) / 700f)));
        g.fill(-rule, 7, rule, 8, argb((int) (a * 0.8f), report.color));
        pose.popPose();
    }

    private static final int HEART_GAP = 2;

    /** An amount and then a heart, starting at x, middle at y 0. */
    private static void heartAmount(GuiGraphics g, Minecraft mc, String amount, int x, int a) {
        g.drawString(mc.font, amount, x, -4, argb(Math.max(a, 5), WHITE), true);
        int end = x + width(mc, amount, false);
        g.flush(); // the text is batched, the sprites draw now
        g.setColor(1, 1, 1, a / 255f);
        RenderSystem.enableBlend();
        g.blitSprite(HEART_CONTAINER, end + HEART_GAP, -5, 9, 9);
        g.blitSprite(HEART, end + HEART_GAP, -5, 9, 9);
        g.setColor(1, 1, 1, 1);
    }

    /**
     * Width of a run of text. Bold is added here, one pixel per character as it is drawn, because in this pack the
     * font measures bold text as plain: laying out by Font.width put the heart and the damage figure on top of
     * the bold text before them, short by one pixel per bold character. (Not Emojiful's width hook, which keeps
     * the style; whichever font mixin does it wasn't pinned down.)
     */
    private static int width(Minecraft mc, String text, boolean bold) {
        return mc.font.width(text) + (bold ? text.codePointCount(0, text.length()) : 0);
    }

    /** Health points as hearts, one decimal, dropping a trailing .0. */
    private static String hearts(float damage) {
        float h = Math.round(damage / 2 * 10) / 10f;
        return h == Math.floor(h) ? Integer.toString((int) h) : String.format(java.util.Locale.ROOT, "%.1f", h);
    }

    private static float ease(float k) {
        return 1 - (1 - k) * (1 - k) * (1 - k);
    }

    // --- starburst -------------------------------------------------------------------------------------

    /** Burst radius for a first kill, and how much more it gets at full heat (x40). */
    private static final float BURST_RADIUS = 30, BURST_GROWTH = 45;
    private static final long BURST_MS = 520, PULSE_MS = 650;
    private static final float PULSE = 0.6f;
    private static final Random RANDOM = new Random();

    private record Burst(long atMs, float radius, float spin, long seed) {}
    private static final List<Burst> bursts = new ArrayList<>();

    /**
     * A lens-flare burst: tapered rays of uneven length, a soft core and a long horizontal streak, all drawn
     * additively as gradient triangles. Rays shoot out over the first 140 ms, then everything fades.
     */
    private static void drawBurst(Matrix4f m, Burst b, long t, float alpha, int hot) {
        float grow = 1 - (float) Math.pow(1 - Math.min(1f, t / 140f), 3);
        float fade = alpha * (1 - t / (float) BURST_MS);
        fade *= fade;
        float r = b.radius * (0.35f + 0.65f * grow);
        int core = mix(hot, WHITE, 0.7f);
        Random rnd = new Random(b.seed);

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        int rays = 14 + Math.min(streak, 10);
        for (int i = 0; i < rays; i++) {
            double ang = Math.toRadians(b.spin + t * 0.03 + (i + rnd.nextFloat() * 0.4) * 360.0 / rays);
            float len = r * (i % 2 == 0 ? 1f : 0.55f) * (0.7f + 0.6f * rnd.nextFloat());
            float w = 1.2f + 1.6f * rnd.nextFloat();
            float dx = (float) Math.cos(ang), dy = (float) Math.sin(ang);
            int baseColor = argb((int) (255 * 0.85f * fade), core), tipColor = argb(0, hot);
            vertex(buf, m, -dy * w, dx * w, baseColor);
            vertex(buf, m, dy * w, -dx * w, baseColor);
            vertex(buf, m, dx * len, dy * len, tipColor);
        }
        float streakLen = r * 2.2f, streakHalf = 1.3f; // the anamorphic streak, a long thin diamond
        int mid = argb((int) (255 * 0.7f * fade), core), end = argb(0, hot);
        for (int side = -1; side <= 1; side += 2) {
            vertex(buf, m, 0, -streakHalf, mid);
            vertex(buf, m, 0, streakHalf, mid);
            vertex(buf, m, side * streakLen, 0, end);
        }
        float glow = r * 0.45f; // the soft core
        int centre = argb((int) (255 * 0.8f * fade), WHITE), rim = argb(0, hot);
        for (int i = 0; i < 24; i++) {
            double a0 = i * Math.PI / 12, a1 = (i + 1) * Math.PI / 12;
            vertex(buf, m, 0, 0, centre);
            vertex(buf, m, (float) Math.cos(a0) * glow, (float) Math.sin(a0) * glow, rim);
            vertex(buf, m, (float) Math.cos(a1) * glow, (float) Math.sin(a1) * glow, rim);
        }
        BufferUploader.drawWithShader(buf.buildOrThrow());

        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
    }

    private static void vertex(BufferBuilder buf, Matrix4f m, float x, float y, int argb) {
        buf.addVertex(m, x, y, 0).setColor(argb);
    }

    // --- shaking ---------------------------------------------------------------------------------------

    private static void shake(PoseStack pose, int element, float amount) {
        if (amount > 0) pose.translate(wobble(element, amount, false), wobble(element, amount, true), 0);
    }

    private static final int SHAKE_OCTAVES = 6;
    private static final double SHAKE_HZ = 7, SHAKE_LACUNARITY = 2.1, SHAKE_GAIN = 0.6;
    /** Layers of independent noise partly cancel, so the sum is scaled back up to about the old sine shake's reach. */
    private static final double SHAKE_BOOST = 1.8;

    /**
     * A shake offset that differs per element: fractal noise, six octaves of 1D gradient noise from 7 Hz up, each
     * about twice as fast and 0.6 as strong as the last, so a slow sway carries fine, busy detail. Each element and
     * axis has its own seed. Smooth, and the same at any frame rate.
     */
    private static float wobble(int element, float amount, boolean vertical) {
        if (amount == 0) return 0;
        double t = clock() / 1000.0;
        int seed = element * 2 + (vertical ? 1 : 0);
        double sum = 0, norm = 0, amp = 1, freq = SHAKE_HZ;
        for (int o = 0; o < SHAKE_OCTAVES; o++) {
            sum += amp * noise(t * freq, seed * 131 + o * 17);
            norm += amp;
            amp *= SHAKE_GAIN;
            freq *= SHAKE_LACUNARITY;
        }
        return amount * (float) (SHAKE_BOOST * sum / norm);
    }

    /** 1D gradient (Perlin) noise in about -1..1: a random slope at each whole x, blended with a quintic fade. */
    private static double noise(double x, int seed) {
        long i = (long) Math.floor(x);
        double f = x - i, u = f * f * f * (f * (f * 6 - 15) + 10);
        double a = slope(i, seed) * f, b = slope(i + 1, seed) * (f - 1);
        return 2 * (a + (b - a) * u);
    }

    private static double slope(long i, int seed) {
        long h = i * 0x9E3779B97F4A7C15L ^ seed * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53 * 2 - 1;
    }

    /** Renders a whole mob, fitted into a square of the given half size centred on (x, y), optionally clipped to it. */
    private static void drawPortrait(GuiGraphics g, Minecraft mc, EntityType<?> type, float x, float y, float half, boolean clip) {
        LivingEntity e = portrait(mc, type);
        if (e == null) return;
        float w = e.getBbWidth(), h = e.getBbHeight();
        float cos = (float) Math.cos(PORTRAIT_PITCH), sin = (float) Math.sin(PORTRAIT_PITCH);
        // On-screen size of the turned, tilted bounding box: its diagonal across, its height plus the top face down.
        float across = w * 1.414f, tall = h * cos + w * 1.414f * sin;
        float scale = 0.85f * 2 * half / Math.max(Math.max(across, tall), 0.3f);
        if (clip) g.enableScissor((int) (x - half), (int) (y - half), (int) Math.ceil(x + half), (int) Math.ceil(y + half));
        try {
            // Negative X tilt brings the top toward the viewer; shift down by half the visible height to centre it.
            InventoryScreen.renderEntityInInventory(g, x, y, scale, new Vector3f(0, tall / 2, 0),
                    new Quaternionf().rotateZ((float) Math.PI).rotateX(-PORTRAIT_PITCH), new Quaternionf(), e);
        } catch (RuntimeException ex) {
            LOG.warn("[combatkick] can't draw a portrait of {}", type, ex);
            portraits.put(type, null);
        }
        if (clip) g.disableScissor();
    }

    private static LivingEntity portrait(Minecraft mc, EntityType<?> type) {
        if (portraitLevel != mc.level) {
            portraits.clear();
            portraitLevel = mc.level;
        }
        return portraits.computeIfAbsent(type, ty -> {
            try {
                if (ty.create(mc.level) instanceof LivingEntity e) {
                    e.yBodyRot = e.yBodyRotO = PORTRAIT_YAW;
                    e.setYRot(PORTRAIT_YAW);
                    e.yHeadRot = e.yHeadRotO = PORTRAIT_YAW;
                    e.setXRot(0);
                    return e;
                }
            } catch (RuntimeException ex) {
                LOG.warn("[combatkick] can't create a portrait of {}", ty, ex);
            }
            return null;
        });
    }

    /** 0 for a single kill, about 0.6 at x10, 1 from x40. */
    private static float heat(int n) {
        return n <= 1 ? 0 : (float) Math.min(1, Math.log(n) / Math.log(40));
    }

    /** White, then yellow, orange and red as the streak heats up. */
    private static int heatColor(float h) {
        int[] stops = {0xF4F1EC, 0xFFE14A, 0xFF8A2A, 0xFF2A1F};
        float f = h * (stops.length - 1);
        int i = Math.min((int) f, stops.length - 2);
        return mix(stops[i], stops[i + 1], f - i);
    }

    private static int mix(int c1, int c2, float k) {
        int r = (int) lerp(c1 >> 16 & 255, c2 >> 16 & 255, k), gr = (int) lerp(c1 >> 8 & 255, c2 >> 8 & 255, k), b = (int) lerp(c1 & 255, c2 & 255, k);
        return r << 16 | gr << 8 | b;
    }

    /** A square outline turned 45 degrees, from its half side and line thickness. */
    private static void diamondOutline(GuiGraphics g, int half, int thick, int color) {
        var pose = g.pose();
        pose.pushPose();
        pose.mulPose(Axis.ZP.rotationDegrees(45));
        g.fill(-half, -half, half, -half + thick, color);
        g.fill(-half, half - thick, half, half, color);
        g.fill(-half, -half + thick, -half + thick, half - thick, color);
        g.fill(half - thick, -half + thick, half, half - thick, color);
        pose.popPose();
    }

    /** A V whose point is just below (x, y), drawn as one mitred stroke so the point is sharp. */
    private static void chevron(GuiGraphics g, int x, int y, int color) {
        stroke(g, new float[] {x - 5.8f, y - 2.8f, x, y + 2.8f, x + 5.8f, y - 2.8f}, 1, color);
    }

    /**
     * An open polyline of the given half thickness with mitred joins: each inner corner's offset points sit
     * where the two edges' offset lines meet, so segments join without notches or overlapping butt ends.
     */
    private static void stroke(GuiGraphics g, float[] pts, float half, int color) {
        int n = pts.length / 2;
        float[] left = new float[n * 2], right = new float[n * 2];
        for (int i = 0; i < n; i++) {
            float[] nIn = i > 0 ? normal(pts, i - 1) : null, nOut = i < n - 1 ? normal(pts, i) : null;
            float mx, my, len;
            if (nIn == null) { mx = nOut[0]; my = nOut[1]; len = half; }
            else if (nOut == null) { mx = nIn[0]; my = nIn[1]; len = half; }
            else {
                mx = nIn[0] + nOut[0]; my = nIn[1] + nOut[1];
                float m = (float) Math.hypot(mx, my);
                mx /= m; my /= m;
                len = half / Math.max(mx * nOut[0] + my * nOut[1], 0.25f); // cap very sharp miters
            }
            left[i * 2] = pts[i * 2] + mx * len; left[i * 2 + 1] = pts[i * 2 + 1] + my * len;
            right[i * 2] = pts[i * 2] - mx * len; right[i * 2 + 1] = pts[i * 2 + 1] - my * len;
        }
        g.flush();
        Matrix4f m = g.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < n - 1; i++) {
            int j = i + 1;
            vertex(buf, m, left[i * 2], left[i * 2 + 1], color);
            vertex(buf, m, right[i * 2], right[i * 2 + 1], color);
            vertex(buf, m, left[j * 2], left[j * 2 + 1], color);
            vertex(buf, m, right[i * 2], right[i * 2 + 1], color);
            vertex(buf, m, right[j * 2], right[j * 2 + 1], color);
            vertex(buf, m, left[j * 2], left[j * 2 + 1], color);
        }
        BufferUploader.drawWithShader(buf.buildOrThrow());
        RenderSystem.enableCull();
    }

    /** Unit normal of the segment from point i to point i + 1. */
    private static float[] normal(float[] pts, int i) {
        float dx = pts[i * 2 + 2] - pts[i * 2], dy = pts[i * 2 + 3] - pts[i * 2 + 1];
        float l = (float) Math.hypot(dx, dy);
        return new float[] {-dy / l, dx / l};
    }

    /** A bar centred on (x, y), turned by deg, with half length and half thickness. */
    private static void bar(GuiGraphics g, float x, float y, float deg, int halfLen, int halfThick, int color) {
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.mulPose(Axis.ZP.rotationDegrees(deg));
        g.fill(-halfLen, -halfThick, halfLen, halfThick, color);
        pose.popPose();
    }

    private static void rotated(GuiGraphics g, float deg, int x1, int y1, int x2, int y2, int color) {
        var pose = g.pose();
        pose.pushPose();
        pose.mulPose(Axis.ZP.rotationDegrees(deg));
        g.fill(x1, y1, x2, y2, color);
        pose.popPose();
    }

    private static int argb(int a, int rgb) {
        return Math.clamp(a, 0, 255) << 24 | rgb;
    }

    private static float lerp(float a, float b, float k) {
        return a + (b - a) * k;
    }

    private KillIcons() {}
}
