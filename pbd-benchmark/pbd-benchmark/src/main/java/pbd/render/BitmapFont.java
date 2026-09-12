package pbd.render;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimalist 5x7 bitmap font for the stats overlay - hand-drawn and
 * visually verified (rendered at a large magnification) before being
 * ported here, see the development history. Covers uppercase letters,
 * digits, and a few symbols; enough for short labels like "FPS: 60.2".
 *
 * Each character is an array of 7 ints (one row top to bottom), each
 * encoding 5 bits (bit 4 = leftmost column, bit 0 = rightmost column).
 * The atlas is indexed directly by (charCode - 32), so across 96 cells
 * (ASCII 32..127) even though only a subset is drawn here - undefined
 * cells stay blank rather than crashing at render time.
 */
public final class BitmapFont {

    public static final int GLYPH_W = 5;
    public static final int GLYPH_H = 7;
    public static final int CELL_W = GLYPH_W + 1; // 1px margin on the right
    public static final int CELL_H = GLYPH_H + 1; // 1px margin on the bottom
    public static final int ATLAS_COLS = 12;
    public static final int ATLAS_ROWS = 8; // 12*8 = 96 = ASCII range 32..127
    public static final int ATLAS_W = ATLAS_COLS * CELL_W;
    public static final int ATLAS_H = ATLAS_ROWS * CELL_H;

    private static final Map<Character, int[]> GLYPHS = new HashMap<>();

    private static void def(char c, String... rows) {
        int[] bits = new int[GLYPH_H];
        for (int r = 0; r < GLYPH_H; r++) {
            int v = 0;
            String row = rows[r];
            for (int col = 0; col < GLYPH_W; col++) {
                if (row.charAt(col) == '#') {
                    v |= 1 << (GLYPH_W - 1 - col);
                }
            }
            bits[r] = v;
        }
        GLYPHS.put(c, bits);
    }

    static {
        def('0', ".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###.");
        def('1', "..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###.");
        def('2', ".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####");
        def('3', ".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###.");
        def('4', "...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#.");
        def('5', "#####", "#....", "####.", "....#", "....#", "#...#", ".###.");
        def('6', ".###.", "#....", "#....", "####.", "#...#", "#...#", ".###.");
        def('7', "#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#...");
        def('8', ".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###.");
        def('9', ".###.", "#...#", "#...#", ".####", "....#", "....#", ".###.");
        def('A', ".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#");
        def('B', "####.", "#...#", "#...#", "####.", "#...#", "#...#", "####.");
        def('C', ".###.", "#...#", "#....", "#....", "#....", "#...#", ".###.");
        def('D', "####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####.");
        def('E', "#####", "#....", "#....", "####.", "#....", "#....", "#####");
        def('F', "#####", "#....", "#....", "####.", "#....", "#....", "#....");
        def('G', ".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".###.");
        def('H', "#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#");
        def('I', ".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###.");
        def('J', "..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##..");
        def('K', "#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#");
        def('L', "#....", "#....", "#....", "#....", "#....", "#....", "#####");
        def('M', "#...#", "##.##", "#.#.#", "#...#", "#...#", "#...#", "#...#");
        def('N', "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#");
        def('O', ".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
        def('P', "####.", "#...#", "#...#", "####.", "#....", "#....", "#....");
        def('Q', ".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#");
        def('R', "####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#");
        def('S', ".####", "#....", "#....", ".###.", "....#", "....#", "####.");
        def('T', "#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#..");
        def('U', "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
        def('V', "#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#..");
        def('W', "#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#");
        def('X', "#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#");
        def('Y', "#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#..");
        def('Z', "#####", "....#", "...#.", "..#..", ".#...", "#....", "#####");
        def(':', ".....", "..#..", ".....", ".....", "..#..", ".....", ".....");
        def('.', ".....", ".....", ".....", ".....", ".....", "..#..", ".....");
        def('-', ".....", ".....", ".....", "#####", ".....", ".....", ".....");
        def('+', ".....", "..#..", "..#..", "#####", "..#..", "..#..", ".....");
        def('(', "...#.", "..#..", ".#...", ".#...", ".#...", "..#..", "...#.");
        def(')', ".#...", "..#..", "...#.", "...#.", "...#.", "..#..", ".#...");
        def('%', "#...#", "....#", "...#.", "..#..", ".#...", "#....", "#...#");
        def('/', "....#", "...#.", "...#.", "..#..", ".#...", ".#...", "#....");
        def('[', ".###.", ".#...", ".#...", ".#...", ".#...", ".#...", ".###.");
        def(']', ".###.", "...#.", "...#.", "...#.", "...#.", "...#.", ".###.");
        def(',', ".....", ".....", ".....", ".....", ".....", "..#..", ".#...");
        def(' ', ".....", ".....", ".....", ".....", ".....", ".....", ".....");
    }

    private BitmapFont() {}

    /** UV rectangle [u0,v0,u1,v1] of the character in the atlas, or the space's if undefined. */
    public static float[] uvOf(char c) {
        int code = (int) c;
        int index = (code >= 32 && code < 32 + ATLAS_COLS * ATLAS_ROWS) ? (code - 32) : 0;
        int col = index % ATLAS_COLS;
        int row = index / ATLAS_COLS;
        float u0 = (col * CELL_W) / (float) ATLAS_W;
        float v0 = (row * CELL_H) / (float) ATLAS_H;
        float u1 = (col * CELL_W + GLYPH_W) / (float) ATLAS_W;
        float v1 = (row * CELL_H + GLYPH_H) / (float) ATLAS_H;
        return new float[]{u0, v0, u1, v1};
    }

    /** Pixels of the full atlas, one byte per pixel (255=lit, 0=unlit), row by row. */
    public static byte[] buildAtlasPixels() {
        byte[] pixels = new byte[ATLAS_W * ATLAS_H];
        for (Map.Entry<Character, int[]> entry : GLYPHS.entrySet()) {
            int code = entry.getKey();
            if (code < 32) continue;
            int index = code - 32;
            int col = index % ATLAS_COLS;
            int row = index / ATLAS_COLS;
            int baseX = col * CELL_W;
            int baseY = row * CELL_H;
            int[] bits = entry.getValue();
            for (int gy = 0; gy < GLYPH_H; gy++) {
                for (int gx = 0; gx < GLYPH_W; gx++) {
                    boolean on = ((bits[gy] >> (GLYPH_W - 1 - gx)) & 1) != 0;
                    int px = baseX + gx;
                    int py = baseY + gy;
                    pixels[py * ATLAS_W + px] = (byte) (on ? 255 : 0);
                }
            }
        }
        return pixels;
    }
}
