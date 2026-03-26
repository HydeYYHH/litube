package com.hhst.youtubelite.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;
import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.R;

@SuppressLint("CustomSplashScreen")
@UnstableApi
public class SplashActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_splash);

        prepareTagline();

        handler.postDelayed(() -> animateWordWithDot(
                findViewById(R.id.tagline_fast),
                findViewById(R.id.tagline_dot1)
        ), 300);

        handler.postDelayed(() -> animateWordWithDot(
                findViewById(R.id.tagline_minimal),
                findViewById(R.id.tagline_dot2)
        ), 700);

        handler.postDelayed(() -> animateView(findViewById(R.id.tagline_simple)), 1100);

        handler.postDelayed(() -> {
            if (!isFinishing()) {
                startActivity(new Intent(SplashActivity.this, MainActivity.class));
                finish();
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }
        }, 2000);
    }

    private void prepareTagline() {
        int[] ids = {
                R.id.tagline_fast,
                R.id.tagline_dot1,
                R.id.tagline_minimal,
                R.id.tagline_dot2,
                R.id.tagline_simple
        };

        for (int id : ids) {
            View v = findViewById(id);
            if (v != null) {
                v.setAlpha(0f);
                v.setTranslationY(50f);
            }
        }
    }

    private void animateWordWithDot(View word, View dot) {
        animateView(word);

        handler.postDelayed(() -> animateView(dot), 150);
    }

    private void animateView(View v) {
        if (v != null) {
            v.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(400)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .start();
        }
    }
}
