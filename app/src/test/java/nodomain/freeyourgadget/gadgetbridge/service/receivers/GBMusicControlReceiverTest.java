package nodomain.freeyourgadget.gadgetbridge.service.receivers;

import static org.junit.Assert.assertEquals;

import android.media.session.PlaybackState;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class GBMusicControlReceiverTest {
    @Test
    public void thePlayingSessionWinsOverAPausedOneListedFirst() {
        assertEquals(1, GBMusicControlReceiver.pickControllerIndex(Arrays.asList(
                PlaybackState.STATE_PAUSED, PlaybackState.STATE_PLAYING, PlaybackState.STATE_STOPPED)));
    }

    @Test
    public void bufferingCountsAsPlaying() {
        assertEquals(2, GBMusicControlReceiver.pickControllerIndex(Arrays.asList(
                PlaybackState.STATE_PAUSED, PlaybackState.STATE_NONE, PlaybackState.STATE_BUFFERING)));
    }

    @Test
    public void nothingPlayingKeepsTheMostRecentSession() {
        assertEquals(0, GBMusicControlReceiver.pickControllerIndex(Arrays.asList(
                PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED)));
        assertEquals(0, GBMusicControlReceiver.pickControllerIndex(
                Collections.singletonList(PlaybackState.STATE_NONE)));
    }

    @Test
    public void twoPlayingSessionsKeepAndroidsOrder() {
        assertEquals(0, GBMusicControlReceiver.pickControllerIndex(Arrays.asList(
                PlaybackState.STATE_PLAYING, PlaybackState.STATE_PLAYING)));
    }
}
