package com.hhst.youtubelite.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.Constant;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.browser.TabManager;
import com.hhst.youtubelite.downloader.ui.DownloadActivity;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.util.UrlUtils;
import com.tencent.mmkv.MMKV;

import java.util.ArrayList;
import java.util.List;

@UnstableApi
public class NavigationBar extends HorizontalScrollView {

    private LinearLayout container;
    private ExtensionManager extensionManager;
    private TabManager tabManager;
    private MMKV kv;

    public NavigationBar(@NonNull Context context) {
        super(context);
        init();
    }

    public NavigationBar(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setFillViewport(true);
        setHorizontalScrollBarEnabled(false);
        
        setHorizontalFadingEdgeEnabled(true);
        setFadingEdgeLength(dpToPx(32));
        
        container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        addView(container, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpToPx(56)));
        kv = MMKV.defaultMMKV();
        
        ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
            Insets navInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), navInsets.bottom);
            return insets;
        });
    }

    public void setup(ExtensionManager extensionManager, TabManager tabManager) {
        this.extensionManager = extensionManager;
        this.tabManager = tabManager;
    }

    public void update() {
        if (extensionManager == null || tabManager == null) return;

        String currentUrl = tabManager.getWebview() != null ? tabManager.getWebview().getUrl() : null;
        String pageClass = UrlUtils.getPageClass(currentUrl);

        boolean isShorts = Constant.PAGE_SHORTS.equals(pageClass);
        boolean showInShorts = extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.SHOW_NAV_BAR_IN_SHORTS);

        boolean shouldShow = pageClass.equals(Constant.PAGE_HOME) ||
                (isShorts && showInShorts) ||
                pageClass.equals(Constant.PAGE_SUBSCRIPTIONS) ||
                pageClass.equals(Constant.PAGE_LIBRARY) ||
                pageClass.equals(UrlUtils.PAGE_SEARCHING) ||
                pageClass.equals(UrlUtils.PAGE_CHANNEL) ||
                pageClass.equals(UrlUtils.PAGE_USER_MENTION);

        setVisibility(shouldShow ? View.VISIBLE : View.GONE);
        if (!shouldShow) return;

        container.removeAllViews();

        String orderStr = kv.decodeString(com.hhst.youtubelite.extension.Constant.NAV_BAR_ORDER, com.hhst.youtubelite.extension.Constant.DEFAULT_NAV_BAR_ORDER);
        if (orderStr == null) orderStr = com.hhst.youtubelite.extension.Constant.DEFAULT_NAV_BAR_ORDER;
        String[] order = orderStr.split(",");

        boolean signedIn = isUserSignedIn();
        List<NavItemInfo> visibleItems = new ArrayList<>();

        for (String key : order) {
            switch (key) {
                case "home":
                    if (extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.NAV_BAR_SHOW_HOME))
                        visibleItems.add(new NavItemInfo(R.drawable.ic_home, R.string.nav_home, pageClass.equals(Constant.PAGE_HOME), () -> {
                            if (tabManager.getWebview() != null && Constant.HOME_URL.equals(tabManager.getWebview().getUrl())) {
                                tabManager.getWebview().reload();
                            } else {
                                tabManager.openTab(Constant.HOME_URL, Constant.PAGE_HOME);
                            }
                        }));
                    break;
                case "shorts":
                    if (extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.NAV_BAR_SHOW_SHORTS))
                        visibleItems.add(new NavItemInfo(R.drawable.ic_shorts, R.string.nav_shorts, pageClass.equals(Constant.PAGE_SHORTS), () -> tabManager.openTab("https://m.youtube.com/shorts", Constant.PAGE_SHORTS)));
                    break;
                case "subscriptions":
                    if (signedIn && extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.NAV_BAR_SHOW_SUBSCRIPTIONS))
                        visibleItems.add(new NavItemInfo(R.drawable.ic_subscriptions, R.string.nav_subscriptions, pageClass.equals(Constant.PAGE_SUBSCRIPTIONS), () -> tabManager.openTab("https://m.youtube.com/feed/subscriptions", Constant.PAGE_SUBSCRIPTIONS)));
                    break;
                case "library":
                    if (extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.NAV_BAR_SHOW_LIBRARY))
                        visibleItems.add(new NavItemInfo(R.drawable.ic_library, R.string.nav_library, pageClass.equals(Constant.PAGE_LIBRARY), () -> tabManager.openTab("https://m.youtube.com/feed/library", Constant.PAGE_LIBRARY)));
                    break;
                case "downloads":
                    if (extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.NAV_BAR_SHOW_DOWNLOADS))
                        visibleItems.add(new NavItemInfo(R.drawable.ic_download, R.string.nav_downloads, false, () -> getContext().startActivity(new Intent(getContext(), DownloadActivity.class))));
                    break;
                case "settings":
                    if (extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.NAV_BAR_SHOW_SETTINGS))
                        visibleItems.add(new NavItemInfo(R.drawable.ic_settings, R.string.nav_settings, false, () -> getContext().startActivity(new Intent(getContext(), SettingsActivity.class))));
                    break;
            }
        }

        if (visibleItems.isEmpty()) {
            setVisibility(View.GONE);
            return;
        }

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int size = visibleItems.size();

        for (NavItemInfo info : visibleItems) {
            if (size <= 4) {
                addNavItem(info.iconRes, info.labelRes, info.isSelected, 0, 1.0f, info.action);
            } else {
                addNavItem(info.iconRes, info.labelRes, info.isSelected, (int) (screenWidth / 4.5), 0f, info.action);
            }
        }
    }

    private void addNavItem(int iconRes, int labelRes, boolean isSelected, int width, float weight, Runnable action) {
        LinearLayout item = new LinearLayout(getContext());
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT);
        params.weight = weight;
        item.setLayoutParams(params);

        TypedValue outValue = new TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        item.setBackgroundResource(outValue.resourceId);
        
        item.setClickable(true);
        item.setFocusable(true);

        TypedValue typedValue = new TypedValue();
        getContext().getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedValue, true);
        int colorOnSurface = typedValue.data;
        
        getContext().getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true);
        int colorOnSurfaceVariant = typedValue.data;

        getContext().getTheme().resolveAttribute(com.google.android.material.R.attr.colorSecondaryContainer, typedValue, true);
        int colorSecondaryContainer = typedValue.data;

        int iconColor = isSelected ? colorOnSurface : colorOnSurfaceVariant;
        int textColor = isSelected ? colorOnSurface : colorOnSurfaceVariant;

        FrameLayout iconContainer = new FrameLayout(getContext());
        int containerWidth = dpToPx(64);
        int containerHeight = dpToPx(28);
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(containerWidth, containerHeight);
        containerParams.topMargin = dpToPx(4);
        iconContainer.setLayoutParams(containerParams);

        if (isSelected) {
            View indicator = new View(getContext());
            FrameLayout.LayoutParams indicatorParams = new FrameLayout.LayoutParams(containerWidth, containerHeight);
            indicator.setLayoutParams(indicatorParams);
            
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.RECTANGLE);
            shape.setCornerRadius(dpToPx(14));
            shape.setColor(colorSecondaryContainer);
            indicator.setBackground(shape);
            iconContainer.addView(indicator);
        }

        ImageView icon = new ImageView(getContext());
        icon.setImageResource(iconRes);
        int iconSize = dpToPx(24);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(iconSize, iconSize);
        iconParams.gravity = Gravity.CENTER;
        icon.setLayoutParams(iconParams);
        icon.setImageTintList(ColorStateList.valueOf(iconColor));
        iconContainer.addView(icon);

        item.addView(iconContainer);

        if (!extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.HIDE_NAV_BAR_LABELS)) {
            TextView label = new TextView(getContext());
            label.setText(labelRes);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(textColor);
            label.setPadding(dpToPx(4), 0, dpToPx(4), dpToPx(6));
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            if (isSelected) {
                label.setTypeface(null, Typeface.BOLD);
            } else {
                label.setTypeface(null, Typeface.NORMAL);
            }
            item.addView(label);
        }

        item.setOnClickListener(v -> {
            action.run();
            postDelayed(this::update, 50);
        });
        container.addView(item);
    }

    private boolean isUserSignedIn() {
        String cookies = CookieManager.getInstance().getCookie("https://www.youtube.com");
        return cookies != null && cookies.contains("SID=");
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private static class NavItemInfo {
        int iconRes;
        int labelRes;
        boolean isSelected;
        Runnable action;

        NavItemInfo(int iconRes, int labelRes, boolean isSelected, Runnable action) {
            this.iconRes = iconRes;
            this.labelRes = labelRes;
            this.isSelected = isSelected;
            this.action = action;
        }
    }
}
