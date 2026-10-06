package com.jairo.minhacamera;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.util.Rational;
import android.util.Log;
import android.view.Gravity;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.common.InputImage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String LOG_TAG = "myCamera";
    private static final int CAMERA_PERMISSION = 10;
    private static final int AUDIO_PERMISSION = 11;
    private static final int[] WB_MODES = {
            CaptureRequest.CONTROL_AWB_MODE_AUTO,
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
    };
    private static final String[] WB_NAMES = {"Auto", "Daylight", "Cloudy", "Incandescent", "Fluorescent"};

    private TextureView preview;
    private TextView zoomBadge;
    private TextView qrLink;
    private TextView propertyTitle;
    private ImageView flashToggle;
    private ImageView modeToggle;
    private ImageView videoToggle;
    private View captureButton;
    private View resetButton;
    private View categoryRow;
    private SeekBar adjustmentSlider;
    private FrameLayout settingsPanel;
    private View gridOverlay;
    private TextView[] categoryButtons;
    private int selectedCategory;
    private int selectedProperty;
    private boolean configuringSlider;
    private boolean flashAvailable;
    private int[] wbChoices = {0};
    private final Runnable hideZoom = () -> { if (zoomBadge != null) zoomBadge.setVisibility(View.GONE); };
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader imageReader;
    private Surface previewSurface;
    private CaptureRequest.Builder previewRequest;
    private CameraCharacteristics characteristics;
    private Size previewSize;
    private Size photoPreviewSize;
    private Size videoPreviewSize;
    private Size videoSize;
    private Size[] availableJpegSizes;
    private Size[] availablePreviewSizes;
    private boolean conservativeCameraSizes;
    private boolean basicCameraRequest;
    private int previewFrameCount;
    private String cameraId;
    private boolean frontCamera;
    private boolean flashOn;
    private boolean capturing;
    private boolean videoMode;
    private volatile boolean recording;
    private volatile boolean recordingStarting;
    private MediaRecorder recorder;
    private ParcelFileDescriptor videoFd;
    private Uri videoUri;
    private int sessionGeneration;
    private volatile boolean opening;
    private volatile boolean resumed;
    private volatile int cameraGeneration;
    private int wbIndex;
    private int exposureSteps;
    private int exposureTenths;
    private float exposureStepEv = 1f;
    private float digitalExposureEv;
    private int brightness;
    private int contrast;
    private int saturation;
    private int warmth;
    private int tint;
    private int previewRotationDegrees;
    private boolean gridOn;
    private float zoomFactor = 1f;
    private float maxZoom = 1f;
    private float maxFocusDistance;
    private boolean manualFocusSupported;
    private int manualFocusProgress = 200;
    private Range<Integer> exposureRange = new Range<>(0, 0);
    private Settings pendingCapture;
    private BarcodeScanner barcodeScanner;
    private final Handler qrHandler = new Handler(Looper.getMainLooper());
    private boolean qrScanning;
    private String detectedQrUrl;
    private final Runnable hideQr = () -> { if (qrLink != null) qrLink.setVisibility(View.GONE); };
    private final Runnable scanQr = new Runnable() {
        @Override public void run() { scanPreviewForQr(); }
    };

    private static final class Settings {
        final int brightness, contrast, saturation, warmth, tint;
        final float digitalExposureEv;
        Settings(int b, int c, int s, int w, int t, float ev) {
            brightness = b; contrast = c; saturation = s; warmth = w; tint = t;
            digitalExposureEv = ev;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        previewRotationDegrees = getPreferences(MODE_PRIVATE).getInt("preview_rotation", 0);
        gridOn = getPreferences(MODE_PRIVATE).getBoolean("grid", false);
        barcodeScanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build());
        buildUi();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView textButton(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private View roundIcon(int type, String description) {
        View icon = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                paint.setStrokeCap(Paint.Cap.ROUND);
                if (type == 0) {
                    paint.setStyle(Paint.Style.FILL);
                    if (recording) {
                        paint.setColor(Color.WHITE);
                        canvas.drawRoundRect(cx - dp(22), cy - dp(22), cx + dp(22), cy + dp(22), dp(4), dp(4), paint);
                    } else if (videoMode) {
                        paint.setColor(Color.RED);
                        canvas.drawCircle(cx, cy, dp(29), paint);
                    } else {
                        paint.setColor(Color.WHITE);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(dp(4));
                        canvas.drawCircle(cx, cy, dp(34), paint);
                        paint.setStyle(Paint.Style.FILL);
                        canvas.drawCircle(cx, cy, dp(23), paint);
                    }
                } else if (type == 1) {
                    paint.setColor(0x88ffffff);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(5));
                    for (int i = -1; i <= 1; i++)
                        canvas.drawLine(cx - dp(15), cy + i * dp(12), cx + dp(15), cy + i * dp(12), paint);
                }
            }
        };
        icon.setContentDescription(description);
        icon.setClickable(true);
        return icon;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        preview = new TextureView(this);
        FrameLayout.LayoutParams cameraArea = new FrameLayout.LayoutParams(-1, -1);
        cameraArea.bottomMargin = dp(136);
        root.addView(preview, cameraArea);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int w, int h) { if (cameraHandler != null) openCamera(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int w, int h) { configureTransform(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { previewFrameCount++; }
        });
        ScaleGestureDetector pinch = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        if (maxZoom <= 1f) return false;
                        float next = Math.max(1f, Math.min(maxZoom, zoomFactor * detector.getScaleFactor()));
                        if (Math.abs(next - zoomFactor) < .005f) return true;
                        zoomFactor = next;
                        updatePreviewRequest();
                        updateZoomLabel();
                        return true;
                    }
                });
        preview.setOnTouchListener((view, event) -> { pinch.onTouchEvent(event); return true; });

        gridOverlay = new View(this) {
            private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                line.setColor(0x88ffffff); line.setStrokeWidth(dp(1));
                float width = getWidth(), height = getHeight();
                for (int i = 1; i < 3; i++) {
                    canvas.drawLine(width * i / 3, 0, width * i / 3, height, line);
                    canvas.drawLine(0, height * i / 3, width, height * i / 3, line);
                }
            }
        };
        gridOverlay.setVisibility(gridOn ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams gridArea = new FrameLayout.LayoutParams(-1, -1);
        gridArea.bottomMargin = dp(136);
        root.addView(gridOverlay, gridArea);

        zoomBadge = textButton("1.0x", 14);
        zoomBadge.setBackgroundColor(0x99000000);
        zoomBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams badgePlace = new FrameLayout.LayoutParams(dp(78), dp(36), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        badgePlace.topMargin = dp(18);
        root.addView(zoomBadge, badgePlace);

        LinearLayout topToggles = new LinearLayout(this);
        topToggles.setGravity(Gravity.CENTER_VERTICAL);
        flashToggle = new ImageView(this);
        flashToggle.setImageResource(R.drawable.ic_flash);
        flashToggle.setScaleType(ImageView.ScaleType.FIT_CENTER);
        flashToggle.setPadding(dp(8), dp(8), dp(8), dp(8));
        flashToggle.setContentDescription("Toggle flash");
        flashToggle.setOnClickListener(v -> {
            if (!flashAvailable) { show("Flash unavailable on this camera"); return; }
            flashOn = !flashOn;
            updateTopToggleStates();
            updatePreviewRequest();
        });
        topToggles.addView(flashToggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        modeToggle = new ImageView(this);
        modeToggle.setImageResource(R.drawable.ic_mode);
        modeToggle.setScaleType(ImageView.ScaleType.FIT_CENTER);
        modeToggle.setPadding(dp(8), dp(8), dp(8), dp(8));
        modeToggle.setContentDescription("Switch front and rear camera");
        modeToggle.setOnClickListener(v -> {
            if (recording || recordingStarting) { show("Stop recording before switching cameras"); return; }
            frontCamera = !frontCamera;
            flashOn = false;
            exposureTenths = 0;
            exposureSteps = 0;
            digitalExposureEv = 0f;
            manualFocusProgress = 200;
            updateTopToggleStates();
            closeCamera();
            preview.postDelayed(this::openCamera, 300);
        });
        topToggles.addView(modeToggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        videoToggle = new ImageView(this);
        videoToggle.setImageResource(R.drawable.ic_video);
        videoToggle.setScaleType(ImageView.ScaleType.FIT_CENTER);
        videoToggle.setPadding(dp(8), dp(8), dp(8), dp(8));
        videoToggle.setContentDescription("Switch video mode");
        videoToggle.setOnClickListener(v -> {
            if (recording || recordingStarting) { show("Stop recording first"); return; }
            videoMode = !videoMode;
            previewSize = videoMode && videoPreviewSize != null ? videoPreviewSize : photoPreviewSize;
            updateTopToggleStates();
            captureButton.invalidate();
            captureButton.setContentDescription(videoMode ? "Start video recording" : "Capture photo");
            configureTransform();
            if (camera != null) startPreview();
        });
        topToggles.addView(videoToggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        FrameLayout.LayoutParams topPlace = new FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP | Gravity.LEFT);
        topPlace.leftMargin = dp(8); topPlace.topMargin = dp(4);
        root.addView(topToggles, topPlace);
        updateTopToggleStates();

        qrLink = textButton("", 14);
        qrLink.setGravity(Gravity.CENTER_VERTICAL);
        qrLink.setPadding(dp(12), dp(8), dp(12), dp(8));
        qrLink.setBackgroundColor(0xdd17191f);
        qrLink.setMaxLines(2);
        qrLink.setPaintFlags(qrLink.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        qrLink.setEllipsize(android.text.TextUtils.TruncateAt.END);
        qrLink.setVisibility(View.GONE);
        qrLink.setOnClickListener(v -> {
            if (detectedQrUrl == null) return;
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(detectedQrUrl))); }
            catch (ActivityNotFoundException e) { show("No browser available for this link"); }
        });
        FrameLayout.LayoutParams linkPlace = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        linkPlace.leftMargin = dp(12); linkPlace.rightMargin = dp(12); linkPlace.topMargin = dp(58);
        root.addView(qrLink, linkPlace);

        settingsPanel = new FrameLayout(this);
        settingsPanel.setBackgroundColor(0x66000000);
        settingsPanel.setVisibility(View.GONE);
        LinearLayout adjustment = new LinearLayout(this);
        adjustment.setOrientation(LinearLayout.VERTICAL);
        adjustment.setPadding(dp(8), dp(6), dp(8), dp(4));
        propertyTitle = textButton("Exposure", 14);
        propertyTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        adjustment.addView(propertyTitle, new LinearLayout.LayoutParams(-1, dp(34)));
        LinearLayout sliderRow = new LinearLayout(this);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView previous = textButton("◀", 27);
        previous.setContentDescription("Previous property");
        previous.setOnClickListener(v -> showProperty(selectedCategory, selectedProperty - 1));
        sliderRow.addView(previous, new LinearLayout.LayoutParams(dp(55), dp(64)));
        adjustmentSlider = new SeekBar(this);
        adjustmentSlider.setProgressTintList(android.content.res.ColorStateList.valueOf(0xccffffff));
        adjustmentSlider.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x99ffffff));
        adjustmentSlider.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        adjustmentSlider.setSplitTrack(false);
        sliderRow.addView(adjustmentSlider, new LinearLayout.LayoutParams(0, dp(64), 1));
        TextView next = textButton("▶", 27);
        next.setContentDescription("Next property");
        next.setOnClickListener(v -> showProperty(selectedCategory, selectedProperty + 1));
        sliderRow.addView(next, new LinearLayout.LayoutParams(dp(55), dp(64)));
        adjustment.addView(sliderRow);
        settingsPanel.addView(adjustment, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams overlayPlace = new FrameLayout.LayoutParams(-1, dp(112), Gravity.BOTTOM);
        overlayPlace.bottomMargin = dp(136);
        root.addView(settingsPanel, overlayPlace);

        FrameLayout bar = new FrameLayout(this);
        bar.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams barPlace = new FrameLayout.LayoutParams(-1, dp(136), Gravity.BOTTOM);
        root.addView(bar, barPlace);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER);
        tabs.setVisibility(View.GONE);
        String[] categories = {"Light", "Tone", "Color", "View"};
        categoryButtons = new TextView[categories.length];
        for (int i = 0; i < categories.length; i++) {
            final int category = i;
            TextView tab = textButton(categories[i], 14);
            tab.setOnClickListener(v -> showProperty(category, 0));
            tabs.addView(tab, new LinearLayout.LayoutParams(dp(70), dp(36)));
            categoryButtons[i] = tab;
        }
        FrameLayout.LayoutParams tabsPlace = new FrameLayout.LayoutParams(-1, dp(40), Gravity.TOP);
        tabsPlace.topMargin = dp(2);
        bar.addView(tabs, tabsPlace);
        categoryRow = tabs;

        captureButton = roundIcon(0, "Capture photo");
        captureButton.setOnClickListener(v -> {
            if (videoMode) {
                if (recording) stopRecording();
                else startRecordingWithPermission();
            } else capture();
        });
        FrameLayout.LayoutParams shutterPlace = new FrameLayout.LayoutParams(dp(82), dp(82), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        shutterPlace.bottomMargin = dp(12);
        bar.addView(captureButton, shutterPlace);

        ImageView resetIcon = new ImageView(this);
        resetIcon.setImageResource(R.drawable.ic_reset);
        resetIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        resetIcon.setPadding(dp(6), dp(6), dp(6), dp(6));
        resetIcon.setContentDescription("Reset current property");
        resetButton = resetIcon;
        resetButton.setVisibility(View.GONE);
        resetButton.setOnClickListener(v -> adjustmentSlider.setProgress(defaultProgress()));
        FrameLayout.LayoutParams resetPlace = new FrameLayout.LayoutParams(dp(66), dp(66), Gravity.BOTTOM | Gravity.LEFT);
        resetPlace.leftMargin = dp(38); resetPlace.bottomMargin = dp(20);
        bar.addView(resetButton, resetPlace);

        View menu = roundIcon(1, "Toggle adjustments");
        menu.setOnClickListener(v -> setAdjustmentsOpen(settingsPanel.getVisibility() != View.VISIBLE));
        FrameLayout.LayoutParams menuPlace = new FrameLayout.LayoutParams(dp(66), dp(66), Gravity.BOTTOM | Gravity.RIGHT);
        menuPlace.rightMargin = dp(24); menuPlace.bottomMargin = dp(20);
        bar.addView(menu, menuPlace);

        adjustmentSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!configuringSlider) { changeProperty(progress); updateResetVisibility(); }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        setContentView(root);
        showProperty(0, 0);
    }

    private void setAdjustmentsOpen(boolean open) {
        settingsPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        categoryRow.setVisibility(open ? View.VISIBLE : View.GONE);
        updateResetVisibility();
    }

    private void updateTopToggleStates() {
        flashToggle.setAlpha(flashOn ? 1f : .5f);
        modeToggle.setAlpha(frontCamera ? 1f : .5f);
        videoToggle.setAlpha(videoMode ? 1f : .5f);
        if (recording) videoToggle.setColorFilter(Color.RED);
        else videoToggle.clearColorFilter();
    }

    private int propertyCount(int category) { return new int[]{2, 2, 3, 3}[category]; }

    private int currentProgress() {
        switch (selectedCategory) {
            case 0:
                if (selectedProperty == 0) return exposureTenths + 50;
                for (int i = 0; i < wbChoices.length; i++) if (wbChoices[i] == wbIndex) return i;
                return 0;
            case 1: return (selectedProperty == 0 ? brightness : contrast) + 50;
            case 2: return (selectedProperty == 0 ? saturation : selectedProperty == 1 ? warmth : tint) + 50;
            default:
                if (selectedProperty == 0) return gridOn ? 1 : 0;
                if (selectedProperty == 1) return previewRotationDegrees / 90;
                return manualFocusProgress;
        }
    }

    private int defaultProgress() {
        if (selectedCategory == 0) return selectedProperty == 0 ? 50 : 0;
        if (selectedCategory == 1 || selectedCategory == 2) return 50;
        return selectedCategory == 3 && selectedProperty == 2 ? 200 : 0;
    }

    private void showProperty(int category, int index) {
        selectedCategory = category;
        int count = propertyCount(category);
        selectedProperty = (index % count + count) % count;
        for (int i = 0; i < categoryButtons.length; i++) {
            categoryButtons[i].setBackgroundColor(i == category ? 0xff292929 : Color.TRANSPARENT);
        }
        int max;
        if (category == 0) max = selectedProperty == 0 ? 100
                : wbChoices.length - 1;
        else if (category == 1 || category == 2) max = 100;
        else max = selectedProperty == 1 ? 3 : selectedProperty == 2 ? 400 : 1;
        configuringSlider = true;
        adjustmentSlider.setMax(Math.max(0, max));
        adjustmentSlider.setProgress(currentProgress());
        configuringSlider = false;
        updatePropertyTitle();
        adjustmentSlider.setEnabled(max > 0
                && !(category == 3 && selectedProperty == 2 && !manualFocusSupported));
        updateResetVisibility();
    }

    private void updatePropertyTitle() {
        String[][] labels = {
                {"Exposure", "White balance"},
                {"Brightness", "Contrast"},
                {"Saturation", "Warmth", "Tint"},
                {"Grid", "Preview rotation", "Manual focus"}
        };
        String label = labels[selectedCategory][selectedProperty];
        if (selectedCategory == 0 && selectedProperty == 0) label += exposureTenths == 0 ? " · 0"
                : String.format(Locale.US, " · %+.1f EV", exposureTenths / 10f);
        else if (selectedCategory == 0 && selectedProperty == 1) label += " · " + WB_NAMES[wbIndex];
        else if (selectedCategory == 3 && selectedProperty == 0) label += gridOn ? " · On" : " · Off";
        else if (selectedCategory == 3 && selectedProperty == 1) label += " · " + previewRotationDegrees + "°";
        else if (selectedCategory == 3 && selectedProperty == 2) label += !manualFocusSupported
                ? " · Unavailable" : manualFocusProgress == 200 ? " · Auto"
                : " · " + (manualFocusProgress - 200) + "%";
        propertyTitle.setText(label);
    }

    private void changeProperty(int progress) {
        if (selectedCategory == 0) {
            if (selectedProperty == 0) { exposureTenths = progress - 50; updateExposureSettings(); }
            else { wbIndex = wbChoices[progress]; updatePreviewRequest(); }
        } else if (selectedCategory == 1) {
            if (selectedProperty == 0) brightness = progress - 50;
            else contrast = progress - 50;
            updateFilter();
        } else if (selectedCategory == 2) {
            if (selectedProperty == 0) saturation = progress - 50;
            else if (selectedProperty == 1) warmth = progress - 50;
            else tint = progress - 50;
            updateFilter();
        } else {
            if (selectedProperty == 0) {
                gridOn = progress == 1;
                gridOverlay.setVisibility(gridOn ? View.VISIBLE : View.GONE);
                getPreferences(MODE_PRIVATE).edit().putBoolean("grid", gridOn).apply();
            } else if (selectedProperty == 1) {
                previewRotationDegrees = progress * 90;
                getPreferences(MODE_PRIVATE).edit().putInt("preview_rotation", previewRotationDegrees).apply();
                configureTransform();
            } else if (selectedProperty == 2 && manualFocusSupported) {
                manualFocusProgress = progress;
                updatePreviewRequest();
            }
        }
        updatePropertyTitle();
    }

    private void updateExposureSettings() {
        float requestedEv = exposureTenths / 10f;
        int hardwareSteps = exposureStepEv > 0f ? Math.round(requestedEv / exposureStepEv) : 0;
        exposureSteps = Math.max(exposureRange.getLower(), Math.min(exposureRange.getUpper(), hardwareSteps));
        digitalExposureEv = requestedEv - exposureSteps * exposureStepEv;
        updatePreviewRequest();
        updateFilter();
    }

    private void updateResetVisibility() {
        if (resetButton != null) resetButton.setVisibility(settingsPanel.getVisibility() == View.VISIBLE
                && currentProgress() != defaultProgress() ? View.VISIBLE : View.GONE);
    }

    private void updateZoomLabel() {
        zoomBadge.setText(String.format(Locale.US, "%.1fx", zoomFactor));
        zoomBadge.setVisibility(View.VISIBLE);
        zoomBadge.removeCallbacks(hideZoom);
        zoomBadge.postDelayed(hideZoom, 1100);
    }

    @Override public void onBackPressed() {
        if (settingsPanel.getVisibility() == View.VISIBLE) setAdjustmentsOpen(false);
        else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        cameraThread = new HandlerThread("camera-worker"); cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        if (preview.isAvailable()) openCamera();
        qrHandler.postDelayed(scanQr, 900);
    }

    @Override protected void onPause() {
        resumed = false;
        qrHandler.removeCallbacks(scanQr);
        qrLink.removeCallbacks(hideQr);
        qrLink.setVisibility(View.GONE);
        detectedQrUrl = null;
        if (recording) stopRecording();
        else if (recordingStarting) abortRecording();
        closeCamera();
        if (cameraThread != null) { cameraThread.quitSafely(); cameraThread = null; cameraHandler = null; }
        super.onPause();
    }

    @Override protected void onDestroy() {
        qrHandler.removeCallbacks(scanQr);
        barcodeScanner.close();
        super.onDestroy();
    }

    private void scanPreviewForQr() {
        if (!resumed || qrScanning) return;
        if (camera == null || !preview.isAvailable() || preview.getWidth() == 0 || preview.getHeight() == 0) {
            qrHandler.postDelayed(scanQr, 700);
            return;
        }
        int width = Math.min(preview.getWidth(), 720);
        int height = Math.max(1, Math.round((float) width * preview.getHeight() / preview.getWidth()));
        Bitmap frame = preview.getBitmap(width, height);
        if (frame == null) { qrHandler.postDelayed(scanQr, 700); return; }
        qrScanning = true;
        barcodeScanner.process(InputImage.fromBitmap(frame, 0))
                .addOnSuccessListener(codes -> {
                    if (!resumed) return;
                    for (Barcode code : codes) {
                        String url = webUrl(code.getRawValue());
                        if (url != null) { showQrLink(url); break; }
                    }
                })
                .addOnCompleteListener(task -> {
                    frame.recycle();
                    qrScanning = false;
                    if (resumed) qrHandler.postDelayed(scanQr, 700);
                });
    }

    private String webUrl(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.startsWith("www.")) value = "https://" + value;
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && uri.getHost() != null ? value : null;
    }

    private void showQrLink(String url) {
        detectedQrUrl = url;
        qrLink.setText("Open QR link: " + url);
        qrLink.setVisibility(View.VISIBLE);
        qrLink.removeCallbacks(hideQr);
        qrLink.postDelayed(hideQr, 3500);
    }

    private void openCamera() {
        if (camera != null || opening || !preview.isAvailable() || cameraHandler == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            cameraId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics info = manager.getCameraCharacteristics(id);
                Integer facing = info.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == (frontCamera ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) {
                    cameraId = id; characteristics = info; break;
                }
            }
            if (cameraId == null) { show("Camera unavailable on this device"); return; }
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) { show("This camera does not support JPEG capture"); return; }
            Size[] jpegSizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG);
            Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
            if (jpegSizes == null || jpegSizes.length == 0 || previewSizes == null || previewSizes.length == 0) {
                show("Camera output sizes unavailable"); return;
            }
            availableJpegSizes = jpegSizes;
            availablePreviewSizes = previewSizes;
            conservativeCameraSizes = false;
            basicCameraRequest = false;
            Size jpeg = Arrays.stream(jpegSizes).filter(s -> s.getWidth() <= 4000 && s.getHeight() <= 4000)
                    .max(Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()))
                    .orElse(Arrays.stream(jpegSizes).min(Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight())).orElse(jpegSizes[0]));
            photoPreviewSize = Arrays.stream(previewSizes).filter(s -> s.getWidth() <= 1920 && s.getHeight() <= 1080)
                    .min(Comparator.comparingDouble((Size s) -> Math.abs((double) s.getWidth() / s.getHeight() - (double) jpeg.getWidth() / jpeg.getHeight()))
                            .thenComparingDouble(s -> -((double) s.getWidth() * s.getHeight())))
                    .orElse(previewSizes[0]);
            Size[] videoSizes = map.getOutputSizes(MediaRecorder.class);
            videoSize = videoSizes == null || videoSizes.length == 0 ? null : Arrays.stream(videoSizes)
                    .filter(s -> s.getWidth() <= 1920 && s.getHeight() <= 1080)
                    .max(Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()))
                    .orElse(videoSizes[0]);
            videoPreviewSize = videoSize == null ? null : Arrays.stream(previewSizes)
                    .filter(s -> s.getWidth() <= 1920 && s.getHeight() <= 1080)
                    .min(Comparator.comparingDouble((Size s) -> Math.abs((double) s.getWidth() / s.getHeight()
                            - (double) videoSize.getWidth() / videoSize.getHeight()))
                            .thenComparingDouble(s -> -((double) s.getWidth() * s.getHeight())))
                    .orElse(photoPreviewSize);
            previewSize = videoMode && videoPreviewSize != null ? videoPreviewSize : photoPreviewSize;
            Log.i(LOG_TAG, "Opening camera " + cameraId + " (front=" + frontCamera
                    + "), JPEG " + jpeg + ", preview " + previewSize);
            Range<Integer> range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            exposureRange = range == null ? new Range<>(0, 0) : range;
            Rational step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            exposureStepEv = step == null || step.floatValue() <= 0f ? 1f : step.floatValue();
            updateExposureSettings();
            Float availableZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = availableZoom == null ? 1f : Math.min(8f, Math.max(1f, availableZoom));
            zoomFactor = 1f;
            Float focusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            maxFocusDistance = focusDistance == null ? 0f : focusDistance;
            int[] availableFocusModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            boolean hasAfOff = false;
            if (availableFocusModes != null) for (int mode : availableFocusModes)
                if (mode == CaptureRequest.CONTROL_AF_MODE_OFF) hasAfOff = true;
            manualFocusSupported = maxFocusDistance > 0f && hasAfOff
                    && characteristics.getAvailableCaptureRequestKeys().contains(CaptureRequest.LENS_FOCUS_DISTANCE);
            manualFocusProgress = 200;
            Boolean flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            flashAvailable = Boolean.TRUE.equals(flash);
            if (!flashAvailable) flashOn = false;
            updateTopToggleStates();
            int[] possible = new int[WB_MODES.length];
            int choices = 0;
            for (int i = 0; i < WB_MODES.length; i++) if (supportsWhiteBalance(WB_MODES[i])) possible[choices++] = i;
            wbChoices = choices == 0 ? new int[]{0} : Arrays.copyOf(possible, choices);
            boolean selectedModeAvailable = false;
            for (int choice : wbChoices) if (choice == wbIndex) selectedModeAvailable = true;
            if (!selectedModeAvailable) wbIndex = wbChoices[0];
            showProperty(selectedCategory, selectedProperty);
            createImageReader(jpeg);
            configureTransform();
            final int generation = cameraGeneration;
            opening = true;
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    if (!resumed || generation != cameraGeneration) { device.close(); return; }
                    opening = false; camera = device; startPreview();
                }
                @Override public void onDisconnected(CameraDevice device) {
                    device.close();
                    if (generation == cameraGeneration) {
                        opening = false; camera = null;
                        if (recording || recordingStarting) runOnUiThread(() -> abortRecording());
                        show("Camera disconnected");
                    }
                }
                @Override public void onError(CameraDevice device, int error) {
                    device.close();
                    if (generation == cameraGeneration) {
                        opening = false; camera = null;
                        Log.e(LOG_TAG, "Camera " + cameraId + " error " + error);
                        if (recording || recordingStarting) runOnUiThread(() -> abortRecording());
                        show("Camera error: " + error);
                    }
                }
            }, cameraHandler);
        } catch (Exception e) { opening = false; Log.e(LOG_TAG, "Could not open camera", e); show("Could not open camera: " + e.getMessage()); }
    }

    private void createImageReader(Size jpeg) {
        if (imageReader != null) imageReader.close();
        imageReader = ImageReader.newInstance(jpeg.getWidth(), jpeg.getHeight(), android.graphics.ImageFormat.JPEG, 2);
        imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireNextImage();
                if (image == null) return;
                byte[] bytes;
                try {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    bytes = new byte[buffer.remaining()]; buffer.get(bytes);
                } finally { image.close(); }
                Settings settings = pendingCapture;
                try { saveImage(bytes, settings == null ? new Settings(0, 0, 0, 0, 0, 0f) : settings); }
                catch (Exception e) { show("Could not save photo: " + e.getMessage()); }
                finally { runOnUiThread(() -> { capturing = false; captureButton.setEnabled(true); }); }
        }, cameraHandler);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) openCamera();
            else show("Allow camera access in Android settings");
        } else if (requestCode == AUDIO_PERMISSION && resumed && videoMode) {
            startRecording();
        }
    }

    private void startPreview() {
        if (camera == null || imageReader == null || previewSize == null || !preview.isAvailable()) return;
        try {
            final int generation = ++sessionGeneration;
            if (session != null) { session.close(); session = null; }
            SurfaceTexture texture = preview.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);
            previewSurface = surface;
            previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequest.addTarget(surface);
            camera.createCaptureSession(Arrays.asList(surface, imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession newSession) {
                    if (camera == null || generation != sessionGeneration) { newSession.close(); return; }
                    session = newSession;
                    updatePreviewRequest();
                    runOnUiThread(() -> {
                        if (generation != sessionGeneration) return;
                        previewFrameCount = 0;
                        preview.postDelayed(() -> checkPreviewFrames(generation), 2500);
                    });
                }
                @Override public void onConfigureFailed(CameraCaptureSession failedSession) {
                    failedSession.close();
                    if (generation == sessionGeneration) retryConservativePreview("Camera rejected preview stream sizes");
                }
            }, cameraHandler);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Could not configure preview", e);
            retryConservativePreview("Could not configure preview: " + e.getMessage());
        }
    }

    private void retryConservativePreview(String reason) {
        Log.w(LOG_TAG, reason);
        if (!resumed || camera == null) return;
        if (conservativeCameraSizes || availableJpegSizes == null || availablePreviewSizes == null) {
            show("Camera preview is still unavailable on this device");
            return;
        }
        conservativeCameraSizes = true;
        try {
            Size jpeg = Arrays.stream(availableJpegSizes)
                    .filter(s -> (long) s.getWidth() * s.getHeight() <= 2_100_000L)
                    .min(Comparator.comparingDouble((Size s) -> Math.abs((double) s.getWidth() / s.getHeight() - 4d / 3d))
                            .thenComparingLong(s -> -((long) s.getWidth() * s.getHeight())))
                    .orElse(Arrays.stream(availableJpegSizes)
                            .min(Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight())).orElse(availableJpegSizes[0]));
            Size smallPreview = Arrays.stream(availablePreviewSizes)
                    .filter(s -> s.getWidth() <= 1280 && s.getHeight() <= 960)
                    .min(Comparator.comparingDouble((Size s) -> Math.abs((double) s.getWidth() / s.getHeight()
                            - (double) jpeg.getWidth() / jpeg.getHeight()))
                            .thenComparingLong(s -> Math.abs((long) s.getWidth() * s.getHeight() - 640L * 480L)))
                    .orElse(Arrays.stream(availablePreviewSizes)
                            .min(Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight())).orElse(availablePreviewSizes[0]));
            Log.w(LOG_TAG, "Retrying camera " + cameraId + " with JPEG " + jpeg + " and preview " + smallPreview);
            if (session != null) { session.close(); session = null; }
            previewRequest = null;
            createImageReader(jpeg);
            photoPreviewSize = smallPreview;
            videoPreviewSize = smallPreview;
            previewSize = smallPreview;
            runOnUiThread(this::configureTransform);
            startPreview();
        } catch (Exception e) {
            Log.e(LOG_TAG, "Conservative camera configuration failed", e);
            show("This camera could not start: " + e.getMessage());
        }
    }

    private void checkPreviewFrames(int generation) {
        if (!resumed || camera == null || generation != sessionGeneration || recording || recordingStarting) return;
        boolean black = previewFrameCount == 0;
        if (!black && preview.isAvailable()) {
            Bitmap frame = preview.getBitmap(24, 24);
            if (frame != null) {
                black = true;
                for (int y = 0; y < frame.getHeight() && black; y += 3)
                    for (int x = 0; x < frame.getWidth(); x += 3) {
                        int pixel = frame.getPixel(x, y);
                        if (Color.red(pixel) > 5 || Color.green(pixel) > 5 || Color.blue(pixel) > 5) {
                            black = false; break;
                        }
                    }
                frame.recycle();
            }
        }
        if (black) retryConservativePreview("Preview stayed black after " + previewFrameCount + " frames");
    }

    private boolean supportsWhiteBalance(int mode) {
        if (characteristics == null) return mode == CaptureRequest.CONTROL_AWB_MODE_AUTO;
        int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        if (modes == null) return mode == CaptureRequest.CONTROL_AWB_MODE_AUTO;
        for (int available : modes) if (available == mode) return true;
        return false;
    }

    private boolean supportsRequestKey(CaptureRequest.Key<?> key) {
        return characteristics != null && characteristics.getAvailableCaptureRequestKeys().contains(key);
    }

    private void applyCameraSettings(CaptureRequest.Builder builder) {
        int[] focusModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        boolean continuous = false;
        boolean autoFocus = false;
        boolean focusOff = false;
        if (focusModes != null) for (int mode : focusModes) if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) continuous = true;
        if (focusModes != null) for (int mode : focusModes) {
            if (mode == CaptureRequest.CONTROL_AF_MODE_AUTO) autoFocus = true;
            if (mode == CaptureRequest.CONTROL_AF_MODE_OFF) focusOff = true;
        }
        if (manualFocusSupported && manualFocusProgress != 200) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE,
                    maxFocusDistance * manualFocusProgress / 400f);
        } else if (supportsRequestKey(CaptureRequest.CONTROL_AF_MODE) && (continuous || autoFocus || focusOff)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    continuous ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            : autoFocus ? CaptureRequest.CONTROL_AF_MODE_AUTO : CaptureRequest.CONTROL_AF_MODE_OFF);
        }
        int[] aeModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        if (supportsRequestKey(CaptureRequest.CONTROL_AE_MODE) && aeModes != null)
            for (int mode : aeModes) if (mode == CaptureRequest.CONTROL_AE_MODE_ON) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                break;
            }
        if (supportsRequestKey(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION))
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureSteps);
        if (supportsRequestKey(CaptureRequest.CONTROL_AWB_MODE))
            builder.set(CaptureRequest.CONTROL_AWB_MODE,
                    supportsWhiteBalance(WB_MODES[wbIndex]) ? WB_MODES[wbIndex] : CaptureRequest.CONTROL_AWB_MODE_AUTO);
        if (flashAvailable && supportsRequestKey(CaptureRequest.FLASH_MODE))
            builder.set(CaptureRequest.FLASH_MODE, flashOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        Rect sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (sensor != null && zoomFactor > 1f && supportsRequestKey(CaptureRequest.SCALER_CROP_REGION)) {
            int width = Math.round(sensor.width() / zoomFactor);
            int height = Math.round(sensor.height() / zoomFactor);
            int left = sensor.left + (sensor.width() - width) / 2;
            int top = sensor.top + (sensor.height() - height) / 2;
            builder.set(CaptureRequest.SCALER_CROP_REGION, new Rect(left, top, left + width, top + height));
        }
    }

    private void updatePreviewRequest() {
        if (previewRequest == null || session == null || cameraHandler == null) return;
        try {
            if (!basicCameraRequest) applyCameraSettings(previewRequest);
            session.setRepeatingRequest(previewRequest.build(), null, cameraHandler);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Preview request rejected", e);
            if (!basicCameraRequest) {
                try {
                    CaptureRequest.Builder safe = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                    safe.addTarget(previewSurface);
                    session.setRepeatingRequest(safe.build(), null, cameraHandler);
                    previewRequest = safe;
                    basicCameraRequest = true;
                    show("Some camera controls are unavailable on this device");
                    return;
                } catch (Exception fallbackError) { Log.e(LOG_TAG, "Basic preview request rejected", fallbackError); }
            }
            retryConservativePreview("Preview request failed: " + e.getMessage());
        }
    }

    private void startRecordingWithPermission() {
        if (recording || recordingStarting) return;
        recordingStarting = true;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
        } else startRecording();
    }

    private void startRecording() {
        if (!resumed || camera == null || session == null || videoSize == null || !preview.isAvailable()) {
            recordingStarting = false;
            show("Video is unavailable on this camera");
            return;
        }
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME,
                    "VID_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/my Camera");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            videoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (videoUri == null) throw new IOException("Could not create video file");
            videoFd = getContentResolver().openFileDescriptor(videoUri, "w");
            if (videoFd == null) throw new IOException("Could not open video file");

            recorder = new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            boolean withAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            if (withAudio) recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(videoFd.getFileDescriptor());
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(8_000_000);
            if (withAudio) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioEncodingBitRate(128_000);
                recorder.setAudioSamplingRate(44_100);
            }
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            recorder.setOrientationHint(orientation == null ? 0 : orientation);
            recorder.prepare();

            final int generation = ++sessionGeneration;
            session.close(); session = null; previewRequest = null;
            SurfaceTexture texture = preview.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(texture);
            Surface recordingSurface = recorder.getSurface();
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            request.addTarget(previewSurface);
            request.addTarget(recordingSurface);
            camera.createCaptureSession(Arrays.asList(previewSurface, recordingSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession newSession) {
                            if (!resumed || camera == null || generation != sessionGeneration || recorder == null) {
                                newSession.close(); return;
                            }
                            try {
                                session = newSession;
                                previewRequest = request;
                                if (!basicCameraRequest) applyCameraSettings(request);
                                newSession.setRepeatingRequest(request.build(), null, cameraHandler);
                                recorder.start();
                                recording = true;
                                recordingStarting = false;
                                runOnUiThread(() -> {
                                    if (!recording) return;
                                    captureButton.setEnabled(true);
                                    captureButton.setContentDescription("Stop video recording");
                                    captureButton.invalidate();
                                    updateTopToggleStates();
                                });
                            } catch (Exception e) {
                                runOnUiThread(() -> {
                                    if (generation != sessionGeneration) return;
                                    abortRecording(); show("Could not start video: " + e.getMessage());
                                });
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession failedSession) {
                            failedSession.close();
                            runOnUiThread(() -> {
                                if (generation != sessionGeneration) return;
                                abortRecording(); show("Could not start video session");
                            });
                        }
                    }, cameraHandler);
            captureButton.setEnabled(false);
        } catch (Exception e) {
            abortRecording();
            show("Could not start video: " + e.getMessage());
        }
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        boolean saved = false;
        try { recorder.stop(); saved = true; }
        catch (RuntimeException e) { show("Video recording was too short or interrupted"); }
        try { recorder.reset(); recorder.release(); } catch (Exception ignored) { }
        recorder = null;
        finishVideoFile(saved);
        recordingStarting = false;
        sessionGeneration++;
        if (session != null) { session.close(); session = null; }
        previewRequest = null;
        captureButton.setEnabled(true);
        captureButton.setContentDescription(videoMode ? "Start video recording" : "Capture photo");
        captureButton.invalidate();
        updateTopToggleStates();
        if (resumed && camera != null) startPreview();
    }

    private void abortRecording() {
        recording = false;
        recordingStarting = false;
        sessionGeneration++;
        if (session != null) { session.close(); session = null; }
        previewRequest = null;
        if (recorder != null) {
            try { recorder.reset(); recorder.release(); } catch (Exception ignored) { }
            recorder = null;
        }
        finishVideoFile(false);
        captureButton.setEnabled(true);
        captureButton.invalidate();
        updateTopToggleStates();
        if (resumed && camera != null) startPreview();
    }

    private void finishVideoFile(boolean saved) {
        if (videoFd != null) {
            try { videoFd.close(); } catch (IOException ignored) { }
            videoFd = null;
        }
        if (videoUri != null) {
            try {
                if (saved) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(videoUri, values, null, null);
                } else getContentResolver().delete(videoUri, null, null);
            } catch (Exception e) { show("Could not finish video file: " + e.getMessage()); }
            videoUri = null;
        }
    }

    private void capture() {
        if (camera == null || session == null || imageReader == null || capturing) return;
        capturing = true; captureButton.setEnabled(false);
        pendingCapture = new Settings(brightness, contrast, saturation, warmth, tint, digitalExposureEv);
        try {
            CaptureRequest.Builder still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            still.addTarget(imageReader.getSurface());
            if (!basicCameraRequest) applyCameraSettings(still);
            Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            still.set(CaptureRequest.JPEG_ORIENTATION, sensor == null ? 0 : sensor);
            session.capture(still.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request,
                        android.hardware.camera2.TotalCaptureResult result) {
                    runOnUiThread(() -> playShutterSound());
                }
                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request, android.hardware.camera2.CaptureFailure failure) {
                    runOnUiThread(() -> { capturing = false; captureButton.setEnabled(true); show("Capture failed"); });
                }
            }, cameraHandler);
        } catch (Exception e) { capturing = false; captureButton.setEnabled(true); show("Capture error: " + e.getMessage()); }
    }

    private void playShutterSound() {
        MediaPlayer player = MediaPlayer.create(this, R.raw.shot);
        if (player == null) return;
        player.setLooping(false);
        player.setOnCompletionListener(MediaPlayer::release);
        player.setOnErrorListener((failed, what, extra) -> { failed.release(); return true; });
        player.start();
    }

    private void closeCamera() {
        cameraGeneration++;
        sessionGeneration++;
        opening = false;
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        previewRequest = null; capturing = false;
        if (captureButton != null) captureButton.setEnabled(true);
    }

    private void configureTransform() {
        if (previewSize == null || !preview.isAvailable()) return;
        int w = preview.getWidth(), h = preview.getHeight();
        if (w == 0 || h == 0) return;
        RectF viewRect = new RectF(0, 0, w, h);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        bufferRect.offset(viewRect.centerX() - bufferRect.centerX(), viewRect.centerY() - bufferRect.centerY());
        Matrix matrix = new Matrix();
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
        float scale = Math.max((float) w / previewSize.getHeight(), (float) h / previewSize.getWidth());
        matrix.postScale(scale, scale, viewRect.centerX(), viewRect.centerY());
        matrix.postRotate(previewRotationDegrees, viewRect.centerX(), viewRect.centerY());
        preview.setTransform(matrix);
    }

    private static ColorMatrix filter(Settings s) {
        float sat = 1f + s.saturation / 50f;
        float con = 1f + s.contrast / 100f;
        float gain = (float) Math.pow(2.0, s.digitalExposureEv);
        float light = s.brightness * 1.8f;
        float warm = s.warmth * .7f;
        float tintValue = s.tint * .55f;
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(sat);
        ColorMatrix tone = new ColorMatrix(new float[]{
                con * gain, 0, 0, 0, (128 * (1 - con) + light + warm + tintValue) * gain,
                0, con * gain, 0, 0, (128 * (1 - con) + light - tintValue) * gain,
                0, 0, con * gain, 0, (128 * (1 - con) + light - warm + tintValue) * gain,
                0, 0, 0, 1, 0
        });
        matrix.postConcat(tone);
        return matrix;
    }

    private void updateFilter() {
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(filter(new Settings(brightness, contrast, saturation, warmth, tint, digitalExposureEv))));
        preview.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
        preview.setLayerPaint(paint);
    }

    private void saveImage(byte[] jpeg, Settings settings) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 3000) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        if (source == null) throw new IOException("Invalid JPEG");
        ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpeg));
        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        Matrix transform = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: transform.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: transform.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: transform.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: transform.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: transform.postScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: transform.postRotate(90); transform.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: transform.postRotate(270); transform.postScale(-1, 1); break;
        }
        Bitmap upright = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), transform, true);
        if (upright != source) source.recycle();
        Bitmap output = Bitmap.createBitmap(upright.getWidth(), upright.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(filter(settings)));
        canvas.drawBitmap(upright, 0, 0, paint);
        upright.recycle();
        ContentValues values = new ContentValues();
        String name = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".jpg";
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/my Camera");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) { output.recycle(); throw new IOException("Gallery unavailable"); }
        try {
            try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
                if (stream == null || !output.compress(Bitmap.CompressFormat.JPEG, 92, stream)) throw new IOException("Could not write JPEG");
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
        } catch (IOException e) { getContentResolver().delete(uri, null, null); throw e; }
        finally { output.recycle(); }
    }

    private void show(String message) { runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show()); }
}
