package com.example.finitescroll;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;

public class Interceptor extends android.net.VpnService implements Runnable, Serializable {

    private transient Context context;
    private boolean ready;

    public Interceptor(Context context) {
        this.context = context;
        this.ready = false;
    }

    // Need empty constructor for VPN services
    public Interceptor() {}

    @Override
    public void run() {

        final SocketAddress serverAddress = new InetSocketAddress(Connections.LOCAL_ADDRESS, Connections.LOCAL_VPN_PORT);

        try {
            Intent intent = VpnService.prepare(this.context);

            // Permission for VPN hasn't been granted yet
            while (intent != null) {
                synchronized (this) {

                    // Start permission request activity
                    ((Activity) this.context).startActivityForResult(intent, MainActivity.VPN_REQUEST_RESULT);

                    // Wait until accepted
                    this.wait();

                    intent = VpnService.prepare(this.context);

                }
            }

            DatagramChannel tunnel = DatagramChannel.open();

            this.protect(tunnel.socket());

            tunnel.connect(serverAddress);
            Builder builder = new Builder();

            ParcelFileDescriptor localTunnel = builder
                    .addAddress(Connections.LOCAL_ADDRESS, 24)
                    .addRoute("0.0.0.0", 0)
                    .establish();

            this.ready = true;

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }

    }

    public boolean isReady() {
        return this.ready;
    }
}
