package com.example.LiveCamera;

import java.nio.ByteBuffer;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

public class AacEncoder {
	private static final String mTag = "AacEncoder";
	private MediaCodec mCodec = null;
	MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

	@SuppressLint("NewApi")
	public AacEncoder(int samplerate, int channels, int bitrate) {
		mCodec = MediaCodec.createEncoderByType("audio/mp4a-latm");
		MediaFormat mediaFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", samplerate, channels);
		mediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
		mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
		mCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		mCodec.start();
		Log.d(mTag, "start");
	}

	@SuppressLint("NewApi")
	public void close() {
		try {
			mCodec.stop();
			mCodec.release();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@SuppressLint("NewApi")
	public int encode(byte[] input, byte[][] outarray, int[] outlen, long outpts[]) {
		int num = 0;
		try {
			ByteBuffer[] inputBuffers = mCodec.getInputBuffers();
			ByteBuffer[] outputBuffers = mCodec.getOutputBuffers();
			int inputBufferIndex = mCodec.dequeueInputBuffer(-1);
			if (inputBufferIndex >= 0) {
				ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
				inputBuffer.clear();
				inputBuffer.put(input);
				mCodec.queueInputBuffer(inputBufferIndex, 0, input.length, System.nanoTime() / 1000, 0);
			}

			int outputBufferIndex = mCodec.dequeueOutputBuffer(mBufferInfo, 0);
			while (outputBufferIndex >= 0 && num < outarray.length) {
				// adts header
				int profile = 2; // AAC LC
				// 39=MediaCodecInfo.CodecProfileLevel.AACObjectELD;
				int freqIdx = 7; // 22.05KHz
				int chanCfg = 2; // CPE
				int framelen = mBufferInfo.size + 7;
				// fill in ADTS data
				outarray[num][0] = (byte) 0xFF;
				outarray[num][1] = (byte) 0xF1;
				outarray[num][2] = (byte) (((profile - 1) << 6) + (freqIdx << 2) + (chanCfg >> 2));
				outarray[num][3] = (byte) (((chanCfg & 3) << 6) + (framelen >> 11));
				outarray[num][4] = (byte) ((framelen & 0x7FF) >> 3);
				outarray[num][5] = (byte) (((framelen & 7) << 5) + 0x1F);
				outarray[num][6] = (byte) 0xFC;

				ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
				outputBuffer.get(outarray[num], 7, mBufferInfo.size);
				outlen[num] = 7 + mBufferInfo.size;
				outpts[num] = mBufferInfo.presentationTimeUs;

				// Log.i(mTag, "aac len = " + outlen[num] + ", pts = " + (outpts[num] / 1000000) + "." + (outpts[num] % 1000000));
				num++;

				mCodec.releaseOutputBuffer(outputBufferIndex, false);
				outputBufferIndex = mCodec.dequeueOutputBuffer(mBufferInfo, 0);
			}

		} catch (Throwable t) {
			t.printStackTrace();
		}

		return num;
	}
}