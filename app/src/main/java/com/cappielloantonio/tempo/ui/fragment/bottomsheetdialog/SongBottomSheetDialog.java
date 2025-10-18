package com.cappielloantonio.tempo.ui.fragment.bottomsheetdialog;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.navigation.fragment.NavHostFragment;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.dialog.PlaylistChooserDialog;
import com.cappielloantonio.tempo.ui.dialog.RatingDialog;
import com.cappielloantonio.tempo.util.AssetLinkUtil;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.ExternalAudioReader;
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.viewmodel.HomeViewModel;
import com.cappielloantonio.tempo.viewmodel.SongBottomSheetViewModel;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.common.util.concurrent.ListenableFuture;

import android.content.Intent;
import androidx.media3.common.MediaItem;
import com.cappielloantonio.tempo.util.ExternalAudioWriter;

import java.util.ArrayList;
import java.util.Collections;

@UnstableApi
public class SongBottomSheetDialog extends BottomSheetDialogFragment implements View.OnClickListener {
    private HomeViewModel homeViewModel;
    private SongBottomSheetViewModel songBottomSheetViewModel;
    private Child song;

    private TextView downloadButton;
    private TextView removeButton;
    private ChipGroup assetLinkChipGroup;
    private Chip songLinkChip;
    private Chip albumLinkChip;
    private Chip artistLinkChip;
    private AssetLinkUtil.AssetLink currentSongLink;
    private AssetLinkUtil.AssetLink currentAlbumLink;
    private AssetLinkUtil.AssetLink currentArtistLink;

    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_song_dialog, container, false);

        song = requireArguments().getParcelable(Constants.TRACK_OBJECT);

        homeViewModel = new ViewModelProvider(requireActivity()).get(HomeViewModel.class);
        songBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(SongBottomSheetViewModel.class);
        songBottomSheetViewModel.setSong(song);

        init(view);

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        MappingUtil.observeExternalAudioRefresh(getViewLifecycleOwner(), this::updateDownloadButtons);
    }

    @Override
    public void onStart() {
        super.onStart();

        initializeMediaBrowser();
    }

    @Override
    public void onStop() {
        releaseMediaBrowser();
        super.onStop();
    }

    private void init(View view) {
        ImageView coverSong = view.findViewById(R.id.song_cover_image_view);
        CustomGlideRequest.Builder
                .from(requireContext(), songBottomSheetViewModel.getSong().getCoverArtId(), CustomGlideRequest.ResourceType.Song)
                .build()
                .into(coverSong);

        TextView titleSong = view.findViewById(R.id.song_title_text_view);
        titleSong.setText(songBottomSheetViewModel.getSong().getTitle());

        titleSong.setSelected(true);

        TextView artistSong = view.findViewById(R.id.song_artist_text_view);
        artistSong.setText(songBottomSheetViewModel.getSong().getArtist());

        initAssetLinkChips(view);
        bindAssetLinkView(coverSong, currentSongLink);
        bindAssetLinkView(titleSong, currentSongLink);
        bindAssetLinkView(artistSong, currentArtistLink != null ? currentArtistLink : currentSongLink);

        ToggleButton favoriteToggle = view.findViewById(R.id.button_favorite);
        favoriteToggle.setChecked(songBottomSheetViewModel.getSong().getStarred() != null);
        favoriteToggle.setOnClickListener(v -> {
            songBottomSheetViewModel.setFavorite(requireContext());
        });
        favoriteToggle.setOnLongClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.TRACK_OBJECT, song);

            RatingDialog dialog = new RatingDialog();
            dialog.setArguments(bundle);
            dialog.show(requireActivity().getSupportFragmentManager(), null);

            dismissBottomSheet();
            return true;
        });

        TextView playRadio = view.findViewById(R.id.play_radio_text_view);
        playRadio.setOnClickListener(v -> {
            MediaManager.startQueue(mediaBrowserListenableFuture, song);
            ((MainActivity) requireActivity()).setBottomSheetInPeek(true);

            songBottomSheetViewModel.getInstantMix(getViewLifecycleOwner(), song).observe(getViewLifecycleOwner(), songs -> {
                MusicUtil.ratingFilter(songs);

                if (songs == null) {
                    dismissBottomSheet();
                    return;
                }

                if (!songs.isEmpty()) {
                    MediaManager.enqueue(mediaBrowserListenableFuture, songs, true);
                    dismissBottomSheet();
                }
            });
        });

        TextView playNext = view.findViewById(R.id.play_next_text_view);
        playNext.setOnClickListener(v -> {
            MediaManager.enqueue(mediaBrowserListenableFuture, song, true);
            ((MainActivity) requireActivity()).setBottomSheetInPeek(true);
            dismissBottomSheet();
        });

        TextView addToQueue = view.findViewById(R.id.add_to_queue_text_view);
        addToQueue.setOnClickListener(v -> {
            MediaManager.enqueue(mediaBrowserListenableFuture, song, false);
            ((MainActivity) requireActivity()).setBottomSheetInPeek(true);
            dismissBottomSheet();
        });

        TextView rate = view.findViewById(R.id.rate_text_view);
        rate.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.TRACK_OBJECT, song);

            RatingDialog dialog = new RatingDialog();
            dialog.setArguments(bundle);
            dialog.show(requireActivity().getSupportFragmentManager(), null);

            dismissBottomSheet();
        });

        downloadButton = view.findViewById(R.id.download_text_view);
        downloadButton.setOnClickListener(v -> {
            if (Preferences.getDownloadDirectoryUri() == null) {
                DownloadUtil.getDownloadTracker(requireContext()).download(
                        MappingUtil.mapDownload(song),
                        new Download(song)
                );
            } else {
                ExternalAudioWriter.downloadToUserDirectory(requireContext(), song);
            }
            dismissBottomSheet();
        });

        removeButton = view.findViewById(R.id.remove_text_view);
        removeButton.setOnClickListener(v -> {
            if (Preferences.getDownloadDirectoryUri() == null) {
                DownloadUtil.getDownloadTracker(requireContext()).remove(
                        MappingUtil.mapDownload(song),
                        new Download(song)
                );
            } else {
                ExternalAudioReader.delete(song);
            }
            dismissBottomSheet();
        });

        updateDownloadButtons();

        TextView addToPlaylist = view.findViewById(R.id.add_to_playlist_text_view);
        addToPlaylist.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(Constants.TRACKS_OBJECT, new ArrayList<>(Collections.singletonList(song)));

            PlaylistChooserDialog dialog = new PlaylistChooserDialog();
            dialog.setArguments(bundle);
            dialog.show(requireActivity().getSupportFragmentManager(), null);

            dismissBottomSheet();
        });

        TextView viewPlaylists = view.findViewById(R.id.view_playlists_text_view);
        viewPlaylists.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.TRACK_OBJECT, song);
            NavHostFragment.findNavController(this).navigate(R.id.songPlaylistsFragment, bundle);
            dismissBottomSheet();
        });

        TextView goToAlbum = view.findViewById(R.id.go_to_album_text_view);
        goToAlbum.setOnClickListener(v -> songBottomSheetViewModel.getAlbum().observe(getViewLifecycleOwner(), album -> {
            if (album != null) {
                Bundle bundle = new Bundle();
                bundle.putParcelable(Constants.ALBUM_OBJECT, album);
                NavHostFragment.findNavController(this).navigate(R.id.albumPageFragment, bundle);
            } else
                Toast.makeText(requireContext(), getString(R.string.song_bottom_sheet_error_retrieving_album), Toast.LENGTH_SHORT).show();

            dismissBottomSheet();
        }));

        goToAlbum.setVisibility(songBottomSheetViewModel.getSong().getAlbumId() != null ? View.VISIBLE : View.GONE);

        TextView goToArtist = view.findViewById(R.id.go_to_artist_text_view);
        goToArtist.setOnClickListener(v -> songBottomSheetViewModel.getArtist().observe(getViewLifecycleOwner(), artist -> {
            if (artist != null) {
                Bundle bundle = new Bundle();
                bundle.putParcelable(Constants.ARTIST_OBJECT, artist);
                NavHostFragment.findNavController(this).navigate(R.id.artistPageFragment, bundle);
            } else
                Toast.makeText(requireContext(), getString(R.string.song_bottom_sheet_error_retrieving_artist), Toast.LENGTH_SHORT).show();

            dismissBottomSheet();
        }));

        goToArtist.setVisibility(songBottomSheetViewModel.getSong().getArtistId() != null ? View.VISIBLE : View.GONE);

        TextView share = view.findViewById(R.id.share_text_view);
        share.setOnClickListener(v -> songBottomSheetViewModel.shareTrack().observe(getViewLifecycleOwner(), sharedTrack -> {
            if (sharedTrack != null) {
                ClipboardManager clipboardManager = (ClipboardManager) requireActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clipData = ClipData.newPlainText(getString(R.string.app_name), sharedTrack.getUrl());
                clipboardManager.setPrimaryClip(clipData);
                refreshShares();
                dismissBottomSheet();
            } else {
                Toast.makeText(requireContext(), getString(R.string.share_unsupported_error), Toast.LENGTH_SHORT).show();
                dismissBottomSheet();
            }
        }));

        share.setVisibility(Preferences.isSharingEnabled() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onClick(View v) {
        dismissBottomSheet();
    }

    private void dismissBottomSheet() {
        dismiss();
    }

    private void updateDownloadButtons() {
        if (downloadButton == null || removeButton == null) {
            return;
        }

        if (Preferences.getDownloadDirectoryUri() == null) {
            boolean downloaded = DownloadUtil.getDownloadTracker(requireContext()).isDownloaded(song.getId());
            downloadButton.setVisibility(downloaded ? View.GONE : View.VISIBLE);
            removeButton.setVisibility(downloaded ? View.VISIBLE : View.GONE);
        } else {
            boolean hasLocal = ExternalAudioReader.getUri(song) != null;
            downloadButton.setVisibility(hasLocal ? View.GONE : View.VISIBLE);
            removeButton.setVisibility(hasLocal ? View.VISIBLE : View.GONE);
        }
    }

    private void initAssetLinkChips(View root) {
        assetLinkChipGroup = root.findViewById(R.id.asset_link_chip_group);
        songLinkChip = root.findViewById(R.id.asset_link_song_chip);
        albumLinkChip = root.findViewById(R.id.asset_link_album_chip);
        artistLinkChip = root.findViewById(R.id.asset_link_artist_chip);

        currentSongLink = bindAssetLinkChip(songLinkChip, AssetLinkUtil.TYPE_SONG, song.getId());
        currentAlbumLink = bindAssetLinkChip(albumLinkChip, AssetLinkUtil.TYPE_ALBUM, song.getAlbumId());
        currentArtistLink = bindAssetLinkChip(artistLinkChip, AssetLinkUtil.TYPE_ARTIST, song.getArtistId());
        syncAssetLinkGroupVisibility();
    }

    private AssetLinkUtil.AssetLink bindAssetLinkChip(@Nullable Chip chip, String type, @Nullable String id) {
        if (chip == null) return null;
        if (id == null || id.isEmpty()) {
            clearAssetLinkChip(chip);
            return null;
        }

        String label = getString(AssetLinkUtil.getLabelRes(type));
        AssetLinkUtil.AssetLink assetLink = AssetLinkUtil.buildAssetLink(type, id);
        if (assetLink == null) {
            clearAssetLinkChip(chip);
            return null;
        }

        chip.setText(getString(R.string.asset_link_chip_text, label, assetLink.id));
        chip.setVisibility(View.VISIBLE);

        chip.setOnClickListener(v -> {
            if (assetLink != null) {
                ((MainActivity) requireActivity()).openAssetLink(assetLink);
            }
        });

        chip.setOnLongClickListener(v -> {
            if (assetLink != null) {
                AssetLinkUtil.copyToClipboard(requireContext(), assetLink);
                Toast.makeText(requireContext(), getString(R.string.asset_link_copied_toast, id), Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        return assetLink;
    }

    private void clearAssetLinkChip(@Nullable Chip chip) {
        if (chip == null) return;
        chip.setVisibility(View.GONE);
        chip.setText("");
        chip.setOnClickListener(null);
        chip.setOnLongClickListener(null);
    }

    private void syncAssetLinkGroupVisibility() {
        if (assetLinkChipGroup == null) return;
        boolean hasVisible = false;
        for (int i = 0; i < assetLinkChipGroup.getChildCount(); i++) {
            View child = assetLinkChipGroup.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                hasVisible = true;
                break;
            }
        }
        assetLinkChipGroup.setVisibility(hasVisible ? View.VISIBLE : View.GONE);
    }

    private void bindAssetLinkView(@Nullable View view, @Nullable AssetLinkUtil.AssetLink assetLink) {
        if (view == null) return;
        if (assetLink == null) {
            AssetLinkUtil.clearLinkAppearance(view);
            view.setOnClickListener(null);
            view.setOnLongClickListener(null);
            view.setClickable(false);
            view.setLongClickable(false);
            return;
        }

        view.setClickable(true);
        view.setLongClickable(true);
        AssetLinkUtil.applyLinkAppearance(view);
        view.setOnClickListener(v -> ((MainActivity) requireActivity()).openAssetLink(assetLink, !AssetLinkUtil.TYPE_SONG.equals(assetLink.type)));
        view.setOnLongClickListener(v -> {
            AssetLinkUtil.copyToClipboard(requireContext(), assetLink);
            Toast.makeText(requireContext(), getString(R.string.asset_link_copied_toast, assetLink.id), Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private void initializeMediaBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseMediaBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    private void refreshShares() {
        homeViewModel.refreshShares(requireActivity());
    }
}
