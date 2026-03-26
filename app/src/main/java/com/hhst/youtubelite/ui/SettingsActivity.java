package com.hhst.youtubelite.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extension.ExtensionManager;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class SettingsActivity extends AppCompatActivity {

    @Inject ExtensionManager extensionManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        View mainView = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        findViewById(R.id.category_general).setOnClickListener(v -> 
            openSubSettings(0, R.string.general));

        findViewById(R.id.category_player).setOnClickListener(v -> 
            openSubSettings(1, R.string.player));

        findViewById(R.id.category_sponsorblock).setOnClickListener(v -> 
            openSubSettings(2, R.string.sponsorblock));

        findViewById(R.id.category_download).setOnClickListener(v -> 
            openSubSettings(3, R.string.download));

        findViewById(R.id.category_miscellaneous).setOnClickListener(v -> 
            openSubSettings(4, R.string.miscellaneous));

        findViewById(R.id.reset_layout).setOnClickListener(v -> showResetConfirmation());
    }

    private void showResetConfirmation() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reset_extension_title)
                .setMessage(R.string.reset_extension_message)
                .setPositiveButton(R.string.confirm, (d, w) -> extensionManager.resetToDefault())
                .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                .show();
    }

    private void openSubSettings(int index, int titleRes) {
        Intent intent = new Intent(this, SubSettingsActivity.class);
        intent.putExtra(SubSettingsActivity.EXTRA_CATEGORY_INDEX, index);
        intent.putExtra(SubSettingsActivity.EXTRA_TITLE_RES, titleRes);
        startActivity(intent);
    }
}