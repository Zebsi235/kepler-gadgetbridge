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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A fixed-aspect crop window ({@link F91KeplerConstants#IMAGE_WIDTH}:{@link
 * F91KeplerConstants#IMAGE_HEIGHT}). The picked bitmap is drawn behind a full-view
 * crop rectangle; pinch to zoom, drag to pan. The image is always kept covering the
 * crop rect (no empty margins), and {@link #getCropBitmap()} returns exactly what
 * lies under it — the caller downscales that to the watch's 96x39.
 *
 * <p>Deliberately a small self-contained View rather than a vendored crop library,
 * to keep the fork's eventual upstream diff small.
 *
 * <p>When the crop changes (image set, resized, panned or zoomed) it fires
 * {@link OnCropChangedListener} so the host can refresh its one-bit preview. The
 * photo under the frame redraws live during a gesture; the listener fires once the
 * gesture settles (or on (re)layout), which is enough to keep the preview honest
 * without re-running the dither on every touch frame.
 */
public class F91KeplerCropView extends View {

    private static final float MAX_ZOOM = 8f;

    /** Notified whenever the visible crop has changed and settled. */
    public interface OnCropChangedListener {
        void onCropChanged();
    }

    private Bitmap source;
    private final Matrix matrix = new Matrix();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float minScale = 1f;
    private float scale = 1f;
    private float transX = 0f;
    private float transY = 0f;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    @Nullable
    private OnCropChangedListener cropListener;

    public F91KeplerCropView(final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setColor(Color.WHITE);
        framePaint.setStrokeWidth(getResources().getDisplayMetrics().density * 2f);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new PanListener());
    }

    public void setOnCropChangedListener(@Nullable final OnCropChangedListener listener) {
        this.cropListener = listener;
    }

    public void setImage(final Bitmap bitmap) {
        this.source = bitmap;
        resetTransform();
        invalidate();
    }

    public boolean hasImage() {
        return source != null;
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final int w = MeasureSpec.getSize(widthMeasureSpec);
        final int h = Math.round(w * (float) F91KeplerConstants.IMAGE_HEIGHT / F91KeplerConstants.IMAGE_WIDTH);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(final int w, final int h, final int oldw, final int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetTransform();
    }

    private void resetTransform() {
        if (source == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        minScale = coverScale();
        scale = minScale;
        transX = (getWidth() - source.getWidth() * scale) / 2f;
        transY = (getHeight() - source.getHeight() * scale) / 2f;
        applyMatrix();
        // Defer the callback: resetTransform runs during layout (onSizeChanged), and
        // the listener touches other views, so posting keeps it out of the layout pass.
        post(this::notifyCropChanged);
    }

    private float coverScale() {
        return Math.max((float) getWidth() / source.getWidth(),
                        (float) getHeight() / source.getHeight());
    }

    private void applyMatrix() {
        clampTransform();
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate(transX, transY);
    }

    private void clampTransform() {
        if (source == null) {
            return;
        }
        minScale = coverScale();
        if (scale < minScale) {
            scale = minScale;
        }
        final float scaledW = source.getWidth() * scale;
        final float scaledH = source.getHeight() * scale;
        // Keep the image covering the crop rect: translations stay in
        // [viewSize - scaledSize, 0] so no black margin ever appears.
        transX = Math.min(0f, Math.max(getWidth() - scaledW, transX));
        transY = Math.min(0f, Math.max(getHeight() - scaledH, transY));
    }

    private void notifyCropChanged() {
        if (cropListener != null) {
            cropListener.onCropChanged();
        }
    }

    @Override
    protected void onDraw(@NonNull final Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);
        if (source != null) {
            canvas.drawBitmap(source, matrix, bitmapPaint);
        }
        canvas.drawRect(1, 1, getWidth() - 1f, getHeight() - 1f, framePaint);
    }

    @Override
    public boolean onTouchEvent(final MotionEvent event) {
        boolean handled = scaleDetector.onTouchEvent(event);
        handled = gestureDetector.onTouchEvent(event) || handled;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // Gesture settled — refresh the one-bit preview from the new crop.
                getParent().requestDisallowInterceptTouchEvent(false);
                notifyCropChanged();
                break;
            default:
                break;
        }
        return handled || super.onTouchEvent(event);
    }

    /** A view-sized bitmap of exactly what lies under the crop rect, or null if no
     *  image has been set yet. */
    @Nullable
    public Bitmap getCropBitmap() {
        if (source == null || getWidth() == 0 || getHeight() == 0) {
            return null;
        }
        final Bitmap out = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        c.drawBitmap(source, matrix, bitmapPaint);
        return out;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(@NonNull final ScaleGestureDetector detector) {
            final float focusX = detector.getFocusX();
            final float focusY = detector.getFocusY();
            final float newScale = Math.max(minScale, Math.min(scale * detector.getScaleFactor(), minScale * MAX_ZOOM));
            final float applied = newScale / scale;
            // Zoom around the pinch focus point.
            transX = focusX - (focusX - transX) * applied;
            transY = focusY - (focusY - transY) * applied;
            scale = newScale;
            applyMatrix();
            invalidate();
            return true;
        }
    }

    private class PanListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(final MotionEvent e1, @NonNull final MotionEvent e2,
                                final float distanceX, final float distanceY) {
            transX -= distanceX;
            transY -= distanceY;
            applyMatrix();
            invalidate();
            return true;
        }
    }
}
