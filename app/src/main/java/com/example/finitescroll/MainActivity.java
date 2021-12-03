package com.example.finitescroll;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import android.content.Intent;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.Switch;

public class MainActivity extends AppCompatActivity {

    public static final int VPN_REQUEST_RESULT = 69;

    private Connections connections;
    private Thread connectionsThread;

    boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SwitchCompat serviceStatusSwitch = findViewById(R.id.serviceStatusSwitch);

        running = false;

        serviceStatusSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                // Let's get this bad boy rolling
                if (isChecked)
                    startService();
                else
                    stopService();
            }
        });

    }

    private void startService() {
        // Start up all connections
        connections = new Connections(this);
        connectionsThread = new Thread(connections);
        connectionsThread.start();

        running = true;
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

                Interceptor interceptor = connections.getInterceptor();

                synchronized (interceptor) {
                    interceptor.notify();
                }

                break;
        }
    }

}