package com.hhst.youtubelite.extension;

import com.hhst.youtubelite.R;
import java.util.List;

import static com.hhst.youtubelite.Constant.*;

public record Extension(String key, int description, int helpText, List<Extension> children) {

    public Extension(String key, int description, List<Extension> children) {
        this(key, description, 0, children);
    }

    public static List<Extension> defaultExtensionTree() {
        return List.of(
            new Extension(null, R.string.general, List.of(
                new Extension(Constant.NAV_BAR_ORDER, R.string.custom_navigation, List.of(
                    new Extension(Constant.NAV_BAR_SHOW_HOME, R.string.nav_home, null),
                    new Extension(Constant.NAV_BAR_SHOW_SHORTS, R.string.nav_shorts, null),
                    new Extension(Constant.NAV_BAR_SHOW_SUBSCRIPTIONS, R.string.nav_subscriptions, null),
                    new Extension(Constant.NAV_BAR_SHOW_LIBRARY, R.string.nav_library, null),
                    new Extension(Constant.NAV_BAR_SHOW_DOWNLOADS, R.string.nav_downloads, null),
                    new Extension(Constant.NAV_BAR_SHOW_SETTINGS, R.string.nav_settings, null)
                )),
                new Extension(Constant.SHOW_NAV_BAR_IN_SHORTS, R.string.show_nav_bar_in_shorts, null),
                new Extension(Constant.HIDE_NAV_BAR_LABELS, R.string.hide_nav_bar_labels,  null),
                new Extension(Constant.HIDE_SHORTS, R.string.hide_shorts, R.string.hide_shorts_desc, null),
                new Extension(Constant.ENABLE_DISPLAY_DISLIKES, R.string.return_dislike, null)
            )),
            new Extension(null, R.string.player, List.of(
                new Extension(Constant.ACTION_BAR_ORDER, R.string.custom_action_bar, R.string.custom_action_bar_desc, List.of(
                    new Extension(Constant.ACTION_BAR_SHOW_DOWNLOAD, R.string.action_download, null),
                    new Extension(ENABLE_PIP, R.string.pip, null),
                    new Extension(Constant.ACTION_BAR_SHOW_SHARE, R.string.action_share, null),
                    new Extension(Constant.ACTION_BAR_SHOW_REMIX, R.string.action_remix, null),
                    new Extension(Constant.ACTION_BAR_SHOW_THANKS, R.string.action_thanks, null),
                    new Extension(Constant.ACTION_BAR_SHOW_CLIP, R.string.action_clip, null),
                    new Extension(Constant.ACTION_BAR_SHOW_SAVE, R.string.action_save, null),
                    new Extension(Constant.ACTION_BAR_SHOW_REPORT, R.string.action_report, null),
                    new Extension(Constant.ACTION_BAR_SHOW_ASK_AI, R.string.action_ask_ai, null)
                )),
                new Extension(Constant.CUSTOMIZE_PLAYER_BUTTONS, R.string.player_buttons, R.string.player_buttons_desc, List.of(
                    new Extension(Constant.PLAYER_SHOW_SPEED, R.string.speed, null),
                    new Extension(Constant.PLAYER_SHOW_QUALITY, R.string.quality, null),
                    new Extension(Constant.PLAYER_SHOW_SUBTITLES, R.string.subtitles, null),
                    new Extension(Constant.PLAYER_SHOW_SEGMENTS, R.string.segments, null),
                    new Extension(Constant.PLAYER_SHOW_LOOP, R.string.loop, null),
                    new Extension(Constant.PLAYER_SHOW_RELOAD, R.string.restart, null),
                    new Extension(Constant.PLAYER_SHOW_LOCK, R.string.lock_screen, null),
                    new Extension(Constant.PLAYER_SHOW_NEXT, R.string.action_next, null),
                    new Extension(Constant.PLAYER_SHOW_PREVIOUS, R.string.action_previous, null)
                )),
                new Extension(REMEMBER_LAST_POSITION, R.string.remember_last_position, null),
                new Extension(Constant.DEFAULT_QUALITY, R.string.default_quality, null),
                new Extension(Constant.DEFAULT_PLAYBACK_SPEED, R.string.default_playback_speed, null),
                new Extension(REMEMBER_RESIZE_MODE, R.string.remember_resize_mode, null),
                new Extension(Constant.ENABLE_PLAYER_GESTURES, R.string.player_gestures, R.string.player_gestures_desc, null),
                new Extension(ENABLE_IN_APP_MINI_PLAYER, R.string.in_app_mini_player, R.string.in_app_mini_player_desc, null),
                new Extension(ENABLE_BACKGROUND_PLAY, R.string.background_play, R.string.background_play_desc, null)
            )),
            new Extension(null, R.string.skip_sponsors, List.of(
                new Extension(SKIP_SPONSORS, R.string.skip_sponsors, null),
                new Extension(SKIP_SELF_PROMO, R.string.skip_sponsors_selfpromo, null),
                new Extension(SKIP_POI_HIGHLIGHT, R.string.skip_sponsors_highlight, null)
            )),
            new Extension(null, R.string.download, List.of(
                new Extension(Constant.DOWNLOAD_LOCATION, R.string.download_location, null)
            )),
            new Extension(null, R.string.miscellaneous, List.of(
                new Extension(Constant.HIDE_COMMENTS, R.string.hide_comments, null),
                new Extension(Constant.HIDE_RECOMMENDATIONS, R.string.hide_recommendations, null)
            ))
        );
    }
}
