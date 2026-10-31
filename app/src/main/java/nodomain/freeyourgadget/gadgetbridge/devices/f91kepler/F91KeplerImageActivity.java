/*  Copyright (C) 2026 Zebsi235

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.f91kepler;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.exifinterface.media.ExifInterface;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * Picks a photo, lets the user crop it to the watch's 96×39 panel (pinch to zoom,
 * drag to position — see {@link F91KeplerCropView}), converts it to black-and-white
 * and uploads it to Image mode.
 *
 * <p>Every control — and every crop pan/zoom — re-renders the preview, and the
 * preview is the packed result blown up with nearest-neighbour sampling, so what is
 * on screen is pixel-exact what the watch will light. Pressing send stores the frame
 * (see {@link F91KeplerImageStore}) and asks the service to upload it; if the watch
 * is away, the stored frame goes out on the next connect instead. The service
 * reports the upload ({@link F91KeplerConstants#ACTION_TRANSFER_STATUS}): a progress
 * bar while it runs, then sent, failed (Send turns into Try again) or interrupted.
 */
public class F91KeplerImageActivity extends AbstractGBActivity {
    private static final Logger LOG = LoggerFactory.getLogger(F91KeplerImageActivity.class);

    /** Longest edge we keep when decoding the source, plenty for a 96×39 target. */
    private static final int MAX_SOURCE_EDGE = 1600;
    /** Preview magnification. */
    private static final int PREVIEW_SCALE = 4;

    private static final String STATE_SOURCE_URI = "f91_image_source_uri";
    private static final String STATE_CROP = "f91_image_crop";
    private static final String STATE_AWAITING = "f91_image_awaiting";

    private GBDevice device;

    private F91KeplerCropView cropView;
    private View cropHint;
    private ImageView preview;
    private TextView status;
    private SwitchCompat ditherSwitch;
    private View thresholdRow;
    private SeekBar thresholdBar;
    private SwitchCompat invertSwitch;
    private Button sendButton;
    private ProgressBar progress;

    /** An upload is running: Send stays off and the status line belongs to it. */
    private boolean uploading;
    /** Sent or saved for the watch and not answered yet: only then does an outcome
     *  replace the status line (a reconnect's "already there" must not cover a new edit). */
    private boolean awaiting;

    private final BroadcastReceiver imageStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (GBDevice.ACTION_DEVICE_CHANGED.equals(intent.getAction())) {
                final GBDevice changed = intent.getParcelableExtra(GBDevice.EXTRA_DEVICE);
                // A send the service never got to (the link went just as it was
                // tapped) would otherwise leave Send switched off for good.
                if (uploading && changed != null && device.getAddress().equals(changed.getAddress())
                        && !changed.isInitialized()) {
                    showUploadState(F91KeplerConstants.TRANSFER_STATE_INTERRUPTED, 0);
                }
                return;
            }
            if (device.getAddress().equals(intent.getStringExtra(F91KeplerConstants.EXTRA_TRANSFER_ADDRESS))
                    && F91KeplerTransfer.IMAGE.name().equals(intent.getStringExtra(F91KeplerConstants.EXTRA_TRANSFER_KIND))) {
                final int state = intent.getIntExtra(F91KeplerConstants.EXTRA_TRANSFER_STATE, 0);
                // An upload starting (also the reconnect re-send) is always news.
                if (awaiting || state == F91KeplerConstants.TRANSFER_STATE_SENDING) {
                    showUploadState(state, intent.getIntExtra(F91KeplerConstants.EXTRA_TRANSFER_PERCENT, 0));
                }
            }
        }
    };

    private Bitmap source;
    /** Where {@link #source} came from, kept so a rotation can decode it again. */
    @Nullable
    private Uri sourceUri;
    /** The one-bit result currently shown, or null when there is nothing to send. */
    private boolean[] bits;

    private ActivityResultLauncher<String[]> picker;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        device = getIntent().getParcelableExtra(GBDevice.EXTRA_DEVICE);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_f91kepler_image);

        if (device == null) {
            GB.toast(this, getString(R.string.f91_image_no_device), Toast.LENGTH_LONG, GB.ERROR);
            finish();
            return;
        }

        cropView = findViewById(R.id.f91_image_crop);
        cropHint = findViewById(R.id.f91_image_crop_hint);
        preview = findViewById(R.id.f91_image_preview);
        status = findViewById(R.id.f91_image_status);
        ditherSwitch = findViewById(R.id.f91_image_dither);
        thresholdRow = findViewById(R.id.f91_image_threshold_row);
        thresholdBar = findViewById(R.id.f91_image_threshold);
        invertSwitch = findViewById(R.id.f91_image_invert);
        sendButton = findViewById(R.id.f91_image_send);
        progress = findViewById(R.id.f91_image_progress);

        thresholdBar.setMax(255);
        thresholdBar.setProgress(F91KeplerImageConverter.DEFAULT_THRESHOLD);

        // Panning/zooming the crop re-renders the one-bit preview once the gesture settles.
        cropView.setOnCropChangedListener(this::render);

        picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) {
                loadSource(uri, null);
            }
        });

        final Button choose = findViewById(R.id.f91_image_choose);
        choose.setOnClickListener(v -> picker.launch(new String[]{"image/*"}));
        sendButton.setOnClickListener(v -> send());

        final CompoundButton.OnCheckedChangeListener onToggle = (button, checked) -> render();
        ditherSwitch.setOnCheckedChangeListener(onToggle);
        invertSwitch.setOnCheckedChangeListener(onToggle);

        final SeekBar.OnSeekBarChangeListener onSlide = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(final SeekBar bar, final int value, final boolean fromUser) {
                render();
            }

            @Override
            public void onStartTrackingTouch(final SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(final SeekBar bar) {
            }
        };
        thresholdBar.setOnSeekBarChangeListener(onSlide);

        ditherSwitch.setChecked(true);
        showStoredFrame();
        // A rotation recreates the screen: pick up the same photo and crop again.
        // The switches and the slider restore themselves (after this, each one
        // re-rendering through its listener).
        if (savedInstanceState != null) {
            // Turned mid-upload: the outcome that follows is still this screen's news.
            awaiting = savedInstanceState.getBoolean(STATE_AWAITING, false);
            final Uri uri = savedInstanceState.getParcelable(STATE_SOURCE_URI);
            if (uri != null) {
                loadSource(uri, savedInstanceState.getFloatArray(STATE_CROP));
            }
        }
        render();

        final IntentFilter filter = new IntentFilter(F91KeplerConstants.ACTION_TRANSFER_STATUS);
        filter.addAction(GBDevice.ACTION_DEVICE_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(imageStatusReceiver, filter);
        // Opened while an upload runs (a reconnect re-send, or a rotation mid-send):
        // the next progress broadcast shows it.
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(imageStatusReceiver);
        super.onDestroy();
    }

    /**
     * The device as the app sees it now. The intent's copy is a snapshot from when
     * the screen opened, so its connection state goes stale as soon as the watch
     * connects or drops.
     */
    private GBDevice currentDevice() {
        final GBDevice fresh = GBApplication.app().getDeviceManager().getDeviceByAddress(device.getAddress());
        return fresh != null ? fresh : device;
    }

    private void showUploadState(final int state, final int percent) {
        uploading = state == F91KeplerConstants.TRANSFER_STATE_SENDING;
        awaiting = uploading || state == F91KeplerConstants.TRANSFER_STATE_WAITING
                || state == F91KeplerConstants.TRANSFER_STATE_INTERRUPTED;
        progress.setVisibility(uploading ? View.VISIBLE : View.GONE);
        progress.setProgress(percent);
        sendButton.setText(state == F91KeplerConstants.TRANSFER_STATE_FAILED
                ? R.string.f91_transfer_retry : R.string.f91_image_send);
        sendButton.setEnabled(!uploading && bits != null);
        switch (state) {
            case F91KeplerConstants.TRANSFER_STATE_SENDING:
                status.setText(getString(R.string.f91_image_sending_percent, percent));
                break;
            case F91KeplerConstants.TRANSFER_STATE_DONE:
                status.setText(R.string.f91_image_sent);
                break;
            case F91KeplerConstants.TRANSFER_STATE_FAILED:
                status.setText(R.string.f91_image_failed_retry);
                break;
            case F91KeplerConstants.TRANSFER_STATE_INTERRUPTED:
                status.setText(R.string.f91_image_interrupted);
                break;
            case F91KeplerConstants.TRANSFER_STATE_WAITING:
                status.setText(R.string.f91_image_saved_offline);
                break;
            default:
                break;
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_AWAITING, awaiting);
        if (source != null && sourceUri != null) {
            outState.putParcelable(STATE_SOURCE_URI, sourceUri);
            outState.putFloatArray(STATE_CROP, cropView.getCropState());
        }
    }

    /** Show the last uploaded frame so re-opening the screen is not a blank slate. */
    private void showStoredFrame() {
        final byte[] stored = F91KeplerImageStore.load(device);
        if (stored == null) {
            return;
        }
        bits = F91KeplerImageCodec.unpackImage(stored);
        showPreview(bits);
        status.setText(R.string.f91_image_showing_stored);
    }

    /**
     * Show a one-bit result. The blow-up is nearest-neighbour, and the ImageView
     * scales it again to the screen width -- so filtering has to be off there too,
     * or the preview would be a smoothed lie about what the panel shows.
     */
    private void showPreview(final boolean[] lit) {
        preview.setImageBitmap(F91KeplerImageConverter.toPreview(lit, PREVIEW_SCALE));
        final Drawable drawable = preview.getDrawable();
        if (drawable != null) {
            drawable.setFilterBitmap(false);
        }
    }

    private void loadSource(final Uri uri, @Nullable final float[] cropState) {
        try {
            source = decodeBounded(uri);
        } catch (final IOException | OutOfMemoryError | RuntimeException e) {
            // RuntimeException: a provider can refuse a saved Uri after the process
            // was restarted (SecurityException); that must not crash the screen.
            LOG.warn("Could not decode the picked image", e);
            source = null;
        }
        if (source == null) {
            GB.toast(this, getString(R.string.f91_image_load_failed), Toast.LENGTH_LONG, GB.ERROR);
            return;
        }
        sourceUri = uri;
        cropView.setImage(source);
        cropView.setCropState(cropState);
        render();
    }

    /**
     * Decode at a bounded resolution: the target is 96×39, so a full-size photo
     * would be a pointless allocation on the way to being thrown away.
     */
    private Bitmap decodeBounded(final Uri uri) throws IOException {
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (final InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        final int longest = Math.max(bounds.outWidth, bounds.outHeight);
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (longest / options.inSampleSize > MAX_SOURCE_EDGE) {
            options.inSampleSize *= 2;
        }
        final Bitmap decoded;
        try (final InputStream in = getContentResolver().openInputStream(uri)) {
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        return decoded == null ? null : uprightByExif(uri, decoded);
    }

    /**
     * Camera photos are often stored sideways with an EXIF note saying how to
     * turn them; BitmapFactory ignores it, so a portrait shot would arrive lying
     * down and the crop view cannot rotate.
     */
    private Bitmap uprightByExif(final Uri uri, final Bitmap decoded) {
        final ExifInterface exif;
        try (final InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return decoded;
            }
            exif = new ExifInterface(in);
        } catch (final IOException | RuntimeException e) {
            // No EXIF (PNG, GIF) or an unreadable header: show it as stored.
            LOG.debug("No usable EXIF orientation in the picked image", e);
            return decoded;
        }
        final int degrees = exif.getRotationDegrees();
        final boolean flipped = exif.isFlipped();
        if (degrees == 0 && !flipped) {
            return decoded;
        }
        final Matrix matrix = new Matrix();
        matrix.setRotate(degrees);
        if (flipped) {
            matrix.postScale(-1, 1);
        }
        final Bitmap upright = Bitmap.createBitmap(decoded, 0, 0,
                decoded.getWidth(), decoded.getHeight(), matrix, true);
        if (upright != decoded) {
            decoded.recycle();
        }
        return upright;
    }

    /** Re-run the conversion for the current settings and update the preview. */
    private void render() {
        thresholdRow.setVisibility(ditherSwitch.isChecked() ? View.GONE : View.VISIBLE);

        // The crop and conversion controls only mean something once a photo is
        // picked. A stored frame can still be re-sent, but it cannot be re-converted
        // -- the source is long gone -- so leaving the controls live would invite the
        // user to adjust settings that do nothing.
        final boolean hasSource = source != null;
        final int sourceVisibility = hasSource ? View.VISIBLE : View.GONE;
        cropView.setVisibility(sourceVisibility);
        cropHint.setVisibility(sourceVisibility);
        ditherSwitch.setEnabled(hasSource);
        thresholdBar.setEnabled(hasSource);
        invertSwitch.setEnabled(hasSource);

        if (!hasSource) {
            // Either the stored frame is on screen, or there is nothing to send.
            sendButton.setEnabled(bits != null && !uploading);
            if (bits == null) {
                status.setText(R.string.f91_image_none);
            }
            return;
        }

        final Bitmap crop = cropView.getCropBitmap();
        if (crop == null) {
            // The crop view has not been laid out yet; it will call back through the
            // crop-changed listener once it has a size, and this will run again.
            return;
        }
        final Bitmap panel = F91KeplerImageConverter.scaleToPanel(crop);
        bits = F91KeplerImageConverter.toBits(panel, ditherSwitch.isChecked(),
                thresholdBar.getProgress(), invertSwitch.isChecked());
        showPreview(bits);
        if (!uploading) {
            // A changed image is a new send, not a retry of the failed one.
            awaiting = false;
            status.setText(R.string.f91_image_ready);
            sendButton.setText(R.string.f91_image_send);
            sendButton.setEnabled(true);
        }
    }

    private void send() {
        if (bits == null || uploading) {
            return;
        }
        final byte[] frame = F91KeplerImageCodec.packImage(bits);
        try {
            F91KeplerImageStore.save(device, frame);
        } catch (final IOException e) {
            LOG.error("Could not store the F91 image frame", e);
            GB.toast(this, getString(R.string.f91_image_store_failed), Toast.LENGTH_LONG, GB.ERROR);
            return;
        }

        // Every Send is a fresh start, also one saved while the watch is away.
        GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()).edit()
                .putBoolean(F91KeplerConstants.PREF_IMAGE_GAVE_UP, false).apply();
        final GBDevice now = currentDevice();
        if (now.isInitialized()) {
            // Off until the service reports back, so a quick second tap is not a second upload.
            showUploadState(F91KeplerConstants.TRANSFER_STATE_SENDING, 0);
            GBApplication.deviceService(now)
                    .onSendConfiguration(F91KeplerConstants.PREF_IMAGE_UPLOAD);
        } else {
            // The stored frame is picked up by the reconnect check in the support
            // class, so there is nothing else to do here.
            showUploadState(F91KeplerConstants.TRANSFER_STATE_WAITING, 0);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
