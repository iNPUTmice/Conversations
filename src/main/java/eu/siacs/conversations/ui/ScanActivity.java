/*
 * Copyright 2012-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package eu.siacs.conversations.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.ResultPointCallback;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.EnumMap;
import java.util.Map;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.service.CameraManager;
import eu.siacs.conversations.ui.util.SettingsUtils;
import eu.siacs.conversations.ui.widget.ScannerView;

/**
 * @author Andreas Schildbach
 */
@SuppressWarnings("deprecation")
public final class ScanActivity extends Activity implements SurfaceTextureListener, ActivityCompat.OnRequestPermissionsResultCallback {
	public static final String INTENT_EXTRA_RESULT = "result";

	public static final int REQUEST_SCAN_QR_CODE = 0x0987;
	private static final int REQUEST_CAMERA_PERMISSIONS_TO_SCAN = 0x6789;

	private static final long VIBRATE_DURATION = 50L;
	private static final long AUTO_FOCUS_INTERVAL_MS = 2500L;
	private static final boolean DISABLE_CONTINUOUS_AUTOFOCUS = Build.MODEL.equals("GT-I9100") // Galaxy S2
			|| Build.MODEL.equals("SGH-T989") // Galaxy S2
			|| Build.MODEL.equals("SGH-T989D") // Galaxy S2 X
			|| Build.MODEL.equals("SAMSUNG-SGH-I727") // Galaxy S2 Skyrocket
			|| Build.MODEL.equals("GT-I9300") // Galaxy S3
			|| Build.MODEL.equals("GT-N7000"); // Galaxy Note
	private final CameraManager cameraManager = new CameraManager();
	private ScannerView scannerView;
	private TextureView previewView;
	private volatile boolean surfaceCreated = false;
	private Vibrator vibrator;
	private HandlerThread cameraThread;
	private volatile Handler cameraHandler;
	private final Runnable closeRunnable = new Runnable() {
		@Override
		public void run() {
			cameraHandler.removeCallbacksAndMessages(null);
			cameraManager.close();
		}
	};
	private final Runnable fetchAndDecodeRunnable = new Runnable() {
		private final QRCodeReader reader = new QRCodeReader();
		private final Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);

		@Override
		public void run() {
			cameraManager.requestPreviewFrame((data, camera) -> decode(data));
		}

		private void decode(final byte[] data) {
			final PlanarYUVLuminanceSource source = cameraManager.buildLuminanceSource(data);
			final BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

			try {
				hints.put(DecodeHintType.NEED_RESULT_POINT_CALLBACK, (ResultPointCallback) dot -> runOnUiThread(() -> scannerView.addDot(dot)));
				final Result scanResult = reader.decode(bitmap, hints);

				runOnUiThread(() -> handleResult(scanResult));
			} catch (final ReaderException x) {
				// retry
				cameraHandler.post(fetchAndDecodeRunnable);
			} finally {
				reader.reset();
			}
		}
	};
	private final Runnable openRunnable = new Runnable() {
		@Override
		public void run() {
			try {
				final Camera camera = cameraManager.open(previewView, displayRotation(), !DISABLE_CONTINUOUS_AUTOFOCUS);

				final Rect framingRect = cameraManager.getFrame();
				final RectF framingRectInPreview = new RectF(cameraManager.getFramePreview());
				framingRectInPreview.offsetTo(0, 0);
				final boolean cameraFlip = cameraManager.getFacing() == CameraInfo.CAMERA_FACING_FRONT;
				final int cameraRotation = cameraManager.getOrientation();

				runOnUiThread(() -> scannerView.setFraming(framingRect, framingRectInPreview, displayRotation(), cameraRotation, cameraFlip));

				final String focusMode = camera.getParameters().getFocusMode();
				final boolean nonContinuousAutoFocus = Camera.Parameters.FOCUS_MODE_AUTO.equals(focusMode)
						|| Camera.Parameters.FOCUS_MODE_MACRO.equals(focusMode);

				if (nonContinuousAutoFocus)
					cameraHandler.post(new AutoFocusRunnable(camera));

				cameraHandler.post(fetchAndDecodeRunnable);
			} catch (final Exception x) {
				Log.d(Config.LOGTAG, "problem opening camera", x);
			}
		}

		private int displayRotation() {
			final int rotation = getWindowManager().getDefaultDisplay().getRotation();
			if (rotation == Surface.ROTATION_0)
				return 0;
			else if (rotation == Surface.ROTATION_90)
				return 90;
			else if (rotation == Surface.ROTATION_180)
				return 180;
			else if (rotation == Surface.ROTATION_270)
				return 270;
			else
				throw new IllegalStateException("rotation: " + rotation);
		}
	};

	@Override
	public void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

		setContentView(R.layout.activity_scan);
		scannerView = findViewById(R.id.scan_activity_mask);
		previewView = findViewById(R.id.scan_activity_preview);
		previewView.setSurfaceTextureListener(this);

		cameraThread = new HandlerThread("cameraThread", Process.THREAD_PRIORITY_BACKGROUND);
		cameraThread.start();
		cameraHandler = new Handler(cameraThread.getLooper());
	}

	@Override
	protected void onResume() {
		super.onResume();
		SettingsUtils.applyScreenshotPreventionSetting(this);
		maybeOpenCamera();
	}

	@Override
	protected void onPause() {
		cameraHandler.post(closeRunnable);

		super.onPause();
	}

	@Override
	protected void onDestroy() {
		// cancel background thread
		cameraHandler.removeCallbacksAndMessages(null);
		cameraThread.quit();

		previewView.setSurfaceTextureListener(null);

		super.onDestroy();
	}

	private void maybeOpenCamera() {
		if (surfaceCreated && ContextCompat.checkSelfPermission(this,
				Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
			cameraHandler.post(openRunnable);
	}

	@Override
	public void onSurfaceTextureAvailable(final SurfaceTexture surface, final int width, final int height) {
		surfaceCreated = true;
		maybeOpenCamera();
	}

	@Override
	public boolean onSurfaceTextureDestroyed(final SurfaceTexture surface) {
		surfaceCreated = false;
		return true;
	}

	@Override
	public void onSurfaceTextureSizeChanged(final SurfaceTexture surface, final int width, final int height) {
	}

	@Override
	public void onSurfaceTextureUpdated(final SurfaceTexture surface) {
	}

	@Override
	public void onAttachedToWindow() {
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
	}

	@Override
	public void onBackPressed() {
		scannerView.setVisibility(View.GONE);
		setResult(RESULT_CANCELED);
		postFinish();
	}

	@Override
	public boolean onKeyDown(final int keyCode, final KeyEvent event) {
		switch (keyCode) {
			case KeyEvent.KEYCODE_FOCUS:
			case KeyEvent.KEYCODE_CAMERA:
				// don't launch camera app
				return true;
			case KeyEvent.KEYCODE_VOLUME_DOWN:
			case KeyEvent.KEYCODE_VOLUME_UP:
				cameraHandler.post(() -> cameraManager.setTorch(keyCode == KeyEvent.KEYCODE_VOLUME_UP));
				return true;
		}

		return super.onKeyDown(keyCode, event);
	}

	public void handleResult(final Result scanResult) {
		vibrator.vibrate(VIBRATE_DURATION);

		scannerView.setIsResult(true);

		final Intent result = new Intent();
		result.putExtra(INTENT_EXTRA_RESULT, scanResult.getText());
		setResult(RESULT_OK, result);
		postFinish();
	}

	private void postFinish() {
		new Handler().postDelayed(this::finish, 50);
	}

	public static void scan(Activity activity) {
		if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
			Intent intent = new Intent(activity, ScanActivity.class);
			activity.startActivityForResult(intent, REQUEST_SCAN_QR_CODE);
		} else {
			ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSIONS_TO_SCAN);
		}

	}

	public static void onRequestPermissionResult(Activity activity, int requestCode, int[] grantResults) {
		if (requestCode != REQUEST_CAMERA_PERMISSIONS_TO_SCAN) {
			return;
		}
		if (grantResults.length > 0) {
			if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				scan(activity);
			} else {
				Toast.makeText(activity, R.string.qr_code_scanner_needs_access_to_camera, Toast.LENGTH_SHORT).show();
			}
		}
	}

	private final class AutoFocusRunnable implements Runnable {
		private final Camera camera;
		private final Camera.AutoFocusCallback autoFocusCallback = new Camera.AutoFocusCallback() {
			@Override
			public void onAutoFocus(final boolean success, final Camera camera) {
				// schedule again
				cameraHandler.postDelayed(AutoFocusRunnable.this, AUTO_FOCUS_INTERVAL_MS);
			}
		};

		public AutoFocusRunnable(final Camera camera) {
			this.camera = camera;
		}

		@Override
		public void run() {
			try {
				camera.autoFocus(autoFocusCallback);
			} catch (final Exception x) {
				Log.d(Config.LOGTAG, "problem with auto-focus, will not schedule again", x);
			}
		}
	}
}
