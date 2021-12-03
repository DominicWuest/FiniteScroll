package com.example.finitescroll;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Start up all connection
        Connections connections = new Connections(this);
        Thread connectionsThread = new Thread(connections);
        connectionsThread.start();
    }
}