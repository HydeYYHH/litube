package com.hhst.youtubelite.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extension.Constant;
import com.hhst.youtubelite.extension.Extension;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.util.DownloadStorageUtils;
import com.hhst.youtubelite.util.ViewUtils;
import com.tencent.mmkv.MMKV;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class SubSettingsActivity extends AppCompatActivity {

    public static final String EXTRA_CATEGORY_INDEX = "extra_category_index";
    public static final String EXTRA_TITLE_RES = "extra_title_res";
    public static final String EXTRA_NAV_BAR_MODE = "extra_nav_bar_mode";
    public static final String EXTRA_ACTION_BAR_MODE = "extra_action_bar_mode";
    public static final String EXTRA_EXTENSION_KEY = "extra_extension_key";

    @Inject ExtensionManager extensionManager;
    private SettingsAdapter adapter;

    private final ActivityResultLauncher<Uri> directoryPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri != null) {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    MMKV.defaultMMKV().encode(Constant.DOWNLOAD_LOCATION, uri.toString());
                    if (adapter != null) adapter.notifyDataSetChanged();
                }
            }
    );

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

        int categoryIndex = getIntent().getIntExtra(EXTRA_CATEGORY_INDEX, -1);
        int titleRes = getIntent().getIntExtra(EXTRA_TITLE_RES, R.string.litepipe_settings);
        boolean navBarMode = getIntent().getBooleanExtra(EXTRA_NAV_BAR_MODE, false);
        boolean actionBarMode = getIntent().getBooleanExtra(EXTRA_ACTION_BAR_MODE, false);
        String extensionKey = getIntent().getStringExtra(EXTRA_EXTENSION_KEY);

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(titleRes);
        toolbar.setNavigationOnClickListener(v -> finish());

        List<Extension> extensions = null;
        if (extensionKey != null) {
            extensions = findExtensionByKey(Extension.defaultExtensionTree(), extensionKey);
        } else if (navBarMode) {
            extensions = findExtensionByKey(Extension.defaultExtensionTree(), Constant.NAV_BAR_ORDER);
        } else if (actionBarMode) {
            extensions = findExtensionByKey(Extension.defaultExtensionTree(), Constant.ACTION_BAR_ORDER);
        } else if (categoryIndex != -1) {
            extensions = Extension.defaultExtensionTree().get(categoryIndex).children();
        }

        if (extensions == null) extensions = new ArrayList<>();

        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        

        boolean useDraggable = navBarMode;
        adapter = new SettingsAdapter(extensions, useDraggable, navBarMode);
        recyclerView.setAdapter(adapter);

        if (useDraggable) {
            ItemTouchHelper touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
                @Override
                public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                    return adapter.onItemMove(viewHolder.getBindingAdapterPosition(), target.getBindingAdapterPosition());
                }

                @Override
                public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {}

                @Override
                public boolean isLongPressDragEnabled() {
                    return false;
                }
            });
            touchHelper.attachToRecyclerView(recyclerView);
            adapter.setTouchHelper(touchHelper);
        }
    }

    private List<Extension> findExtensionByKey(List<Extension> tree, String key) {
        for (Extension ext : tree) {
            if (key.equals(ext.key())) return ext.children();
            if (ext.children() != null) {
                List<Extension> result = findExtensionByKey(ext.children(), key);
                if (result != null) return result;
            }
        }
        return null;
    }

    private class SettingsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_TOGGLE = 0;
        private static final int TYPE_DRAGGABLE = 1;

        private final List<Extension> items;
        private final boolean draggableMode;
        private final boolean isNavBar;
        private ItemTouchHelper touchHelper;

        SettingsAdapter(List<Extension> items, boolean draggableMode, boolean isNavBar) {
            this.items = new ArrayList<>(items);
            this.draggableMode = draggableMode;
            this.isNavBar = isNavBar;
            if (draggableMode) sortItems();
        }

        private void sortItems() {
            String key = isNavBar ? Constant.NAV_BAR_ORDER : Constant.ACTION_BAR_ORDER;
            String def = isNavBar ? Constant.DEFAULT_NAV_BAR_ORDER : Constant.DEFAULT_ACTION_BAR_ORDER;
            String orderStr = MMKV.defaultMMKV().decodeString(key, def);
            if (orderStr == null) orderStr = def;
            List<String> order = Arrays.asList(orderStr.split(","));

            items.sort((a, b) -> {
                String keyA = a.key() != null ? a.key().replace("nav_bar_show_", "").replace("action_bar_show_", "") : "";
                if (keyA.equals(com.hhst.youtubelite.Constant.ENABLE_PIP)) keyA = "pip";

                String keyB = b.key() != null ? b.key().replace("nav_bar_show_", "").replace("action_bar_show_", "") : "";
                if (keyB.equals(com.hhst.youtubelite.Constant.ENABLE_PIP)) keyB = "pip";

                int idxA = order.indexOf(keyA);
                int idxB = order.indexOf(keyB);
                return Integer.compare(idxA == -1 ? 99 : idxA, idxB == -1 ? 99 : idxB);
            });
        }

        void setTouchHelper(ItemTouchHelper touchHelper) {
            this.touchHelper = touchHelper;
        }

        @Override
        public int getItemViewType(int position) {
            return draggableMode ? TYPE_DRAGGABLE : TYPE_TOGGLE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_DRAGGABLE) {
                View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_setting_draggable, parent, false);
                return new DraggableViewHolder(view);
            } else {
                View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_setting_toggle, parent, false);
                return new ToggleViewHolder(view);
            }
        }

        @SuppressLint({"ClickableViewAccessibility", "NotifyDataSetChanged"})
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Extension ext = items.get(position);
            if (holder instanceof DraggableViewHolder dragHolder) {
                dragHolder.title.setText(ext.description());
                dragHolder.checkbox.setChecked(extensionManager.isEnabled(ext.key()));
                dragHolder.itemView.setOnClickListener(v -> toggle(ext.key(), dragHolder.checkbox));
                dragHolder.dragHandle.setOnTouchListener((v, event) -> {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        touchHelper.startDrag(dragHolder);
                    }
                    return false;
                });
            } else if (holder instanceof ToggleViewHolder toggleHolder) {
                toggleHolder.title.setText(ext.description());
                boolean isDownloadLocation = Constant.DOWNLOAD_LOCATION.equals(ext.key());
                boolean isDefaultQuality = Constant.DEFAULT_QUALITY.equals(ext.key());
                boolean isDefaultSpeed = Constant.DEFAULT_PLAYBACK_SPEED.equals(ext.key());

                if (isDownloadLocation) {
                    toggleHolder.description.setText(DownloadStorageUtils.getDownloadsLocationLabel(SubSettingsActivity.this));
                    toggleHolder.description.setVisibility(View.VISIBLE);
                    toggleHolder.checkbox.setVisibility(View.GONE);
                    toggleHolder.actionButton.setVisibility(View.VISIBLE);
                    toggleHolder.actionButton.setOnClickListener(v -> directoryPickerLauncher.launch(null));
                } else if (isDefaultQuality) {
                    String quality = MMKV.defaultMMKV().decodeString("preferences:" + Constant.DEFAULT_QUALITY, "Auto");
                    toggleHolder.description.setText(quality);
                    toggleHolder.description.setVisibility(View.VISIBLE);
                    toggleHolder.checkbox.setVisibility(View.GONE);
                    toggleHolder.actionButton.setVisibility(View.VISIBLE);
                    toggleHolder.actionButton.setOnClickListener(v -> showQualitySelector());
                } else if (isDefaultSpeed) {
                    String speed = MMKV.defaultMMKV().decodeString("preferences:" + Constant.DEFAULT_PLAYBACK_SPEED, "1.0x");
                    toggleHolder.description.setText(speed);
                    toggleHolder.description.setVisibility(View.VISIBLE);
                    toggleHolder.checkbox.setVisibility(View.GONE);
                    toggleHolder.actionButton.setVisibility(View.VISIBLE);
                    toggleHolder.actionButton.setOnClickListener(v -> showSpeedSelector());
                } else {
                    toggleHolder.actionButton.setVisibility(View.GONE);
                    if (ext.helpText() != 0) {
                        toggleHolder.description.setText(ext.helpText());
                        toggleHolder.description.setVisibility(View.VISIBLE);
                    } else {
                        toggleHolder.description.setVisibility(View.GONE);
                    }

                    boolean hasChildren = ext.children() != null && !ext.children().isEmpty();
                    boolean isNavBarOrder = ext.key() != null && ext.key().equals(Constant.NAV_BAR_ORDER);
                    boolean isActionBarOrder = ext.key() != null && ext.key().equals(Constant.ACTION_BAR_ORDER);

                    if (hasChildren || isNavBarOrder || isActionBarOrder) {
                        toggleHolder.checkbox.setVisibility(View.GONE);
                    } else {
                        toggleHolder.checkbox.setVisibility(ext.key() != null ? View.VISIBLE : View.GONE);
                        if (ext.key() != null) toggleHolder.checkbox.setChecked(extensionManager.isEnabled(ext.key()));
                    }
                }

                toggleHolder.itemView.setOnClickListener(v -> {
                    if (isDownloadLocation) {
                        directoryPickerLauncher.launch(null);
                    } else if (isDefaultQuality) {
                        showQualitySelector();
                    } else if (isDefaultSpeed) {
                        showSpeedSelector();
                    } else if (ext.key() != null && (ext.key().equals(Constant.NAV_BAR_ORDER) || ext.key().equals(Constant.ACTION_BAR_ORDER))) {
                        Intent intent = new Intent(SubSettingsActivity.this, SubSettingsActivity.class);
                        if (ext.key().equals(Constant.NAV_BAR_ORDER)) intent.putExtra(EXTRA_NAV_BAR_MODE, true);
                        else intent.putExtra(EXTRA_ACTION_BAR_MODE, true);
                        intent.putExtra(EXTRA_EXTENSION_KEY, ext.key());
                        intent.putExtra(EXTRA_TITLE_RES, ext.description());
                        startActivity(intent);
                    } else if (ext.children() != null && !ext.children().isEmpty()) {
                        Intent intent = new Intent(SubSettingsActivity.this, SubSettingsActivity.class);
                        intent.putExtra(EXTRA_EXTENSION_KEY, ext.key());
                        intent.putExtra(EXTRA_TITLE_RES, ext.description());
                        startActivity(intent);
                    } else if (ext.key() != null) {
                        toggle(ext.key(), toggleHolder.checkbox);
                    }
                });
            }
        }

        @SuppressLint("NotifyDataSetChanged")
        private void showQualitySelector() {
            String[] options = {"Auto", "144p", "240p", "360p", "480p", "720p", "1080p", "1440p", "2160p"};
            new MaterialAlertDialogBuilder(SubSettingsActivity.this)
                    .setTitle(R.string.default_quality)
                    .setItems(options, (d, w) -> {
                        MMKV.defaultMMKV().encode("preferences:" + Constant.DEFAULT_QUALITY, options[w]);
                        notifyDataSetChanged();
                    })
                    .show();
        }

        @SuppressLint("NotifyDataSetChanged")
        private void showSpeedSelector() {
            String currentSpeed = MMKV.defaultMMKV().decodeString("preferences:" + Constant.DEFAULT_PLAYBACK_SPEED, "1.0x");
            if (currentSpeed == null) currentSpeed = "1.0x";
            
            final EditText input = new EditText(SubSettingsActivity.this);
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setText(currentSpeed.replace("x", ""));
            input.setSelection(input.getText().length());

            FrameLayout container = new FrameLayout(SubSettingsActivity.this);
            int padding = ViewUtils.dpToPx(SubSettingsActivity.this, 24);
            container.setPadding(padding, padding / 2, padding, 0);
            container.addView(input);

            new MaterialAlertDialogBuilder(SubSettingsActivity.this)
                    .setTitle(R.string.default_playback_speed)
                    .setView(container)
                    .setPositiveButton(R.string.save, (dialog, which) -> {
                        String value = input.getText().toString();
                        if (!value.isEmpty()) {
                            try {
                                float speed = Float.parseFloat(value);
                                if (speed > 4.0f) speed = 4.0f;
                                if (speed < 0.25f) speed = 0.25f;
                                MMKV.defaultMMKV().encode("preferences:" + Constant.DEFAULT_PLAYBACK_SPEED, String.format(Locale.US, "%.2fx", speed));
                                notifyDataSetChanged();
                            } catch (NumberFormatException ignored) {}
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }

        private void toggle(String key, MaterialSwitch checkbox) {
            boolean newState = !extensionManager.isEnabled(key);
            extensionManager.setEnabled(key, newState);
            checkbox.setChecked(newState);
        }

        boolean onItemMove(int fromPosition, int toPosition) {
            if (fromPosition < 0 || toPosition < 0) return false;
            java.util.Collections.swap(items, fromPosition, toPosition);
            notifyItemMoved(fromPosition, toPosition);
            saveOrder();
            return true;
        }

        private void saveOrder() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                String k = items.get(i).key().replace("nav_bar_show_", "").replace("action_bar_show_", "");
                if (k.equals(com.hhst.youtubelite.Constant.ENABLE_PIP)) k = "pip";
                sb.append(k);
                if (i < items.size() - 1) sb.append(",");
            }
            String prefKey = isNavBar ? Constant.NAV_BAR_ORDER : Constant.ACTION_BAR_ORDER;
            MMKV.defaultMMKV().encode(prefKey, sb.toString());
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ToggleViewHolder extends RecyclerView.ViewHolder {
            TextView title;
            TextView description;
            MaterialSwitch checkbox;
            MaterialButton actionButton;
            ToggleViewHolder(View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.title);
                description = itemView.findViewById(R.id.setting_description);
                checkbox = itemView.findViewById(R.id.checkbox);
                actionButton = itemView.findViewById(R.id.action_button);
            }
        }

        class DraggableViewHolder extends RecyclerView.ViewHolder {
            TextView title;
            MaterialSwitch checkbox;
            ImageView dragHandle;
            DraggableViewHolder(View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.title);
                checkbox = itemView.findViewById(R.id.checkbox);
                dragHandle = itemView.findViewById(R.id.drag_handle);
            }
        }
    }
}
