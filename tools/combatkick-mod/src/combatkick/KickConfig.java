package combatkick;

import net.neoforged.neoforge.common.ModConfigSpec;

/** combatkick-client.toml. NeoForge reloads it when the file changes, so values can be tuned while playing. */
final class KickConfig {
    static final ModConfigSpec SPEC;
    static final ModConfigSpec.BooleanValue ENABLED;
    static final ModConfigSpec.DoubleValue STRENGTH;
    static final ModConfigSpec.DoubleValue KICK_DEGREES;
    static final ModConfigSpec.DoubleValue MAX_DEGREES;
    static final ModConfigSpec.DoubleValue FREQUENCY;
    static final ModConfigSpec.DoubleValue DAMPING;
    static final ModConfigSpec.DoubleValue CRIT_MULTIPLIER;
    static final ModConfigSpec.DoubleValue FOV_PUNCH;
    static final ModConfigSpec.BooleanValue FOLLOW_SWING;
    static final ModConfigSpec.BooleanValue KEEP_MOVEMENT;
    static final ModConfigSpec.BooleanValue KILL_ICONS;
    static final ModConfigSpec.BooleanValue KILL_ONLY_HOSTILE;
    static final ModConfigSpec.DoubleValue KILL_STREAK_MIN_SECONDS;
    static final ModConfigSpec.DoubleValue KILL_STREAK_MAX_SECONDS;
    static final ModConfigSpec.IntValue KILL_STREAK_MAX_AT;
    static final ModConfigSpec.DoubleValue KILL_SOUND_VOLUME;
    static final ModConfigSpec.BooleanValue KILL_ECHO;
    static final ModConfigSpec.DoubleValue KILL_ICON_SCALE;
    static final ModConfigSpec.DoubleValue KILL_ICON_HEIGHT;
    static final ModConfigSpec.BooleanValue RAGDOLL_FLING;
    static final ModConfigSpec.DoubleValue RAGDOLL_FLING_SPEED;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        ENABLED = b.comment("Kick the camera when a melee attack hits").define("enabled", true);
        STRENGTH = b.comment("Overall multiplier for every kick (also scaled by the vanilla Screen Effect Scale slider)")
                .defineInRange("strength", 1.0, 0.0, 10.0);
        KICK_DEGREES = b.comment("Kick in degrees for a sword-weight hit; heavier (slower) weapons kick more, lighter ones less")
                .defineInRange("kickDegrees", 3.0, 0.0, 30.0);
        MAX_DEGREES = b.comment("Cap on how far stacked kicks can push the camera, in degrees")
                .defineInRange("maxDegrees", 9.0, 0.0, 45.0);
        FREQUENCY = b.comment("Sway speed in Hz: higher snaps back faster, lower sways longer")
                .defineInRange("frequency", 2.2, 0.2, 20.0);
        DAMPING = b.comment("Damping ratio: 1.0 returns without overshoot, lower values overshoot and sway more")
                .defineInRange("damping", 0.35, 0.0, 2.0);
        CRIT_MULTIPLIER = b.comment("Extra kick on critical (falling) hits")
                .defineInRange("critMultiplier", 1.6, 1.0, 5.0);
        FOV_PUNCH = b.comment("Brief zoom toward the target on a sword-weight hit, as a fraction of FOV (0 disables)")
                .defineInRange("fovPunch", 0.03, 0.0, 0.3);
        FOLLOW_SWING = b.comment("true: the camera is dragged along with the swing; false: it recoils against it")
                .define("followSwing", true);
        KEEP_MOVEMENT = b.comment("Keep walking and sprinting during Heavier Weapons hitstop, which otherwise stops your movement input")
                .define("keepMovementDuringHitstop", true);
        KILL_ICONS = b.comment("Show a kill insignia at the bottom centre and ding when you kill a mob")
                .define("killIcons", true);
        KILL_ONLY_HOSTILE = b.comment("Only count hostile mobs as kills").define("killOnlyHostile", true);
        KILL_STREAK_MIN_SECONDS = b.comment("After a first kill, another within this many seconds continues the streak (the xN counter).",
                        "The insignia stays up for the same time, fading over the last second, and the combat report follows")
                .defineInRange("killStreakMinSeconds", 5.0, 1.0, 300.0);
        KILL_STREAK_MAX_SECONDS = b.comment("The streak window grows steadily with each kill up to this many seconds")
                .defineInRange("killStreakMaxSeconds", 15.0, 1.0, 300.0);
        KILL_STREAK_MAX_AT = b.comment("Streak count at which the window reaches killStreakMaxSeconds")
                .defineInRange("killStreakMaxAt", 20, 2, 1000);
        KILL_SOUND_VOLUME = b.comment("Volume of the kill ding (0 mutes it)").defineInRange("killSoundVolume", 0.6, 0.0, 2.0);
        KILL_ECHO = b.comment("Follow the ding with fading repeats, like an echo").define("killEcho", true);
        KILL_ICON_SCALE = b.comment("Size of the kill insignia").defineInRange("killIconScale", 1.0, 0.25, 4.0);
        KILL_ICON_HEIGHT = b.comment("Height of the insignia's centre above the bottom of the screen, in GUI pixels")
                .defineInRange("killIconHeight", 140.0, 0.0, 400.0);
        RAGDOLL_FLING = b.comment("With Physics Mod ragdolls, drive the body part your killing swing struck the way the swing was going")
                .define("ragdollFling", true);
        RAGDOLL_FLING_SPEED = b.comment("Speed given to the struck body part, in blocks per second, for a sword-weight kill; heavier weapons hit harder")
                .defineInRange("ragdollFlingSpeed", 8.0, 0.0, 40.0);
        SPEC = b.build();
    }

    private KickConfig() {}
}
