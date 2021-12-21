package com.example.finitescroll;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import android.content.Intent;
import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

	public static final int VPN_REQUEST_RESULT = 42;

	public static final String LOCAL_ADDRESS  = "10.0.2.0";
	public static final int    LOCAL_VPN_PORT = 1337;

	private Interceptor interceptor;
	private Thread interceptorThread;

	boolean running;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		SwitchCompat serviceStatusSwitch = findViewById(R.id.serviceStatusSwitch);

		running = false;

		serviceStatusSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			// Let's get this bad boy rolling
			if (isChecked)
				startService();
			else
				stopService();
		});

	}

	private void startService() {

		// Start the interceptor (Pseudo VPN)
		interceptor = new Interceptor(this);
		interceptorThread = new Thread(interceptor);
		interceptorThread.start();

		running = true;
	}

	private boolean stopService() {

		boolean success = true;

		// Shut down interceptor
		synchronized (interceptor) {
			// Request interceptor to stop it's services
			success = interceptor.stopServices();

			interceptor = null;
			interceptorThread = null;
		}

		running = false;

		return success;
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();

		if (running && !stopService()) {
			System.err.println("Error while stopping services");
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		switch (requestCode) {

			case VPN_REQUEST_RESULT: // Returned from VPN permission request
				synchronized (interceptor) {
					interceptor.notify();
				}
				break;

		}
	}

}