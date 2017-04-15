package com.example.LiveCamera;

import java.nio.ByteBuffer;
import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;


public class AvcEncoder {
	private static final String mTag = "AvcEncoder";
	private MediaCodec mCodec = null;
	private Surface mInputSurface = null;
	private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
	private int mWidth;
	private int mHeight;
	private byte[] mSpsPps = null;
	private byte[] mYuv420 = null;
	private byte[] mOutData = null;

	@SuppressLint("NewApi")
	public AvcEncoder(int width, int height, int framerate, int bitrate, boolean isCreateSurfaceForInput) {
		mWidth = width;
		mHeight = height;
		mYuv420 = new byte[width * height * 3 / 2];
		mOutData = new byte[width * height * 3 / 2];

		mCodec = MediaCodec.createEncoderByType("video/avc");
//		MediaCodecInfo codecInfo = mCodec.getCodecInfo();
		
		MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", width, height);
		mediaFormat.setInteger("profile", MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
		mediaFormat.setInteger("level", MediaCodecInfo.CodecProfileLevel.AVCLevel4);		
		mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, isCreateSurfaceForInput ? 
				MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface : 
				MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
		mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
		mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, framerate);
		mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 关键帧间隔时间   单位s

		mCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		
		if (isCreateSurfaceForInput) {
			mInputSurface = mCodec.createInputSurface();
			if (mInputSurface == null) {
				mCodec.release();
				Log.e(mTag, "MediaCodec.createInputSurface() failed");
				throw new RuntimeException("MediaCodec Create input surface failed");
			}
		}
		
		mCodec.start();
	}

	public Surface getInputSurface() {
		return mInputSurface;
	}

	@SuppressLint("NewApi")
	public int encode(byte[] input, byte[][] outarray, int[] outlen, long outpts[]) {
		ByteBuffer[] inputBuffers = mCodec.getInputBuffers();
		ByteBuffer[] outputBuffers = mCodec.getOutputBuffers();
		int num = 0;

		if (mInputSurface == null) {
			int inputBufferIndex = mCodec.dequeueInputBuffer(-1);
			swapYV12toYUV420sp(input, mYuv420, mWidth, mHeight);
			if (inputBufferIndex >= 0) {
				ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
				inputBuffer.clear();
				inputBuffer.put(mYuv420);
				mCodec.queueInputBuffer(inputBufferIndex, 0, mYuv420.length, System.nanoTime() / 1000, 0);
			}
		}

		int outputBufferIndex = mCodec.dequeueOutputBuffer(mBufferInfo, 0);
		while (outputBufferIndex >= 0 && num < outarray.length) {
			ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
			outputBuffer.get(mOutData, 0, mBufferInfo.size);

			if (mSpsPps == null) {
				ByteBuffer spsPpsBuffer = ByteBuffer.wrap(mOutData);
				if (spsPpsBuffer.getInt() == 0x00000001) {
					mSpsPps = new byte[mBufferInfo.size];
					System.arraycopy(mOutData, 0, mSpsPps, 0, mBufferInfo.size);
				}
			} else {
				if (mOutData[4] == 0x65) // key frame 编码器生成关键帧时只有 00 00 00 01 65  没有pps sps， 要加上
				{
					System.arraycopy(mSpsPps, 0, outarray[num], 0, mSpsPps.length);
					System.arraycopy(mOutData, 0, outarray[num], mSpsPps.length, mBufferInfo.size);
					outlen[num] = mSpsPps.length + mBufferInfo.size;
					outpts[num] = mBufferInfo.presentationTimeUs;
				} else {
					System.arraycopy(mOutData, 0, outarray[num], 0, mBufferInfo.size);
					outlen[num] = mBufferInfo.size;
					outpts[num] = mBufferInfo.presentationTimeUs;
				}
				// Log.i(mTag, "avc len = " + outlen[num] + ", pts = " + (outpts[num] / 1000000) + "." + (outpts[num] % 1000000));
				num++;
			}

			mCodec.releaseOutputBuffer(outputBufferIndex, false);
			outputBufferIndex = mCodec.dequeueOutputBuffer(mBufferInfo, 0);
		}

		return num;
	}

	@SuppressLint("NewApi")
	public void close() {
		try {
			mCodec.stop();
			if (mInputSurface != null) {
				mInputSurface.release();
				mInputSurface = null;
			}
			mCodec.release();
			mCodec = null;
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	// YV12 转 yuv420sp
	private void swapYV12toYUV420sp(byte[] yv12bytes, byte[] yuv420sp, int width, int height) {
		int i;
		int y_size = width * height;
		int u_size = y_size >> 2;
		System.arraycopy(yv12bytes, 0, yuv420sp, 0, y_size);
		for (i = 0; i < u_size; i++) {
			yuv420sp[y_size + (i << 1)] = yv12bytes[y_size + u_size + i]; // u
			yuv420sp[y_size + (i << 1) + 1] = yv12bytes[y_size + i]; // v
		}
		// System.arraycopy(yv12bytes, width*height+width*height/4, i420bytes,
		// width*height,width*height/4);
		// System.arraycopy(yv12bytes, width*height, i420bytes,
		// width*height+width*height/4,width*height/4);
	}
}