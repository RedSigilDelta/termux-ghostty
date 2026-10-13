package com.termux.app;

import android.view.ContextThemeWrapper;

import com.termux.R;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.ThemeUtils;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

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

    @Test
    public void blackTheme_resolvesBlackExtraKeysBackground() {
        ContextThemeWrapper context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
            R.style.Theme_TermuxActivity_M3_Black_NoActionBar);

        int extraKeysBackground = ThemeUtils.getSystemAttrColor(context,
            com.termux.shared.R.attr.extraKeysButtonBackgroundColor);
        Assert.assertEquals(0xFF000000, extraKeysBackground);
    }

}
