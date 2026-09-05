package com.downpour;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Singleton manager for the Silent Hill: Downpour title screen and settings background music.
 * Seamlessly transitions between TitleActivity and SettingsActivity without interrupting playback.
 * Performs smooth synchronized volume fade-out when entering gameplay.
 */
public class MenuMusicManager {

    private static final String TAG = "MenuMusicManager";
    private static MenuMusicManager instance;

    private MediaPlayer mediaPlayer;
    private ValueAnimator fadeAnimator;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pauseRunnable = this::pausePlayback;

    private int activeActivityCount = 0;
    private boolean isTransitioningToGame = false;
    private float currentVolume = 1.0f;

    private MenuMusicManager() {}

    public static synchronized MenuMusicManager getInstance() {
        if (instance == null) {
            instance = new MenuMusicManager();
        }
        return instance;
    }

    /**
     * Called in onResume of TitleActivity and SettingsActivity.
     */
    public synchronized void onActivityResumed(Context context) {
        activeActivityCount++;
        handler.removeCallbacks(pauseRunnable);

        if (isTransitioningToGame) {
            // User returned back to menu (e.g. game closed or cancelled)
            isTransitioningToGame = false;
        }

        startOrResume(context.getApplicationContext());
    }

    /**
     * Called in onPause of TitleActivity and SettingsActivity.
     */
    public synchronized void onActivityPaused() {
        activeActivityCount = Math.max(0, activeActivityCount - 1);

        if (isTransitioningToGame) {
            // Already handling fade-out into game
            return;
        }

        if (activeActivityCount == 0) {
            // 350ms buffer to allow smooth transition between TitleActivity and SettingsActivity
            handler.removeCallbacks(pauseRunnable);
            handler.postDelayed(pauseRunnable, 350);
        }
    }

    /**
     * Starts or resumes music playback at full volume.
     */
    public synchronized void startOrResume(Context context) {
        if (isTransitioningToGame) {
            return;
        }

        if (fadeAnimator != null && fadeAnimator.isRunning()) {
            fadeAnimator.cancel();
            fadeAnimator = null;
        }

        currentVolume = 1.0f;

        if (mediaPlayer == null) {
            try {
                mediaPlayer = MediaPlayer.create(context, R.raw.title_screen);
                if (mediaPlayer == null) {
                    Log.w(TAG, "MediaPlayer.create returned null for R.raw.title_screen");
                    return;
                }

                mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .build());

                mediaPlayer.setLooping(true);
                mediaPlayer.setVolume(1.0f, 1.0f);
                mediaPlayer.start();
                Log.i(TAG, "Title screen background music started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start title screen music: " + e.getMessage(), e);
            }
        } else {
            try {
                mediaPlayer.setVolume(1.0f, 1.0f);
                if (!mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                    Log.i(TAG, "Title screen background music resumed");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to resume music: " + e.getMessage(), e);
            }
        }
    }

    private synchronized void pausePlayback() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    Log.i(TAG, "Title screen music paused (app minimized or navigated away)");
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Fades volume smoothly from current volume to 0 over durationMs,
     * in sync with the fade-to-black screen transition, then stops and releases the player.
     */
    public synchronized void fadeOutAndStop(long durationMs, Runnable onComplete) {
        isTransitioningToGame = true;
        handler.removeCallbacks(pauseRunnable);

        if (mediaPlayer == null || !mediaPlayer.isPlaying()) {
            stopAndRelease();
            if (onComplete != null) onComplete.run();
            return;
        }

        if (fadeAnimator != null && fadeAnimator.isRunning()) {
            fadeAnimator.cancel();
        }

        fadeAnimator = ValueAnimator.ofFloat(currentVolume, 0.0f);
        fadeAnimator.setDuration(durationMs);
        fadeAnimator.addUpdateListener(animation -> {
            float vol = (float) animation.getAnimatedValue();
            currentVolume = vol;
            synchronized (MenuMusicManager.this) {
                if (mediaPlayer != null) {
                    try {
                        mediaPlayer.setVolume(vol, vol);
                    } catch (Exception ignored) {}
                }
            }
        });

        fadeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                synchronized (MenuMusicManager.this) {
                    stopAndRelease();
                }
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });

        fadeAnimator.start();
        Log.i(TAG, "Music fading out over " + durationMs + "ms into gameplay transition");
    }

    /**
     * Stops and releases the MediaPlayer immediately.
     */
    public synchronized void stopAndRelease() {
        handler.removeCallbacks(pauseRunnable);
        if (fadeAnimator != null) {
            fadeAnimator.cancel();
            fadeAnimator = null;
        }

        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.reset();
                mediaPlayer.release();
                Log.i(TAG, "Title screen music stopped and released cleanly");
            } catch (Exception e) {
                Log.w(TAG, "Error releasing MediaPlayer: " + e.getMessage());
            } finally {
                mediaPlayer = null;
            }
        }
        currentVolume = 1.0f;
    }
}
