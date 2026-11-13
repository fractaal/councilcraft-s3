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
        SPEC = b.build();
    }

    private KickConfig() {}
}
