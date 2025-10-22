package com.cappielloantonio.tempo.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentSongPlaylistsBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.ui.adapter.PlaylistHorizontalAdapter;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.viewmodel.SongPlaylistsViewModel;

import com.cappielloantonio.tempo.subsonic.models.Playlist;

import java.util.List;

@UnstableApi
public class SongPlaylistsFragment extends Fragment implements ClickCallback {
    private FragmentSongPlaylistsBinding binding;
    private SongPlaylistsViewModel songPlaylistsViewModel;
    private PlaylistHorizontalAdapter playlistAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSongPlaylistsBinding.inflate(inflater, container, false);
        songPlaylistsViewModel = new ViewModelProvider(this).get(SongPlaylistsViewModel.class);

        Child song = requireArguments().getParcelable(Constants.TRACK_OBJECT);
        initToolbar();
        bindSongInfo(song);
        initRecyclerView();

        if (song != null) {
            songPlaylistsViewModel.setSong(song);
            observePlaylists();
        } else {
            showMissingSongState();
        }

        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        playlistAdapter = null;
    }

    private void initToolbar() {
        MainActivity activity = (MainActivity) requireActivity();
        activity.setSupportActionBar(binding.toolbar);

        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            activity.getSupportActionBar().setDisplayShowHomeEnabled(true);
            activity.getSupportActionBar().setTitle(R.string.song_playlists_title);
        }

        binding.toolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
    }

    private void bindSongInfo(@Nullable Child song) {
        if (song == null) {
            binding.songTitleTextView.setText(R.string.song_playlists_unknown_song);
            binding.songArtistTextView.setText(R.string.song_playlists_unknown_artist);
            binding.songCoverImageView.setImageResource(R.drawable.ic_placeholder_album);
            return;
        }

        binding.songTitleTextView.setText(song.getTitle());
        binding.songTitleTextView.setSelected(true);

        binding.songArtistTextView.setText(song.getArtist());
        binding.songArtistTextView.setSelected(true);

        CustomGlideRequest.Builder
                .from(requireContext(), song.getCoverArtId(), CustomGlideRequest.ResourceType.Song)
                .build()
                .into(binding.songCoverImageView);
    }

    private void initRecyclerView() {
        binding.playlistsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.playlistsRecyclerView.setHasFixedSize(true);

        playlistAdapter = new PlaylistHorizontalAdapter(this);
        binding.playlistsRecyclerView.setAdapter(playlistAdapter);
    }

    private void observePlaylists() {
        binding.progressIndicator.setVisibility(View.VISIBLE);
        songPlaylistsViewModel.getPlaylistsContainingSong().observe(getViewLifecycleOwner(), this::renderPlaylists);
    }

    private void showMissingSongState() {
        binding.progressIndicator.setVisibility(View.GONE);
        binding.playlistsRecyclerView.setVisibility(View.GONE);
        binding.emptyTextView.setVisibility(View.VISIBLE);
        binding.emptyTextView.setText(R.string.song_playlists_error);
    }

    private void renderPlaylists(@Nullable List<Playlist> playlists) {
        if (playlists == null) {
            binding.progressIndicator.setVisibility(View.VISIBLE);
            binding.playlistsRecyclerView.setVisibility(View.GONE);
            binding.emptyTextView.setVisibility(View.GONE);
            return;
        }

        binding.progressIndicator.setVisibility(View.GONE);
        boolean hasItems = !playlists.isEmpty();

        if (hasItems) {
            playlistAdapter.setItems(playlists);
            binding.playlistsRecyclerView.setVisibility(View.VISIBLE);
            binding.emptyTextView.setVisibility(View.GONE);
        } else {
            playlistAdapter.setItems(playlists);
            binding.playlistsRecyclerView.setVisibility(View.GONE);
            binding.emptyTextView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onPlaylistClick(Bundle bundle) {
        bundle.putBoolean("is_offline", false);
        NavHostFragment.findNavController(this).navigate(R.id.playlistPageFragment, bundle);
    }
}
