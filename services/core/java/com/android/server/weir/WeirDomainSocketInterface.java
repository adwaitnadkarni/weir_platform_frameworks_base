package com.android.server.weir;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;
import com.android.server.weir.WeirManagerService;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

public class WeirDomainSocketInterface implements Runnable {

	public static final String LOCALSOCKET_INTERFACE_NAME = "WEIR";
	private static final String TAG = "WEIRDomainSocketInterface";
	private LocalServerSocket socket = null;
	private WeirManagerService mService;
	public WeirDomainSocketInterface(WeirManagerService weir) {
		mService = weir;
	}

	@Override
	public void run() {
		try {
			socket = new LocalServerSocket(LOCALSOCKET_INTERFACE_NAME);
		} catch (IOException e) {
			Log.e(TAG, "Error creating Local Server Socket", e);
			return;
		}

		Log.d(TAG, "Waiting for connections...");

		while (true) {
			try {
				LocalSocket socketIn = socket.accept();
				//Log.d(TAG, "New connection");
				WEIRAsyncQueryTask task = new WEIRAsyncQueryTask(socketIn);
				new Thread(task).start();

			} catch (IOException e) {
				Log.e(TAG, "Error accepting connection", e);
			}
		}
	}

	private class WEIRAsyncQueryTask implements Runnable {

		private static final String TAG = "WEIRAsyncQueryTask";
		private LocalSocket mySocket = null;
		private boolean abort = false;
		private BufferedReader br = null;
		private BufferedWriter bw = null;

		public WEIRAsyncQueryTask(LocalSocket socket) {
			mySocket = socket;
		}

		@Override
		public void run() {
		    try {
			    br = new BufferedReader(new InputStreamReader(
						mySocket.getInputStream()));
			    bw = new BufferedWriter(new OutputStreamWriter(
						mySocket.getOutputStream()));
			    String query = br.readLine();
				
			    //Log.d(TAG, "Query:  {" + query +"}");

			    String result = "";
			    while (!abort) {
				result = mService.query(query);
				synchronized (bw) {
				    bw.write(result + "\n");
				    bw.flush();
				}
				query = br.readLine();
				//Log.d(TAG, "Query:  {" + query +"}");
			    }

			    br.close();
			    bw.close();
			    mySocket.close();

			} catch (IOException e) {
				// Ignore errors for now.
				Log.e(TAG, "IOException during weir socket operation! ignoring", e); 
			}

		}
	}
}
