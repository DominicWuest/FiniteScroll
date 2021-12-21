package com.example.finitescroll;

import java.nio.ByteBuffer;
import java.util.concurrent.SynchronousQueue;

public class ProxySocket implements Runnable {

    SynchronousQueue<byte[]> sharedBuffer;

    private int remoteIp, remotePort;
    private int localIp, localPort;

    private boolean isRunning;

    ProxySocket(byte[] firstPacket) {
        this.sharedBuffer = new SynchronousQueue<>();

        extractHeaderInfo(firstPacket);

        this.isRunning = true;
    }

    @Override
    public void run() {

        try {
            byte[] packet;

            while (this.isRunning) {
                packet = sharedBuffer.take();

                if (packet.length != 0) { // As length is zero if proxy is getting shut down

                    System.out.println(byteArrToHexString(packet));

                }
            }

        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    private void extractHeaderInfo(byte[] firstPacket) {

        ByteBuffer wrapper = ByteBuffer.wrap(firstPacket);

        this.localIp = wrapper.getInt(12);
        this.remoteIp = wrapper.getInt(16);

        this.localPort = wrapper.getShort(20) & 0xFFFF; // Masking because java
        this.remotePort = wrapper.getShort(22) & 0xFFFF;

    }

    private String byteArrToHexString(byte[] packet) {
        StringBuilder sb = new StringBuilder();

        for (byte b : packet) {
            sb.append(String.format("%02X", b));
        }

        return sb.toString();
    }

    /*
     * Stops the proxy socket and all underlying services
     * returns true if successful, false otherwise
     * Synchronized, in order to make callee wait
     */
    public synchronized boolean stopServices() {
    	this.isRunning = false;

    	this.sharedBuffer.offer(new byte[] {}); // To unblock in sharedBuffer.take()

        return true;
    }

}
