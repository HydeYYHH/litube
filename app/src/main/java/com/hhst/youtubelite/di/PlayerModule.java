package com.hhst.youtubelite.di;

import android.app.Activity;

import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.player.LitePlayerView;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.components.ActivityComponent;
import dagger.hilt.android.scopes.ActivityScoped;

@Module
@InstallIn(ActivityComponent.class)
@UnstableApi
public class PlayerModule {

	@Provides
	@ActivityScoped
	public static LitePlayerView provideLitePlayerView(Activity activity) {
		return activity.findViewById(R.id.playerView);
	}
}
