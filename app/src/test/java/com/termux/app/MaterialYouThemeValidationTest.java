package com.termux.app;

import com.termux.shared.termux.theme.TermuxThemeUtils;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MaterialYouThemeValidationTest {

    @Test
    public void getAppNightMode_respectsDisabledAndMaterialYouVariants() {
        Assert.assertEquals("true", TermuxThemeUtils.getAppNightMode("true",
            TermuxThemeUtils.MATERIAL_YOU_THEME_DISABLED));
        Assert.assertEquals("false", TermuxThemeUtils.getAppNightMode("false",
            TermuxThemeUtils.MATERIAL_YOU_THEME_DISABLED));
        Assert.assertEquals("system", TermuxThemeUtils.getAppNightMode("system",
            TermuxThemeUtils.MATERIAL_YOU_THEME_DISABLED));

        Assert.assertEquals("false", TermuxThemeUtils.getAppNightMode("true",
            TermuxThemeUtils.MATERIAL_YOU_THEME_LIGHT));
        Assert.assertEquals("true", TermuxThemeUtils.getAppNightMode("false",
            TermuxThemeUtils.MATERIAL_YOU_THEME_DARK));
        Assert.assertEquals("true", TermuxThemeUtils.getAppNightMode("false",
            TermuxThemeUtils.MATERIAL_YOU_THEME_BLACK));
        Assert.assertEquals("system", TermuxThemeUtils.getAppNightMode("false",
            TermuxThemeUtils.MATERIAL_YOU_THEME_SYSTEM));
    }

}
