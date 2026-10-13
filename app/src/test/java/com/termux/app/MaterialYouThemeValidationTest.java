package com.termux.app;

import android.util.TypedValue;
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
    public void materialYouThemes_resolveDrawerButtonsToM3Style() {
        assertButtonBarButtonStyle(R.style.Theme_TermuxActivity_M3_Light_NoActionBar,
            R.style.TermuxActivity_Drawer_ButtonBarStyle_M3);
        assertButtonBarButtonStyle(R.style.Theme_TermuxActivity_M3_DayNight_NoActionBar,
            R.style.TermuxActivity_Drawer_ButtonBarStyle_M3);
        assertButtonBarButtonStyle(R.style.Theme_TermuxActivity_M3_Black_NoActionBar,
            R.style.TermuxActivity_Drawer_ButtonBarStyle_M3);
    }

    @Test
    public void legacyTheme_keepsLegacyDrawerButtonStyle() {
        assertButtonBarButtonStyle(R.style.Theme_TermuxActivity_DayNight_NoActionBar,
            R.style.TermuxActivity_Drawer_ButtonBarStyle_Light);
    }

    @Test
    public void blackTheme_resolvesBlackExtraKeysBackground() {
        ContextThemeWrapper context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
            R.style.Theme_TermuxActivity_M3_Black_NoActionBar);

        int extraKeysBackground = ThemeUtils.getSystemAttrColor(context,
            com.termux.shared.R.attr.extraKeysButtonBackgroundColor);
        int drawerBackground = ThemeUtils.getSystemAttrColor(context, R.attr.termuxActivityDrawerBackground);

        Assert.assertEquals(0xFF000000, extraKeysBackground);
        Assert.assertEquals(0xFF000000, drawerBackground);
    }

    private void assertButtonBarButtonStyle(int themeRes, int expectedStyleRes) {
        ContextThemeWrapper context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), themeRes);
        TypedValue typedValue = new TypedValue();
        Assert.assertTrue(context.getTheme().resolveAttribute(android.R.attr.buttonBarButtonStyle, typedValue, true));
        Assert.assertEquals(expectedStyleRes, typedValue.resourceId);
    }

}
