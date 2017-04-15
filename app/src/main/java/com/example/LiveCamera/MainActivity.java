package com.example.LiveCamera;

import android.os.Bundle;
import android.app.Activity;
import android.view.Menu;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.media.AudioRecord;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.annotation.SuppressLint;
import android.content.res.Configuration;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.MotionEvent;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.SeekBar;

import com.example.LiveCamera.AacEncoder;
import com.example.LiveCamera.AvcEncoder;
import com.example.LiveCamera.rtsp_demo_jni;
import com.example.LiveCamera.R;

//TODO 3A(AF/AE/AWB)
//FIXME redmi3 jni Release* error
//FIXME samsung note not support camera parameters

public class MainActivity extends Activity {
	private final static String mTag = "MainActivity";

	private AvcEncoder mAvcEncoder;
	private AacEncoder mAacEncoder;
	private rtsp_demo_jni mRtspHandle = new rtsp_demo_jni();
	
	private Object mLock = null;

	private Camera mCamera;
	private int mWidth = 1280;
	private int mHeight = 720;
	private int mFramerate = 30;
	private int mBitrate = (int) (1.5f * 1024 * 1024);
	private SurfaceTexture mSurfaceTexture;
	private SurfaceView mSurfaceView;
	private GlVideoRender mVideoRender = null;

	private byte[][] mH264Array = new byte[10][];
	private int[] mH264Len = new int[10];
	private long[] mH264Pts = new long[10];

	private AudioRecord mAudioRecord;
	private Thread mAudioReadThd;
	private int mSamplerate = 22050;
	private int mChannels = 2;
	private int mAudioBitrate = 32000;
	private int mAudioBufSiz = 0;
	private byte[] mPcmData;
	private byte[][] mAacArray = new byte[10][];
	private int[] mAacLen = new int[10];
	private long[] mAacPts = new long[10];
	private boolean mAudioStarted = false;

	private Movie mMovie;
	private Bitmap mBmp;

	private Camera openCamera(boolean front_camera, int width, int height, int framerate, SurfaceTexture st) {
		Camera camera = null;

		Camera.CameraInfo info = new Camera.CameraInfo();
		int numCameras = Camera.getNumberOfCameras();
		for (int i = 0; i < numCameras; i++) {
			Camera.getCameraInfo(i, info);
			if (front_camera && info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
				camera = Camera.open(i);
				break;
			}
			if (!front_camera && info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
				camera = Camera.open(i);
				break;
			}
		}
		if (camera == null) {
			Log.d(mTag, "No front-facing camera found; opening default");
			camera = Camera.open(); // opens first back-facing camera
			if (camera == null) {
				throw new RuntimeException("Unable to open camera");
			}
		}

		Camera.Parameters parameters = camera.getParameters();
		// parameters.setPreviewFormat(ImageFormat.YV12);
		CameraUtils.choosePreviewSize(parameters, width, height);
		CameraUtils.chooseFixedPreviewFps(parameters, framerate*1000);
		if (parameters.getSupportedAntibanding().contains(Parameters.ANTIBANDING_50HZ)) {
			parameters.setAntibanding(Parameters.ANTIBANDING_50HZ);// 抗闪	
		}
		if (parameters.getSupportedFocusModes().contains(Parameters.FOCUS_MODE_AUTO)) {
        	parameters.setFocusMode(Parameters.FOCUS_MODE_AUTO);
        }
		// parameters.setZoom(0);//最大视野

		camera.setParameters(parameters);

		try {
			camera.setPreviewTexture(st);
		} catch (IOException e) {
			e.printStackTrace();
			camera.release();
			return null;
		}

		camera.startPreview();
		return camera;
	}

	private void closeCamera(Camera camera) {
		camera.stopPreview();
		camera.release();
	}
	
	private void startEncode() {
		synchronized (mLock) {
			mRtspHandle.start(8554, "/livecamera", System.nanoTime() / 1000);
			
			mAvcEncoder = new AvcEncoder(mWidth, mHeight, mFramerate, mBitrate, true);
			mVideoRender.setEncSurface(mAvcEncoder.getInputSurface());
			
			mAacEncoder = new AacEncoder(mSamplerate, mChannels, mAudioBitrate);
		}
	}
	
	private void stopEncode() {
		synchronized (mLock) {
			if (mAacEncoder != null) {
				mAacEncoder.close();
				mAacEncoder = null;
			}
	
			if (mAvcEncoder != null) {
				mVideoRender.setEncSurface(null);
				mAvcEncoder.close();
				mAvcEncoder = null;
			}
	
			mRtspHandle.stop();
		}
	}

	@SuppressLint("NewApi")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		Log.d(mTag, "onCreate ...");
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		mLock = new Object();
			
		InputStream is = getResources().openRawResource(R.drawable.gif1);
		mMovie = Movie.decodeStream(is);
		mBmp = Bitmap.createBitmap(mMovie.width(), mMovie.height(), Config.ARGB_8888);
		
		mVideoRender = new GlVideoRender(mWidth, mHeight);
		mVideoRender.prepare();
		mSurfaceTexture = mVideoRender.getInputSurfaceTexture();
		mSurfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
			@Override
			public void onFrameAvailable(SurfaceTexture surfaceTexture) {
				// Log.d(mTag, "onFrameAvailable");
				mBmp.eraseColor(0);
				mMovie.setTime((int) (System.currentTimeMillis() % mMovie.duration()));
				mMovie.draw(new Canvas(mBmp), 0, 0);
				if (mVideoRender != null) {
					mVideoRender.setOsdBmp(mBmp);
					mVideoRender.drawFrame();
	
					synchronized (mLock) {
						if (mAvcEncoder != null) {
							int num = mAvcEncoder.encode(null, mH264Array, mH264Len, mH264Pts);
							for (int i = 0; i < num; i++) {
								mRtspHandle.h264_send(mH264Array[i], mH264Len[i], mH264Pts[i]);
								// Log.i(mTag, "h264 len = " + mH264Len[i] + ", pts = " + mH264Pts[i]);
							}
						}
					}
				}
			}
		});
		
		mCamera = openCamera(false, mWidth, mHeight, mFramerate, mSurfaceTexture);
		mVideoRender.setMirror(false);

		mAudioBufSiz = AudioRecord.getMinBufferSize(mSamplerate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
		if (1024 * 2 * 2 > mAudioBufSiz)
			mAudioBufSiz = 1024 * 2 * 2;
		mPcmData = new byte[mAudioBufSiz];
		mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, mSamplerate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, mAudioBufSiz);
		mAudioRecord.startRecording();

		mAudioStarted = true;
		mAudioReadThd = new Thread(new Runnable() {
			@Override
			public void run() {
				// TODO Auto-generated method stub
				while (mAudioStarted) {
					mAudioRecord.read(mPcmData, 0, mPcmData.length);
					// Log.v(mTag, "mPcmData.length = " + mPcmData.length);
					
					synchronized (mLock) {
						if (mAacEncoder != null) {
							int num = mAacEncoder.encode(mPcmData, mAacArray, mAacLen, mAacPts);
							for (int i = 0; i < num; i++) {
								mRtspHandle.aac_send(mAacArray[i], mAacLen[i], mAacPts[i]);
								// Log.i(mTag, "aac len = " + mAacLen[i] + ", pts = " + mAacPts[i]);
							}
						}
					}
				}
			}
		});
		mAudioReadThd.start();
		
		for (int i = 0; i < mH264Array.length; i++)
			mH264Array[i] = new byte[mWidth * mHeight * 3 / 2];
		for (int i = 0; i < mAacArray.length; i++)
			mAacArray[i] = new byte[mAudioBufSiz];

		mSurfaceView = (SurfaceView) findViewById(R.id.surfaceview);
		mSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
			@Override
			public void surfaceDestroyed(SurfaceHolder holder) {
				Log.d(mTag, "surfaceDestroyed ...");
				mVideoRender.setViewSurface(null);
			}

			@Override
			public void surfaceCreated(SurfaceHolder holder) {
				Log.d(mTag, "surfaceCreated ...");
				mVideoRender.setViewSurface(holder.getSurface());
			}

			@Override
			public void surfaceChanged(SurfaceHolder holder, int format, int mWidth, int mHeight) {
				// TODO Auto-generated method stub
			}
		});

		CheckBox checkbox_front_camera = (CheckBox) findViewById(R.id.checkbox_front_camera);
		checkbox_front_camera.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				if (mCamera != null && mVideoRender != null) {
					closeCamera(mCamera);
					mCamera = null;
					mCamera = openCamera(isChecked, mWidth, mHeight, mFramerate, mSurfaceTexture);
					mVideoRender.setMirror(isChecked);
				}
			}
		});

		CheckBox checkbox_encode_en = (CheckBox) findViewById(R.id.checkbox_encode_en);
		checkbox_encode_en.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				if (mCamera != null && mVideoRender != null) {
					if (isChecked) {
						startEncode();
					} else {
						stopEncode();
					}
				}
			}
		});

		CheckBox checkbox_filter_en = (CheckBox) findViewById(R.id.checkbox_filter_en);
		checkbox_filter_en.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				if (mVideoRender != null) {
					mVideoRender.setFilterEnable(isChecked);
				}
			}
		});

		SeekBar seekbar_meiyan_level = (SeekBar) findViewById(R.id.seekbar_meiyan_level);
		seekbar_meiyan_level.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}

			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				int level = seekBar.getProgress() * 5 / seekBar.getMax();
				if (mVideoRender != null) {
					mVideoRender.setBeautifyLevel(level);
				}
			}
		});

		SeekBar seekbar_saturation_level = (SeekBar) findViewById(R.id.seekbar_saturation_level);
		seekbar_saturation_level.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}

			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (mVideoRender != null) {
					mVideoRender.setSaturation(seekBar.getProgress());
				}
			}
		});
	}

	@Override
	protected void onDestroy() {
		Log.d(mTag, "onDestroy ...");
		
		stopEncode();

		mAudioStarted = false;
		try {
			mAudioReadThd.join();
			mAudioReadThd = null;
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		mAudioRecord.stop();
		mAudioRecord.release();
		mAudioRecord = null;

		closeCamera(mCamera);
		mCamera = null;

		mSurfaceTexture.setOnFrameAvailableListener(null);
		mSurfaceTexture = null;
		
		mVideoRender.release();
		mVideoRender = null;
		
		super.onDestroy();
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {// 屏幕触摸事件
		if (event.getAction() == MotionEvent.ACTION_DOWN) {// 按下时自动对焦
			if (mCamera != null) {
				mCamera.autoFocus(null);
			}
		}
		return true;
	}
}