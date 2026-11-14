package startupfix;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * "fractalWARP STARTUP BOOST 9000 ACTIVE" with a spinner and a start-up clock on Drippy's early loading window, in the same place,
 * size and colours as the banner Memory Tweaks draws on the loading screen that follows (see StartupBanner in
 * tools/memtweaks-mod, which this copies the pixel font from). Purely for show.
 *
 * The early window's drawFrame() is patched to hand its provider object to this at the end of every frame
 * (Patches.trackEarlyWindowSize). The window draws with fixed-function OpenGL in window pixels, origin top left.
 * OpenGL is reached through method handles looked up in the provider's class loader: the game loads LWJGL in
 * its own module layer, and naming GL11 here would load a second copy of it that has no OpenGL context.
 */
final class EarlyBanner implements Consumer<Object> {
    static final String KEY = "startupfix.earlyBanner";
    private static final Map<Character, String[]> FONT = new HashMap<>();
    private static final String TEXT = "fractalWARP STARTUP BOOST 9000 ACTIVE";
    private static final int CYAN = 0xFF6FE8FF, WHITE = 0xFFF2F2F2, GOLD = 0xFFFFC83D, GREEN = 0xFF6BFF8A, RED = 0xFFFF5A5A, SHADOW = 0xC0000000;
    private static final int GL_QUADS = 7, GL_TEXTURE_2D = 3553, GL_BLEND = 3042, GL_SRC_ALPHA = 770, GL_ONE_MINUS_SRC_ALPHA = 771;

    static {
        glyph('A', ".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#");
        glyph('B', "####.", "#...#", "#...#", "####.", "#...#", "#...#", "####.");
        glyph('C', ".###.", "#...#", "#....", "#....", "#....", "#...#", ".###.");
        glyph('E', "#####", "#....", "#....", "####.", "#....", "#....", "#####");
        glyph('I', ".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###.");
        glyph('O', ".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
        glyph('P', "####.", "#...#", "#...#", "####.", "#....", "#....", "#....");
        glyph('R', "####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#");
        glyph('S', ".####", "#....", "#....", ".###.", "....#", "....#", "####.");
        glyph('T', "#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#..");
        glyph('U', "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
        glyph('V', "#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#..");
        glyph('W', "#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#");
        glyph('0', ".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###.");
        glyph('9', ".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##..");
        glyph('f', "..##.", ".#..#", ".#...", "###..", ".#...", ".#...", ".#...");
        glyph('r', ".....", ".....", "#.##.", "##..#", "#....", "#....", "#....");
        glyph('a', ".....", ".....", ".###.", "....#", ".####", "#...#", ".####");
        glyph('c', ".....", ".....", ".###.", "#....", "#....", "#...#", ".###.");
        glyph('t', ".#...", ".#...", "###..", ".#...", ".#...", ".#..#", "..##.");
        glyph('l', ".##..", "..#..", "..#..", "..#..", "..#..", "..#..", ".###.");
        glyph('D', "####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####.");
        glyph('L', "#....", "#....", "#....", "#....", "#....", "#....", "#####");
        glyph('1', "..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###.");
        glyph('2', ".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####");
        glyph('3', "####.", "....#", "....#", ".###.", "....#", "....#", "####.");
        glyph('4', "...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#.");
        glyph('5', "#####", "#....", "####.", "....#", "....#", "#...#", ".###.");
        glyph('6', "..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###.");
        glyph('7', "#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#...");
        glyph('8', ".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###.");
        glyph('s', ".....", ".....", ".####", "#....", ".###.", "....#", "####.");
        glyph('/', "....#", "....#", "...#.", "..#..", ".#...", "#....", "#....");
    }

    private final int guiScaleSetting = guiScaleSetting();
    private final long started = ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(System.currentTimeMillis());
    private final long lastStart = lastStart();
    private boolean broken;
    private Field width, height;
    private MethodHandle begin, end, vertex, color, enable, disable, blendFunc;

    private static void glyph(char c, String... rows) {
        FONT.put(c, rows);
    }

    /** False when Memory Tweaks' startupBanner switch is off, so one switch hides both banners. */
    static boolean wanted() {
        try {
            for (String line : Files.readAllLines(Path.of("config", "memtweaks-client.toml"))) {
                if (line.replace(" ", "").equals("startupBanner=false")) return false;
            }
        } catch (Exception e) {
            // no config yet: the switch defaults to on
        }
        return true;
    }

    /** Seconds the previous warm start took, which Memory Tweaks writes when the menu appears; 0 when there is no record. */
    private static long lastStart() {
        try {
            return Long.parseLong(Files.readString(Path.of("startupfix", "last-start.txt")).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Minecraft's guiScale option; 0 (also when unreadable) means "as large as fits". */
    private static int guiScaleSetting() {
        try {
            for (String line : Files.readAllLines(Path.of("options.txt"))) {
                if (line.startsWith("guiScale:")) return Integer.parseInt(line.substring(9).trim());
            }
        } catch (Exception e) {
            // falls through to automatic
        }
        return 0;
    }

    @Override
    public void accept(Object provider) {
        if (broken) return;
        try {
            if (begin == null) link(provider.getClass());
            draw(width.getInt(provider), height.getInt(provider));
        } catch (Throwable t) {
            broken = true;
            Agent.log("early banner switched off: " + t);
        }
    }

    private void link(Class<?> providerClass) throws Exception {
        width = providerClass.getDeclaredField("windowWidth");
        height = providerClass.getDeclaredField("windowHeight");
        width.setAccessible(true);
        height.setAccessible(true);
        Class<?> gl = Class.forName("org.lwjgl.opengl.GL11", true, providerClass.getClassLoader());
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodType oneInt = MethodType.methodType(void.class, int.class);
        end = lookup.findStatic(gl, "glEnd", MethodType.methodType(void.class));
        vertex = lookup.findStatic(gl, "glVertex2f", MethodType.methodType(void.class, float.class, float.class));
        color = lookup.findStatic(gl, "glColor4f", MethodType.methodType(void.class, float.class, float.class, float.class, float.class));
        enable = lookup.findStatic(gl, "glEnable", oneInt);
        disable = lookup.findStatic(gl, "glDisable", oneInt);
        blendFunc = lookup.findStatic(gl, "glBlendFunc", MethodType.methodType(void.class, int.class, int.class));
        begin = lookup.findStatic(gl, "glBegin", oneInt);
    }

    /** Same layout as StartupBanner.render, in Minecraft's GUI pixels, which are `scale` window pixels each. */
    private void draw(int windowWidth, int windowHeight) throws Throwable {
        int scale = 1;
        while (scale != guiScaleSetting && scale < windowWidth && scale < windowHeight
                && windowWidth / (scale + 1) >= 320 && windowHeight / (scale + 1) >= 240) scale++;
        int guiWidth = (windowWidth + scale - 1) / scale, guiHeight = (windowHeight + scale - 1) / scale;
        // The clock after the spinner, as in StartupBanner.render: this start against the previous one.
        long seconds = (System.currentTimeMillis() - started) / 1000;
        boolean coldStart = "cold".equals(System.getProperty("startupfix.cache"));
        String clock = coldStart ? "COLD START " + seconds + "s" : lastStart > 0 ? seconds + "s / " + lastStart + "s" : seconds + "s";
        int clockColor = !coldStart && lastStart > 0 && seconds > lastStart * 5 / 4 ? RED : WHITE;    // a quarter slower than last time
        int textWidth = TEXT.length() * 6 - 1, spinner = 10, gap = 6;
        int x = (guiWidth - (textWidth + gap + spinner + gap + clock.length() * 6 - 1)) / 2, y = guiHeight - 14;
        disable.invokeExact(GL_TEXTURE_2D);
        enable.invokeExact(GL_BLEND);
        blendFunc.invokeExact(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        begin.invokeExact(GL_QUADS);
        text(TEXT, x + 1, y + 1, SHADOW, scale);
        text(TEXT, x, y, 0, scale);
        spinner(x + textWidth + gap, y - 2, scale);
        text(clock, x + textWidth + gap + spinner + gap + 1, y + 1, SHADOW, scale);
        text(clock, x + textWidth + gap + spinner + gap, y, clockColor, scale);
        end.invokeExact();
        disable.invokeExact(GL_BLEND);
        color.invokeExact(1f, 1f, 1f, 1f);
    }

    /** `argb` 0 picks the banner's own colour for each letter. */
    private void text(String text, int x, int y, int argb, int scale) throws Throwable {
        for (int i = 0; i < text.length(); i++) {
            String[] rows = FONT.get(text.charAt(i));
            if (rows == null) continue;
            int c = argb != 0 ? argb : i < 11 ? CYAN : i < 26 ? WHITE : i < 30 ? GOLD : GREEN;
            for (int row = 0; row < 7; row++) {
                String line = rows[row];
                for (int col = 0; col < 5; col++) {
                    if (line.charAt(col) == '#') fill(x + i * 6 + col, y + row, 1, c, scale);
                }
            }
        }
    }

    /** Eight dots in a ring; the bright one goes round ten steps a second with a fading tail. */
    private void spinner(int x, int y, int scale) throws Throwable {
        int[][] ring = {{4, 0}, {7, 1}, {8, 4}, {7, 7}, {4, 8}, {1, 7}, {0, 4}, {1, 1}};
        int head = (int) (System.currentTimeMillis() / 100 % 8);
        for (int i = 0; i < 8; i++) {
            int age = (head - i + 8) % 8;
            int argb = Math.max(70, 255 - age * 30) << 24 | (age == 0 ? 0xFFFFFF : CYAN & 0xFFFFFF);
            fill(x + ring[i][0] + 1, y + ring[i][1] + 1, 2, SHADOW, scale);
            fill(x + ring[i][0], y + ring[i][1], 2, argb, scale);
        }
    }

    /** A square of `size` GUI pixels at GUI position (x, y). */
    private void fill(int x, int y, int size, int argb, int scale) throws Throwable {
        float left = x * scale, top = y * scale, right = (x + size) * scale, bottom = (y + size) * scale;
        color.invokeExact((argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f, (argb >>> 24) / 255f);
        vertex.invokeExact(left, top);
        vertex.invokeExact(left, bottom);
        vertex.invokeExact(right, bottom);
        vertex.invokeExact(right, top);
    }
}
