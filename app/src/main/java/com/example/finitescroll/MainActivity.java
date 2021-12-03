package com.example.finitescroll;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

    public static final int VPN_REQUEST_RESULT = 69;

    private Connections connections;
    private Thread connectionsThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Let's get this bad boy rolling
        startService();
    }

    private void startService() {
        // Start up all connections
        connections = new Connections(this);
        connectionsThread = new Thread(connections);
        connectionsThread.start();
    }

    private boolean stopService() {

        boolean success = true;

        synchronized (connections) {
            try {
                // Request connections thread to stop it's services
                success = connections.stopServices() && success;

                // Terminate connections thread
                connectionsThread.join();

            } catch (InterruptedException e) {
                e.printStackTrace();
                success = false;
            }
        }

        return success;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!stopService()) {
            System.err.println("Error while stopping services");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case VPN_REQUEST_RESULT: // Returned from VPN permission request

                Interceptor interceptor = connections.getInterceptor();

                synchronized (interceptor) {
                    interceptor.notify();
                }

                break;
        }
    }

}