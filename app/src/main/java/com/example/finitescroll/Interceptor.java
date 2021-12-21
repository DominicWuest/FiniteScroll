package com.example.finitescroll;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

public class Interceptor extends android.net.VpnService implements Runnable {

    public static final int MAX_PACKET_SIZE = Short.MAX_VALUE;

    private Context context;
    private DatagramChannel tunnel;
    ParcelFileDescriptor localTunnel;

    private boolean isRunning;

    public Interceptor(Context context) {
        this.context = context;
        this.isRunning = false;
    }

    // Need empty constructor for VPN services
    public Interceptor() {}

    @Override
    public void run() {

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

            final SocketAddress serverAddress = new InetSocketAddress(MainActivity.LOCAL_ADDRESS, MainActivity.LOCAL_VPN_PORT);

            tunnel.connect(serverAddress);
            Builder builder = new Builder();

            localTunnel = builder
                    .addAddress(MainActivity.LOCAL_ADDRESS, 24)
                    .addRoute("0.0.0.0", 0)
                    .addAllowedApplication("com.android.chrome") // For testing purposes only, to minimize packages
                    .establish();

            FileInputStream in = new FileInputStream(localTunnel.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(localTunnel.getFileDescriptor());

            ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_SIZE);

            this.isRunning = true;

            while (this.isRunning) {

                int packetLen = in.read(packet.array());

                packet.limit(packetLen);

                System.err.println(byteBufToHexString(packet));

                packet.clear();

            }

        } catch (IOException | InterruptedException | PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

    }

    private String byteBufToHexString(ByteBuffer packet) {
        StringBuilder sb = new StringBuilder();

        int len = packet.limit();

        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X", packet.get(i)));
        }

        return sb.toString();
    }

    /*
     * Stops the VPN and all underlying services
     * returns true if successful, false otherwise
     * Synchronized, in order to make callee wait
     */
    public synchronized boolean stopServices() {
        try {
            isRunning = false;

            tunnel.close();
            localTunnel.close();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

}
