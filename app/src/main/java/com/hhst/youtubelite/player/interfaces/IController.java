package com.hhst.youtubelite.player.interfaces;

/**
 * Public interface for external use containing controller functionality.
 */
public interface IController {
    boolean isFullscreen();

    void exitFullscreen();

    void onPictureInPictureModeChanged(boolean isInPictureInPictureMode);
    void release();
    void canSkipToNext(boolean can);
    void canSkipToPrevious(boolean can);
}