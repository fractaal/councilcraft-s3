package memtweaks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.fml.loading.FMLPaths;

/**
 * "fractalWARP STARTUP BOOST 9000 ACTIVE" with a spinner, drawn over the loading screen of the first resource
 * load when the pack's startup agent (tools/startupfix) is loaded in this JVM. Purely for show. After the
 * spinner comes a running clock: "32s / 54s" is this start against the previous one (red when it runs well
 * past it), "COLD START 32s" a start that is rebuilding the agent's class cache.
 *
 * When that loading screen closes, the same spot on the menu shows how long the start took, for a few seconds
 * before it fades: "Started in 54s. Would have taken ~105s.", or on a cold start (the first one after any mod
 * change) "Cold start: took 79s. Your next start should be much faster."
 *
 * The "would have taken" figure is an estimate, not a measurement: this start's time multiplied by the ratio
 * measured on one machine on 2026-11-14 between the pack before this work (105 s) and after it (about 53 s with
 * the two JVM flags the README lists, about 60 s without). A cold start already has every fix except the class
 * cache, so comparing warm starts with cold ones would say very little.
 *
 * Minecraft's font isn't usable yet during the first load (fonts are one of the things being loaded), so the
 * banner and clock are drawn from a small 5x7 pixel font kept here, one filled rectangle per pixel.
 */
public final class StartupBanner {
    private static final Map<Character, String[]> FONT = new HashMap<>();
    private static final String TEXT = "fractalWARP STARTUP BOOST 9000 ACTIVE";
    private static final int CYAN = 0xFF6FE8FF, WHITE = 0xFFF2F2F2, GOLD = 0xFFFFC83D, GREEN = 0xFF6BFF8A, RED = 0xFFFF5A5A, SHADOW = 0xC0000000;
    private static final long STARTED = ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(System.currentTimeMillis());
    private static long lastStart = -1;    // seconds the previous warm start took; 0 when there is no record, -1 before the file is read
    private static final long STAT_HOLD_MS = 10_000, STAT_FADE_MS = 5_000;
    private static boolean firstLoadOver;
    private static String stat;
    private static long statSince;

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

    private StartupBanner() {}

    private static void glyph(char c, String... rows) {
        FONT.put(c, rows);
    }

    /** True while the game's first loading screen is up and the startup agent says it is loaded. */
    public static boolean showing() {
        return !firstLoadOver && System.getProperty("startupfix.active") != null && MemTweaks.startupBanner();
    }

    /** Called on every frame that has a screen and no loading overlay: the first call ends the banner for good. */
    public static void firstLoadFinished() {
        if (firstLoadOver) return;
        boolean show = showing();
        firstLoadOver = true;
        if (show) prepareStat();
    }

    /**
     * Start-up time is measured from the start of the Java process. The agent says whether its class cache was
     * there ("warm") or had to be recorded ("cold"). A warm start's time is kept in startupfix/last-start.txt
     * for the next start's clock.
     */
    private static void prepareStat() {
        try {
            long seconds = Math.round((System.currentTimeMillis() - STARTED) / 1000.0);
            if ("cold".equals(System.getProperty("startupfix.cache"))) {
                stat = "Cold start: took " + seconds + "s. Your next start should be much faster.";
            } else {
                Files.createDirectories(lastStartFile().getParent());
                Files.writeString(lastStartFile(), Long.toString(seconds));
                boolean flags = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream().anyMatch(a -> a.startsWith("-XX:SymbolTableSize"));
                long without = Math.round(seconds * (flags ? 2.0 : 1.75) / 5) * 5;    // see the class comment: an estimate
                stat = "Started in " + seconds + "s. Would have taken ~" + without + "s.";
            }
            statSince = System.currentTimeMillis();
            org.apache.logging.log4j.LogManager.getLogger("memtweaks").info("Startup banner: {}", stat);
        } catch (Exception e) {
            stat = null;    // an unwritable file: nothing to show
        }
    }

    private static Path lastStartFile() {
        return FMLPaths.GAMEDIR.get().resolve("startupfix").resolve("last-start.txt");
    }

    private static long lastStart() {
        if (lastStart < 0) {
            try {
                lastStart = Files.exists(lastStartFile()) ? Long.parseLong(Files.readString(lastStartFile()).trim()) : 0;
            } catch (Exception e) {
                lastStart = 0;
            }
        }
        return lastStart;
    }

    /** Draws the start-up time over whatever screen is open, until it has faded or a world is entered. */
    public static void renderStat(GuiGraphics g) {
        if (stat == null) return;
        long age = System.currentTimeMillis() - statSince;
        Minecraft mc = Minecraft.getInstance();
        if (age > STAT_HOLD_MS + STAT_FADE_MS || mc.level != null) {
            stat = null;
            return;
        }
        int alpha = age < STAT_HOLD_MS ? 255 : (int) (255 * (1 - (age - STAT_HOLD_MS) / (double) STAT_FADE_MS));
        if (alpha < 8) return;    // the font treats near-zero alpha as fully opaque
        Font font = mc.font;
        String name = "fractalWARP  ";
        int x = (g.guiWidth() - font.width(name + stat)) / 2, y = g.guiHeight() - 14;
        g.drawString(font, name, x, y, alpha << 24 | (CYAN & 0xFFFFFF), true);
        g.drawString(font, stat, x + font.width(name), y, alpha << 24 | (WHITE & 0xFFFFFF), true);
    }

    public static void render(GuiGraphics g) {
        long seconds = (System.currentTimeMillis() - STARTED) / 1000, last = lastStart();
        boolean coldStart = "cold".equals(System.getProperty("startupfix.cache"));
        String clock = coldStart ? "COLD START " + seconds + "s" : last > 0 ? seconds + "s / " + last + "s" : seconds + "s";
        int clockColor = !coldStart && last > 0 && seconds > last * 5 / 4 ? RED : WHITE;    // a quarter slower than last time
        int width = TEXT.length() * 6 - 1, spinner = 10, gap = 6;
        int x = (g.guiWidth() - (width + gap + spinner + gap + clock.length() * 6 - 1)) / 2, y = g.guiHeight() - 14;
        drawText(g, TEXT, x + 1, y + 1, SHADOW, true);
        drawText(g, TEXT, x, y, 0, true);
        drawSpinner(g, x + width + gap, y - 2);
        drawText(g, clock, x + width + gap + spinner + gap + 1, y + 1, SHADOW, false);
        drawText(g, clock, x + width + gap + spinner + gap, y, clockColor, false);
    }

    /** `color` 0 with `banner` set picks the banner's own colour for each letter. */
    private static void drawText(GuiGraphics g, String text, int x, int y, int color, boolean banner) {
        for (int i = 0; i < text.length(); i++) {
            String[] rows = FONT.get(text.charAt(i));
            if (rows == null) continue;
            int c = color != 0 ? color : i < 11 ? CYAN : i < 26 ? WHITE : i < 30 ? GOLD : GREEN;
            for (int row = 0; row < 7; row++) {
                String line = rows[row];
                for (int col = 0; col < 5; col++) {
                    if (line.charAt(col) == '#') g.fill(x + i * 6 + col, y + row, x + i * 6 + col + 1, y + row + 1, c);
                }
            }
        }
    }

    /** Eight dots in a ring; the bright one goes round ten steps a second with a fading tail. */
    private static void drawSpinner(GuiGraphics g, int x, int y) {
        int[][] ring = {{4, 0}, {7, 1}, {8, 4}, {7, 7}, {4, 8}, {1, 7}, {0, 4}, {1, 1}};
        int head = (int) (System.currentTimeMillis() / 100 % 8);
        for (int i = 0; i < 8; i++) {
            int age = (head - i + 8) % 8;
            int color = Math.max(70, 255 - age * 30) << 24 | (age == 0 ? 0xFFFFFF : CYAN & 0xFFFFFF);
            int dx = x + ring[i][0], dy = y + ring[i][1];
            g.fill(dx + 1, dy + 1, dx + 3, dy + 3, SHADOW);
            g.fill(dx, dy, dx + 2, dy + 2, color);
        }
    }
}
