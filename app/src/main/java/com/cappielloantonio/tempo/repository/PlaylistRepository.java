package com.cappielloantonio.tempo.repository;

import static android.provider.Settings.System.getString;

import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.PlaylistDao;
import com.cappielloantonio.tempo.subsonic.api.playlist.PlaylistClient;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.Playlist;
import com.cappielloantonio.tempo.subsonic.models.PlaylistWithSongs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class PlaylistRepository {
    @androidx.media3.common.util.UnstableApi
    private final PlaylistDao playlistDao = AppDatabase.getInstance().playlistDao();
    public MutableLiveData<List<Playlist>> getPlaylists(boolean random, int size) {
        MutableLiveData<List<Playlist>> listLivePlaylists = new MutableLiveData<>(new ArrayList<>());

        PlaylistClient playlistClient = App.getSubsonicClientInstance(false).getPlaylistClient();

        playlistClient
                .getPlaylists()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getPlaylists() != null && response.body().getSubsonicResponse().getPlaylists().getPlaylists() != null) {
                            List<Playlist> playlists = response.body().getSubsonicResponse().getPlaylists().getPlaylists();

                            if (random) {
                                Collections.shuffle(playlists);
                                listLivePlaylists.setValue(playlists.subList(0, Math.min(playlists.size(), size)));
                            } else {
                                listLivePlaylists.setValue(playlists);
                            }
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                    }
                });

        return listLivePlaylists;
    }

    public MutableLiveData<List<Child>> getPlaylistSongs(String id) {
        MutableLiveData<List<Child>> listLivePlaylistSongs = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getPlaylistClient()
                .getPlaylist(id)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getPlaylist() != null) {
                            List<Child> songs = response.body().getSubsonicResponse().getPlaylist().getEntries();
                            listLivePlaylistSongs.setValue(songs);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                    }
                });

        return listLivePlaylistSongs;
    }

    public LiveData<List<Playlist>> getPlaylistsContainingSong(String songId) {
        MutableLiveData<List<Playlist>> playlistsLiveData = new MutableLiveData<>();

        if (songId == null) {
            playlistsLiveData.setValue(Collections.emptyList());
            return playlistsLiveData;
        }

        playlistsLiveData.setValue(null);

        PlaylistClient playlistClient = App.getSubsonicClientInstance(false).getPlaylistClient();

        playlistClient
                .getPlaylists()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (!response.isSuccessful()
                                || response.body() == null
                                || response.body().getSubsonicResponse().getPlaylists() == null
                                || response.body().getSubsonicResponse().getPlaylists().getPlaylists() == null) {
                            playlistsLiveData.postValue(Collections.emptyList());
                            return;
                        }

                        List<Playlist> playlists = response.body().getSubsonicResponse().getPlaylists().getPlaylists();

                        if (playlists.isEmpty()) {
                            playlistsLiveData.postValue(Collections.emptyList());
                            return;
                        }

                        filterPlaylistsBySong(playlistClient, playlists, 0, songId, new ArrayList<>(), playlistsLiveData);
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        playlistsLiveData.postValue(Collections.emptyList());
                    }
                });

        return playlistsLiveData;
    }

    private void filterPlaylistsBySong(PlaylistClient playlistClient,
                                       List<Playlist> playlists,
                                       int index,
                                       String songId,
                                       List<Playlist> accumulator,
                                       MutableLiveData<List<Playlist>> target) {
        if (index >= playlists.size()) {
            target.postValue(new ArrayList<>(accumulator));
            return;
        }

        Playlist playlist = playlists.get(index);

        playlistClient
                .getPlaylist(playlist.getId())
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful()
                                && response.body() != null
                                && response.body().getSubsonicResponse().getPlaylist() != null) {
                            PlaylistWithSongs playlistWithSongs = response.body().getSubsonicResponse().getPlaylist();
                            List<Child> entries = playlistWithSongs.getEntries();

                            if (entries != null) {
                                for (Child entry : entries) {
                                    if (songId.equals(entry.getId())) {
                                        accumulator.add(playlist);
                                        break;
                                    }
                                }
                            }
                        }

                        filterPlaylistsBySong(playlistClient, playlists, index + 1, songId, accumulator, target);
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        filterPlaylistsBySong(playlistClient, playlists, index + 1, songId, accumulator, target);
                    }
                });
    }

    public MutableLiveData<Playlist> getPlaylist(String id) {
        MutableLiveData<Playlist> playlistLiveData = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getPlaylistClient()
                .getPlaylist(id)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful()
                                && response.body() != null
                                && response.body().getSubsonicResponse().getPlaylist() != null) {
                            playlistLiveData.setValue(response.body().getSubsonicResponse().getPlaylist());
                        } else {
                            playlistLiveData.setValue(null);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        playlistLiveData.setValue(null);
                    }
                });

        return playlistLiveData;
    }

    public void addSongToPlaylist(String playlistId, ArrayList<String> songsId) {
        if (songsId.isEmpty()) {
            Toast.makeText(App.getContext(), App.getContext().getString(R.string.playlist_chooser_dialog_toast_all_skipped), Toast.LENGTH_SHORT).show();
        } else{
            App.getSubsonicClientInstance(false)
                    .getPlaylistClient()
                    .updatePlaylist(playlistId, null, true, songsId, null)
                    .enqueue(new Callback<ApiResponse>() {
                        @Override
                        public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                            Toast.makeText(App.getContext(), App.getContext().getString(R.string.playlist_chooser_dialog_toast_add_success), Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                            Toast.makeText(App.getContext(), App.getContext().getString(R.string.playlist_chooser_dialog_toast_add_failure), Toast.LENGTH_SHORT).show();
                        }
                    });
        }
    }

    public void createPlaylist(String playlistId, String name, ArrayList<String> songsId) {
        App.getSubsonicClientInstance(false)
                .getPlaylistClient()
                .createPlaylist(playlistId, name, songsId)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {

                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });
    }

    public void updatePlaylist(String playlistId, String name, ArrayList<String> songsId) {
        App.getSubsonicClientInstance(false)
                .getPlaylistClient()
                .deletePlaylist(playlistId)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        createPlaylist(null, name, songsId);
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });
    }

    public void deletePlaylist(String playlistId) {
        App.getSubsonicClientInstance(false)
                .getPlaylistClient()
                .deletePlaylist(playlistId)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {

                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });
    }
    @androidx.media3.common.util.UnstableApi
    public LiveData<List<Playlist>> getPinnedPlaylists() {
        return playlistDao.getAll();
    }

    @androidx.media3.common.util.UnstableApi
    public void insert(Playlist playlist) {
        InsertThreadSafe insert = new InsertThreadSafe(playlistDao, playlist);
        Thread thread = new Thread(insert);
        thread.start();
    }

    @androidx.media3.common.util.UnstableApi
    public void delete(Playlist playlist) {
        DeleteThreadSafe delete = new DeleteThreadSafe(playlistDao, playlist);
        Thread thread = new Thread(delete);
        thread.start();
    }

    private static class InsertThreadSafe implements Runnable {
        private final PlaylistDao playlistDao;
        private final Playlist playlist;

        public InsertThreadSafe(PlaylistDao playlistDao, Playlist playlist) {
            this.playlistDao = playlistDao;
            this.playlist = playlist;
        }

        @Override
        public void run() {
            playlistDao.insert(playlist);
        }
    }

    private static class DeleteThreadSafe implements Runnable {
        private final PlaylistDao playlistDao;
        private final Playlist playlist;

        public DeleteThreadSafe(PlaylistDao playlistDao, Playlist playlist) {
            this.playlistDao = playlistDao;
            this.playlist = playlist;
        }

        @Override
        public void run() {
            playlistDao.delete(playlist);
        }
    }
}
