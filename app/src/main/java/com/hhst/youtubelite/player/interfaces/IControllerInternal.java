package com.hhst.youtubelite.player.interfaces;

/**
 * Internal controller interface for module use.
 * This interface provides additional methods for internal module communication.
 */
public interface IControllerInternal extends IController {
    void showHint(String text, long durationMs);
    void hideHint();
    boolean isControlsVisible();
    void setControlsVisible(boolean visible);
    void setIsGesturing(boolean gesturing);
    void setLongPress(boolean longPress);
}