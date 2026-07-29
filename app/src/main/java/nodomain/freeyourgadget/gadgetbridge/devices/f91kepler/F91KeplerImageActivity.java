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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

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
 * Picks a photo, converts it to the watch's 96×39 black-and-white panel and
 * uploads it to Image mode.
 *
 * <p>Every control re-renders the preview, and the preview is the packed result
 * blown up with nearest-neighbour sampling — so what is on screen is pixel-exact
 * what the watch will light. Pressing send stores the frame (see
 * {@link F91KeplerImageStore}) and asks the service to upload it; if the watch is
 * away, the stored frame goes out on the next connect instead.
 */
public class F91KeplerImageActivity extends AbstractGBActivity {
    private static final Logger LOG = LoggerFactory.getLogger(F91KeplerImageActivity.class);

    /** Longest edge we keep when decoding the source, plenty for a 96×39 target. */
    private static final int MAX_SOURCE_EDGE = 1600;
    /** Preview magnification. */
    private static final int PREVIEW_SCALE = 4;

    private GBDevice device;

    private ImageView preview;
    private TextView status;
    private SwitchCompat fillSwitch;
    private View panRow;
    private SeekBar panBar;
    private SwitchCompat ditherSwitch;
    private View thresholdRow;
    private SeekBar thresholdBar;
    private SwitchCompat invertSwitch;
    private Button sendButton;

    private Bitmap source;
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

        preview = findViewById(R.id.f91_image_preview);
        status = findViewById(R.id.f91_image_status);
        fillSwitch = findViewById(R.id.f91_image_fill);
        panRow = findViewById(R.id.f91_image_pan_row);
        panBar = findViewById(R.id.f91_image_pan);
        ditherSwitch = findViewById(R.id.f91_image_dither);
        thresholdRow = findViewById(R.id.f91_image_threshold_row);
        thresholdBar = findViewById(R.id.f91_image_threshold);
        invertSwitch = findViewById(R.id.f91_image_invert);
        sendButton = findViewById(R.id.f91_image_send);

        thresholdBar.setMax(255);
        thresholdBar.setProgress(F91KeplerImageConverter.DEFAULT_THRESHOLD);
        panBar.setMax(100);
        panBar.setProgress(50);

        picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) {
                loadSource(uri);
            }
        });

        final Button choose = findViewById(R.id.f91_image_choose);
        choose.setOnClickListener(v -> picker.launch(new String[]{"image/*"}));
        sendButton.setOnClickListener(v -> send());

        final CompoundButton.OnCheckedChangeListener onToggle = (button, checked) -> render();
        fillSwitch.setOnCheckedChangeListener(onToggle);
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
        panBar.setOnSeekBarChangeListener(onSlide);
        thresholdBar.setOnSeekBarChangeListener(onSlide);

        ditherSwitch.setChecked(true);
        showStoredFrame();
        render();
    }

    /** Show the last uploaded frame so re-opening the screen is not a blank slate. */
    private void showStoredFrame() {
        final byte[] stored = F91KeplerImageStore.load(device);
        if (stored == null) {
            return;
        }
        bits = F91KeplerImageCodec.unpackImage(stored);
        preview.setImageBitmap(F91KeplerImageConverter.toPreview(bits, PREVIEW_SCALE));
        status.setText(R.string.f91_image_showing_stored);
    }

    private void loadSource(final Uri uri) {
        try {
            source = decodeBounded(uri);
        } catch (final IOException | OutOfMemoryError e) {
            LOG.warn("Could not decode the picked image", e);
            source = null;
        }
        if (source == null) {
            GB.toast(this, getString(R.string.f91_image_load_failed), Toast.LENGTH_LONG, GB.ERROR);
            return;
        }
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
        try (final InputStream in = getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, options);
        }
    }

    /** Re-run the conversion for the current settings and update the preview. */
    private void render() {
        panRow.setVisibility(fillSwitch.isChecked() ? View.VISIBLE : View.GONE);
        thresholdRow.setVisibility(ditherSwitch.isChecked() ? View.GONE : View.VISIBLE);

        if (source == null) {
            // Nothing picked yet: either the stored frame is on screen, or nothing is.
            sendButton.setEnabled(bits != null);
            if (bits == null) {
                status.setText(R.string.f91_image_none);
            }
            return;
        }

        final Bitmap panel = F91KeplerImageConverter.fitToPanel(
                source, fillSwitch.isChecked(), panBar.getProgress() / 100f);
        bits = F91KeplerImageConverter.toBits(panel, ditherSwitch.isChecked(),
                thresholdBar.getProgress(), invertSwitch.isChecked());
        preview.setImageBitmap(F91KeplerImageConverter.toPreview(bits, PREVIEW_SCALE));
        status.setText(R.string.f91_image_ready);
        sendButton.setEnabled(true);
    }

    private void send() {
        if (bits == null) {
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

        if (device.isInitialized()) {
            GBApplication.deviceService(device)
                    .onSendConfiguration(F91KeplerConstants.PREF_IMAGE_UPLOAD);
            GB.toast(this, getString(R.string.f91_image_sending), Toast.LENGTH_SHORT, GB.INFO);
        } else {
            // The stored frame is picked up by the reconnect check in the support
            // class, so there is nothing else to do here.
            GB.toast(this, getString(R.string.f91_image_saved_offline), Toast.LENGTH_LONG, GB.INFO);
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
