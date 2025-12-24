package me.Luki.WalkieTalkiePlugin.voice;

/**
 * A minimal interface for optional voice integrations.
 *
 * This interface must not reference Simple Voice Chat API types,
 * so the plugin can load even when SVC is not installed.
 */
public interface VoiceBridge {

    void reloadFromConfig();

    /**
     * @return true if the integration is currently hooked/active.
     */
    boolean isHooked();
}
