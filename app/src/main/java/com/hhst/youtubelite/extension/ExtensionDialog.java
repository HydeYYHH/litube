package com.hhst.youtubelite.extension;

import android.content.Context;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hhst.youtubelite.R;

import java.util.List;
import java.util.Optional;

public class ExtensionDialog {
	private final Context context;
	private final ExtensionManager manager;


	public ExtensionDialog(Context context, ExtensionManager manager) {
		this.context = context;
		this.manager = manager;
	}

	/**
	 * Entry point to show the extension dialog tree.
	 */
	public void build() {
		showGroupDialog(Extension.defaultExtensionTree(), R.string.extension);
	}

	// Show a group dialog: if children are all leaves, show multi-choice; else, show as a list.
	private void showGroupDialog(List<Extension> extensions, int titleRes) {
		if (extensions.isEmpty()) return;

		// Check if all children are leaves (no further children)
		boolean allLeaves = true;
		for (Extension ext : extensions) {
			if (Optional.ofNullable(ext.children()).filter(list -> !list.isEmpty()).isPresent()) {
				allLeaves = false;
				break;
			}
		}

		if (allLeaves) showLeafMultiChoiceDialog(extensions, titleRes);
		else {
			// Show as a list, clicking into subgroups or leaves
			CharSequence[] items = new CharSequence[extensions.size()];
			for (int i = 0; i < extensions.size(); i++) {
				Extension ext = extensions.get(i);
				String text = context.getString(ext.description());
				items[i] = text;
			}

			MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context).setTitle(titleRes).setItems(items, null).setPositiveButton(R.string.close, (dialog, which) -> dialog.dismiss());

			AlertDialog dialog = builder.create();

			dialog.setOnShowListener(dlg -> {
				ListView listView = dialog.getListView();
				listView.setOnItemClickListener((parent, view, position, id) -> {
					Extension selected = extensions.get(position);
					if (Optional.ofNullable(selected.children()).filter(list -> !list.isEmpty()).isPresent())
						showGroupDialog(selected.children(), selected.description());
					else
						// Single leaf, show single toggle dialog
						showLeafMultiChoiceDialog(List.of(selected), selected.description());
				});
			});

			dialog.show();
		}
	}

	// Show a multi-choice dialog for a list of leaf extensions
	private void showLeafMultiChoiceDialog(List<Extension> leaves, int titleRes) {
		CharSequence[] items = new CharSequence[leaves.size()];
		boolean[] checked = new boolean[leaves.size()];
		for (int i = 0; i < leaves.size(); i++) {
			String text = context.getString(leaves.get(i).description());
			items[i] = text;
			checked[i] = manager.isEnabled(leaves.get(i).key());
		}

		AlertDialog dialog = new MaterialAlertDialogBuilder(context).setTitle(titleRes).setMultiChoiceItems(items, checked, (dlg, which, isChecked) -> {
			// Just update checked array, apply on confirm
			checked[which] = isChecked;
		}).setPositiveButton(R.string.confirm, null).setNegativeButton(R.string.cancel, (dlg, which) -> dlg.dismiss()).create();

		dialog.setOnShowListener(dlg -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			boolean changed = false;
			for (int i = 0; i < leaves.size(); i++) {
				boolean oldState = manager.isEnabled(leaves.get(i).key());
				if (oldState != checked[i]) {
					manager.setEnabled(leaves.get(i).key(), checked[i]);
					changed = true;
				}
			}
			// if (changed) RestartDialog.show(context); // fix: #119
			dialog.dismiss();
		}));

		dialog.show();
	}
}
