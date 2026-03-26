package com.hhst.youtubelite.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extension.Extension;
import com.hhst.youtubelite.extension.ExtensionManager;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class SubSettingsActivity extends AppCompatActivity {

    public static final String EXTRA_CATEGORY_INDEX = "extra_category_index";
    public static final String EXTRA_TITLE_RES = "extra_title_res";

    @Inject ExtensionManager extensionManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_sub_settings);

        View mainView = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        int categoryIndex = getIntent().getIntExtra(EXTRA_CATEGORY_INDEX, 0);
        int titleRes = getIntent().getIntExtra(EXTRA_TITLE_RES, R.string.litepipe_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(titleRes);
        toolbar.setNavigationOnClickListener(v -> finish());

        List<Extension> extensions = Extension.defaultExtensionTree().get(categoryIndex).children();

        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(new SettingsAdapter(extensions));
    }

    private class SettingsAdapter extends RecyclerView.Adapter<SettingsAdapter.ViewHolder> {
        private final List<Extension> items;

        SettingsAdapter(List<Extension> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_setting_toggle, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Extension ext = items.get(position);
            holder.title.setText(ext.description());
            holder.checkbox.setChecked(extensionManager.isEnabled(ext.key()));

            holder.itemView.setOnClickListener(v -> {
                boolean newState = !extensionManager.isEnabled(ext.key());
                extensionManager.setEnabled(ext.key(), newState);
                holder.checkbox.setChecked(newState);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView title;
            MaterialSwitch checkbox;

            ViewHolder(View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.title);
                checkbox = itemView.findViewById(R.id.checkbox);
            }
        }
    }
}