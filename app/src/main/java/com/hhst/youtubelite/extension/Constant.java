package com.hhst.youtubelite.extension;

import static com.hhst.youtubelite.Constant.ENABLE_BACKGROUND_PLAY;
import static com.hhst.youtubelite.Constant.ENABLE_IN_APP_MINI_PLAYER;
import static com.hhst.youtubelite.Constant.ENABLE_PIP;
import static com.hhst.youtubelite.Constant.REMEMBER_LAST_POSITION;
import static com.hhst.youtubelite.Constant.REMEMBER_RESIZE_MODE;
import static com.hhst.youtubelite.Constant.SKIP_POI_HIGHLIGHT;
import static com.hhst.youtubelite.Constant.SKIP_SELF_PROMO;
import static com.hhst.youtubelite.Constant.SKIP_SPONSORS;

import java.util.HashMap;
import java.util.Map;

public class Constant {
	public static final String ENABLE_DISPLAY_DISLIKES = "enable_display_dislikes";
	public static final String DEFAULT_QUALITY = "default_quality";
	public static final String DEFAULT_PLAYBACK_SPEED = "default_playback_speed";
	public static final String ENABLE_PLAYER_GESTURES = "enable_player_gestures";
	
	public static final String NAV_BAR_ORDER = "nav_bar_order";
	public static final String NAV_BAR_SHOW_HOME = "nav_bar_show_home";
	public static final String NAV_BAR_SHOW_SHORTS = "nav_bar_show_shorts";
	public static final String NAV_BAR_SHOW_SUBSCRIPTIONS = "nav_bar_show_subscriptions";
	public static final String NAV_BAR_SHOW_LIBRARY = "nav_bar_show_library";
	public static final String NAV_BAR_SHOW_DOWNLOADS = "nav_bar_show_downloads";
	public static final String NAV_BAR_SHOW_SETTINGS = "nav_bar_show_settings";
	public static final String SHOW_NAV_BAR_IN_SHORTS = "show_nav_bar_in_shorts";
	public static final String HIDE_NAV_BAR_LABELS = "hide_nav_bar_labels";

	public static final String DEFAULT_NAV_BAR_ORDER = "home,shorts,subscriptions,library,downloads,settings";
	public static final String ACTION_BAR_ORDER = "action_bar_order";
	public static final String ACTION_BAR_SHOW_SHARE = "action_bar_show_share";
	public static final String ACTION_BAR_SHOW_REMIX = "action_bar_show_remix";
	public static final String ACTION_BAR_SHOW_DOWNLOAD = "action_bar_show_download";
	public static final String ACTION_BAR_SHOW_THANKS = "action_bar_show_thanks";
	public static final String ACTION_BAR_SHOW_CLIP = "action_bar_show_clip";
	public static final String ACTION_BAR_SHOW_SAVE = "action_bar_show_save";
	public static final String ACTION_BAR_SHOW_REPORT = "action_bar_show_report";
	public static final String ACTION_BAR_SHOW_ASK_AI = "action_bar_show_ask_ai";

	public static final String DEFAULT_ACTION_BAR_ORDER = "like_dislike,download,pip,share,remix,thanks,clip,save,report,ask_ai";

	public static final String HIDE_SHORTS = "hide_shorts";
	public static final String HIDE_COMMENTS = "hide_comments";
	public static final String HIDE_RECOMMENDATIONS = "hide_recommendations";
	public static final String CUSTOMIZE_PLAYER_BUTTONS = "player_internal_buttons";
	public static final String PLAYER_SHOW_SPEED = "player_show_speed";
	public static final String PLAYER_SHOW_QUALITY = "player_show_quality";
	public static final String PLAYER_SHOW_SUBTITLES = "player_show_subtitles";
	public static final String PLAYER_SHOW_SEGMENTS = "player_show_segments";
	public static final String PLAYER_SHOW_LOOP = "player_show_loop";
	public static final String PLAYER_SHOW_RELOAD = "player_show_reload";
	public static final String PLAYER_SHOW_LOCK = "player_show_lock";
	public static final String PLAYER_SHOW_NEXT = "player_show_next";
	public static final String PLAYER_SHOW_PREVIOUS = "player_show_previous";
	public static final String DOWNLOAD_LOCATION = "download_location";

	public static final Map<String, Boolean> DEFAULT_PREFERENCES = new HashMap<>();
	static {
		DEFAULT_PREFERENCES.put(ENABLE_DISPLAY_DISLIKES, true);
		DEFAULT_PREFERENCES.put(SKIP_SPONSORS, true);
		DEFAULT_PREFERENCES.put(SKIP_SELF_PROMO, true);
		DEFAULT_PREFERENCES.put(SKIP_POI_HIGHLIGHT, true);
		DEFAULT_PREFERENCES.put(REMEMBER_LAST_POSITION, true);
		DEFAULT_PREFERENCES.put(ENABLE_BACKGROUND_PLAY, true);
		DEFAULT_PREFERENCES.put(ENABLE_PIP, true);
		DEFAULT_PREFERENCES.put(ENABLE_IN_APP_MINI_PLAYER, true);
		DEFAULT_PREFERENCES.put(REMEMBER_RESIZE_MODE, false);
		DEFAULT_PREFERENCES.put(ENABLE_PLAYER_GESTURES, true);
		DEFAULT_PREFERENCES.put(NAV_BAR_SHOW_HOME, true);
		DEFAULT_PREFERENCES.put(NAV_BAR_SHOW_SHORTS, true);
		DEFAULT_PREFERENCES.put(NAV_BAR_SHOW_SUBSCRIPTIONS, true);
		DEFAULT_PREFERENCES.put(NAV_BAR_SHOW_LIBRARY, true);
		DEFAULT_PREFERENCES.put(NAV_BAR_SHOW_DOWNLOADS, true);
		DEFAULT_PREFERENCES.put(NAV_BAR_SHOW_SETTINGS, true);
		DEFAULT_PREFERENCES.put(SHOW_NAV_BAR_IN_SHORTS, true);
		DEFAULT_PREFERENCES.put(HIDE_NAV_BAR_LABELS, false);
		DEFAULT_PREFERENCES.put(ACTION_BAR_SHOW_SHARE, true);
		DEFAULT_PREFERENCES.put(ACTION_BAR_SHOW_REMIX, true);
		DEFAULT_PREFERENCES.put(ACTION_BAR_SHOW_DOWNLOAD, true);
		DEFAULT_PREFERENCES.put(ACTION_BAR_SHOW_THANKS, true);
		DEFAULT_PREFERENCES.put(ACTION_BAR_SHOW_CLIP, true);
		DEFAULT_PREFERENCES.put(ACTION_BAR_SHOW_SAVE, true);
		DEFAULT_PREFERENCES.put(ACTION_BAR_SHOW_REPORT, true);
		DEFAULT_PREFERENCES.put(ACTION_BAR_SHOW_ASK_AI, true);

		DEFAULT_PREFERENCES.put(HIDE_SHORTS, false);
		DEFAULT_PREFERENCES.put(HIDE_COMMENTS, false);
		DEFAULT_PREFERENCES.put(HIDE_RECOMMENDATIONS, false);
		DEFAULT_PREFERENCES.put(PLAYER_SHOW_SPEED, true);
		DEFAULT_PREFERENCES.put(PLAYER_SHOW_QUALITY, true);
		DEFAULT_PREFERENCES.put(PLAYER_SHOW_SUBTITLES, true);
		DEFAULT_PREFERENCES.put(PLAYER_SHOW_SEGMENTS, true);
		DEFAULT_PREFERENCES.put(PLAYER_SHOW_LOOP, true);
		DEFAULT_PREFERENCES.put(PLAYER_SHOW_RELOAD, true);
		DEFAULT_PREFERENCES.put(PLAYER_SHOW_LOCK, true);
		DEFAULT_PREFERENCES.put(PLAYER_SHOW_NEXT, true);
		DEFAULT_PREFERENCES.put(PLAYER_SHOW_PREVIOUS, true);
	}
}
