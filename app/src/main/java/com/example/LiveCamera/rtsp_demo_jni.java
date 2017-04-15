package com.example.LiveCamera;

public class rtsp_demo_jni {
	public native int start(int port, String path, long pts);

	public native void stop();

	public native int h264_send(byte[] data, int length, long pts);

	public native int aac_send(byte[] data, int length, long pts);

	static {
		System.loadLibrary("rtsp_demo_jni");
	}
}
