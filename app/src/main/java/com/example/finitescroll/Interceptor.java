package com.example.finitescroll;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;

public class Interceptor extends android.net.VpnService implements Runnable {

    private transient Context context;
    private DatagramChannel tunnel;
    ParcelFileDescriptor localTunnel;

    public Interceptor(Context context) {
        this.context = context;
    }

    // Need empty constructor for VPN services
    public Interceptor() {}

    @Override
    public void run() {

        final SocketAddress serverAddress = new InetSocketAddress(MainActivity.LOCAL_ADDRESS, MainActivity.LOCAL_VPN_PORT);

        try {
            Intent intent = VpnService.prepare(this.context);

            // Permission for VPN hasn't been granted yet
            while (intent != null) {
                synchronized (this) {

                    // Start permission request activity
                    ((Activity) context).startActivityForResult(intent, MainActivity.VPN_REQUEST_RESULT);

                    // Wait until responded
                    this.wait();

                    intent = VpnService.prepare(context);

                }
            }

            tunnel = DatagramChannel.open();

            this.protect(tunnel.socket());

            tunnel.connect(serverAddress);
            Builder builder = new Builder();

            localTunnel = builder
                    .addAddress(MainActivity.LOCAL_ADDRESS, 24)
                    .addRoute("0.0.0.0", 0)
                    .establish();

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }

    }

    /*
     * Stops the VPN and all underlying services
     * returns true if successful, false otherwise
     * Synchronized, in order to make callee wait
     */
    public synchronized boolean stopServices() {
        try {
            tunnel.close();
            localTunnel.close();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

}
