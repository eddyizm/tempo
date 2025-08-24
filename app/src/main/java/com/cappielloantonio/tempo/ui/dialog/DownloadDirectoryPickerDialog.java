package com.cappielloantonio.tempo.ui.dialog;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.cappielloantonio.tempo.R;

public class DownloadDirectoryPickerDialog extends DialogFragment {

    private ActivityResultLauncher<Intent> folderPickerLauncher;

    @NonNull
    @Override
    public android.app.Dialog onCreateDialog(Bundle savedInstanceState) {
        // Register launcher *before* button triggers
        folderPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null) {
                        Uri uri = data.getData();
                        if (uri != null) {
                            requireContext().getContentResolver().takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            );

                            requireContext().getSharedPreferences("tempo_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putString("download_directory_uri", uri.toString())
                                .apply();

                            Toast.makeText(requireContext(), "Download directory set:\n" + uri.toString(), Toast.LENGTH_LONG).show();
                        }
                    }
                }
            }
        );

        return new MaterialAlertDialogBuilder(requireContext())
            .setTitle("Set Download Directory")
            .setMessage("Choose a folder where downloaded songs will be stored.")
            .setPositiveButton("Choose Folder", (dialog, which) -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                              | Intent.FLAG_GRANT_READ_URI_PERMISSION
                              | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                folderPickerLauncher.launch(intent);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create();
    }
}
