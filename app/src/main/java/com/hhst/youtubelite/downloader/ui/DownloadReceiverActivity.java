package com.hhst.youtubelite.downloader.ui;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;
import com.hhst.youtubelite.ui.MainActivity;

@UnstableApi
public class DownloadReceiverActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (intent != null) {
            Intent forwardIntent = new Intent(this, MainActivity.class);
            forwardIntent.setAction("TRIGGER_DOWNLOAD_FROM_SHARE");
            forwardIntent.putExtras(intent);
            forwardIntent.setData(intent.getData());
            forwardIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(forwardIntent);
        }
        finish();
    }
}