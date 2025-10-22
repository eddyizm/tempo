package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.repository.PlaylistRepository;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.Playlist;

import java.util.List;
import java.util.Objects;

public class SongPlaylistsViewModel extends AndroidViewModel {
    private final PlaylistRepository playlistRepository;

    private Child song;
    private LiveData<List<Playlist>> playlistsContainingSong = new MutableLiveData<>();
    private boolean hasLoadedPlaylists;

    public SongPlaylistsViewModel(@NonNull Application application) {
        super(application);
        playlistRepository = new PlaylistRepository();
    }

    public void setSong(@Nullable Child song) {
        if (song == null) {
            this.song = null;
            playlistsContainingSong = new MutableLiveData<>();
            hasLoadedPlaylists = false;
            return;
        }

        if (this.song == null || !Objects.equals(song.getId(), this.song.getId())) {
            this.song = song;
            hasLoadedPlaylists = false;
        }
    }

    public Child getSong() {
        return song;
    }

    public LiveData<List<Playlist>> getPlaylistsContainingSong() {
        if (!hasLoadedPlaylists && song != null) {
            playlistsContainingSong = playlistRepository.getPlaylistsContainingSong(song.getId());
            hasLoadedPlaylists = true;
        }

        return playlistsContainingSong;
    }
}
