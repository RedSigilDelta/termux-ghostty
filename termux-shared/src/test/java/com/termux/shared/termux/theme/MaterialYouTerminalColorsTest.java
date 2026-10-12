package com.termux.shared.termux.theme;

import com.google.android.material.color.utilities.Contrast;
import com.google.android.material.color.utilities.Hct;

import org.junit.Test;

import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class MaterialYouTerminalColorsTest {

    private static final int[] HUES = {0, 60, 120, 200, 280, 330};
    private static final double[] CHROMAS = {4, 24, 48};
    private static final double[] LIGHT_SURFACE_TONES = {94, 98, 99};
    private static final int[] CHROMATIC_SLOTS = {1, 2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 14};

    @Test
    public void lightTheme_chromaticSlotsMeetContrastOnSurface() {
        for (double hue : HUES) {
            for (double chroma : CHROMAS) {
                for (double surfaceTone : LIGHT_SURFACE_TONES) {
                    Properties properties = terminal(false, hue, chroma, surfaceTone);
                    double backgroundTone = toneOf(properties, "background");
                    for (int slot : CHROMATIC_SLOTS) {
                        double ratio = Contrast.ratioOfTones(toneOf(properties, "color" + slot), backgroundTone);
                        assertTrue("slot " + slot + " hue " + hue + " chroma " + chroma
                            + " surface " + surfaceTone + " ratio " + ratio, ratio >= 4.5);
                    }
                }
            }
        }
    }

    @Test
    public void lightTheme_herdrRowsAndSecondaryTextStayReadable() {
        for (double hue : HUES) {
            for (double chroma : CHROMAS) {
                for (double surfaceTone : LIGHT_SURFACE_TONES) {
                    Properties properties = terminal(false, hue, chroma, surfaceTone);
                    double foreground = toneOf(properties, "foreground");
                    double surface = toneOf(properties, "background");
                    double muted = toneOf(properties, "color7");
                    double rowBackground = toneOf(properties, "color8");
                    assertTrue("foreground on row " + Contrast.ratioOfTones(foreground, rowBackground),
                        Contrast.ratioOfTones(foreground, rowBackground) >= 4.5);
                    assertTrue("muted on row " + Contrast.ratioOfTones(muted, rowBackground),
                        Contrast.ratioOfTones(muted, rowBackground) >= 3.0);
                    assertTrue("row on surface " + Contrast.ratioOfTones(rowBackground, surface),
                        Contrast.ratioOfTones(rowBackground, surface) >= 3.0);
                    assertTrue("muted on surface " + Contrast.ratioOfTones(muted, surface),
                        Contrast.ratioOfTones(muted, surface) >= 4.5);
                }
            }
        }
    }

    @Test
    public void lightTheme_brightSlotsStayDistinctFromNormal() {
        for (double hue : HUES) {
            for (double chroma : CHROMAS) {
                Properties properties = terminal(false, hue, chroma, 98);
                assertNotEquals(properties.getProperty("color1"), properties.getProperty("color9"));
                assertNotEquals(properties.getProperty("color2"), properties.getProperty("color10"));
                assertNotEquals(properties.getProperty("color4"), properties.getProperty("color12"));
                assertNotEquals(properties.getProperty("color5"), properties.getProperty("color13"));
                assertNotEquals(properties.getProperty("color6"), properties.getProperty("color14"));
            }
        }
    }

    @Test
    public void darkTheme_keepsContainerRolesForSlots3And12() {
        Properties properties = terminal(true, 200, 48, 6);
        String primaryContainer = hex(argb(200, 48, 30));
        assertEquals(primaryContainer, properties.getProperty("color3"));
        assertEquals(primaryContainer, properties.getProperty("color12"));
    }

    private static Properties terminal(boolean isDarkTheme, double hue, double chroma, double surfaceTone) {
        return MaterialYouTerminalColors.toTerminalProperties(isDarkTheme,
            argb(hue, 4, surfaceTone),
            argb(hue, 10, isDarkTheme ? 90 : 10),
            argb(hue, 12, isDarkTheme ? 80 : 35),
            argb(hue, 8, isDarkTheme ? 60 : 55),
            argb(hue, 6, isDarkTheme ? 24 : 90),
            argb(25, 84, isDarkTheme ? 80 : 40),
            argb(25, 84, isDarkTheme ? 30 : 90),
            argb(hue, chroma, isDarkTheme ? 80 : 40),
            argb(hue, chroma, isDarkTheme ? 30 : 90),
            argb((hue + 40) % 360, chroma, isDarkTheme ? 80 : 40),
            argb((hue + 40) % 360, chroma, isDarkTheme ? 30 : 90),
            argb((hue + 80) % 360, chroma, isDarkTheme ? 80 : 40),
            argb((hue + 80) % 360, chroma, isDarkTheme ? 30 : 90));
    }

    private static int argb(double hue, double chroma, double tone) {
        return Hct.from(hue, chroma, tone).toInt();
    }

    private static double toneOf(Properties properties, String key) {
        int rgb = Integer.parseInt(properties.getProperty(key).substring(1), 16);
        return Hct.fromInt(0xFF000000 | rgb).getTone();
    }

    private static String hex(int argb) {
        return String.format("#%06x", argb & 0xFFFFFF);
    }
}
