package fi.alavesa.vitals;

import java.awt.image.BufferedImage;

/** Placeholder pixel art for the content pack - 16x16 items, 8x8 HUD icons. Replace the PNGs to restyle. */
final class Textures {
    private Textures() { }

    private static final int T = 0x00000000;
    private static final int W = 0xFFF4F1EA, WS = 0xFFC9C4B8, R = 0xFFD32F2F, RD = 0xFF8E1B1B, G = 0xFF4CAF50;
    private static final int BR = 0xFFA06A3C, BRD = 0xFF6B4423, O = 0xFF222222, Y = 0xFFFFD54F, CY = 0xFF7FD3FF;

    private static BufferedImage img(int w, int h) { return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB); }
    private static void px(BufferedImage i, int x, int y, int c) { if (x >= 0 && y >= 0 && x < i.getWidth() && y < i.getHeight()) i.setRGB(x, y, c); }
    private static void rect(BufferedImage i, int x, int y, int w, int h, int c) { for (int a = 0; a < w; a++) for (int b = 0; b < h; b++) px(i, x + a, y + b, c); }
    private static void frame(BufferedImage i, int x, int y, int w, int h, int c) { rect(i, x, y, w, 1, c); rect(i, x, y + h - 1, w, 1, c); rect(i, x, y, 1, h, c); rect(i, x + w - 1, y, 1, h, c); }

    /** A rolled bandage: white roll with a shaded core and a loose tail. */
    static BufferedImage bandage() {
        BufferedImage i = img(16, 16);
        rect(i, 3, 4, 9, 8, W); frame(i, 3, 4, 9, 8, WS); frame(i, 2, 3, 11, 10, O);
        rect(i, 6, 6, 3, 4, WS); px(i, 7, 7, O); px(i, 7, 8, O);
        rect(i, 11, 9, 4, 2, W); rect(i, 11, 11, 4, 1, WS); px(i, 15, 9, O); px(i, 15, 10, O); px(i, 15, 11, O);
        for (int y = 5; y < 11; y += 2) { px(i, 4, y, WS); px(i, 10, y, WS); }
        return i;
    }

    /** A red first-aid box with a white cross and a handle. */
    static BufferedImage firstAidKit() {
        BufferedImage i = img(16, 16);
        rect(i, 2, 5, 12, 9, R); frame(i, 2, 5, 12, 9, RD); frame(i, 1, 4, 14, 11, O);
        rect(i, 6, 3, 4, 2, RD); frame(i, 5, 2, 6, 3, O);
        rect(i, 7, 7, 2, 5, W); rect(i, 5, 8, 6, 2, W); px(i, 7, 7, WS); px(i, 5, 8, WS);
        return i;
    }

    /** Two wooden slats tied with bandage. */
    static BufferedImage splint() {
        BufferedImage i = img(16, 16);
        for (int k = 0; k < 11; k++) { px(i, 2 + k, 12 - k, BR); px(i, 3 + k, 12 - k, BRD); px(i, 1 + k, 12 - k, O); px(i, 4 + k, 12 - k, O); }
        for (int k = 0; k < 11; k++) { px(i, 4 + k, 14 - k, BR); px(i, 5 + k, 14 - k, BRD); px(i, 6 + k, 14 - k, O); }
        rect(i, 4, 9, 4, 2, W); rect(i, 9, 4, 4, 2, W); frame(i, 3, 8, 6, 4, WS); frame(i, 8, 3, 6, 4, WS);
        return i;
    }

    /** 8x8 lightning bolt (stamina). */
    static BufferedImage staminaIcon() {
        BufferedImage i = img(8, 8);
        int[][] p = {{4,0},{3,1},{2,2},{3,3},{4,3},{3,4},{2,5},{1,6},{3,5},{2,6},{1,7}};
        for (int[] q : p) px(i, q[0], q[1], Y);
        px(i, 5, 0, O); px(i, 4, 1, O); px(i, 3, 2, O); px(i, 5, 3, O); px(i, 4, 4, O); px(i, 3, 6, O); px(i, 2, 7, O);
        return i;
    }

    /** 8x8 heart with a crack (injuries). */
    static BufferedImage injuryIcon() {
        BufferedImage i = img(8, 8);
        int[][] p = {{1,1},{2,1},{5,1},{6,1},{0,2},{1,2},{2,2},{3,2},{4,2},{5,2},{6,2},{7,2},{0,3},{1,3},{2,3},{3,3},{4,3},{5,3},{6,3},{7,3},
                     {1,4},{2,4},{3,4},{4,4},{5,4},{6,4},{2,5},{3,5},{4,5},{5,5},{3,6},{4,6}};
        for (int[] q : p) px(i, q[0], q[1], R);
        px(i, 4, 2, W); px(i, 3, 3, W); px(i, 4, 4, W); px(i, 3, 5, W);
        px(i, 1, 1, RD); px(i, 0, 2, RD); px(i, 6, 1, RD); px(i, 7, 2, RD);
        return i;
    }
}
