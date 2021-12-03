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

        // Start up all connection
        connections = new Connections(this);
        connectionsThread = new Thread(connections);
        connectionsThread.start();
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