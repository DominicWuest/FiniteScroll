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
import java.util.Arrays;
import java.util.HashMap;

public class Interceptor extends android.net.VpnService implements Runnable {

	public static final int MAX_PACKET_SIZE = Short.MAX_VALUE;

	private Context context;
	private DatagramChannel tunnel;
	ParcelFileDescriptor localTunnel;

	private boolean isRunning;

	private HashMap<Integer, ProxySocket> socks;

	Interceptor(Context context) {
		this.context = context;
		this.isRunning = false;

		this.socks = new HashMap<>();
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

				if (packetLen == 0)
					continue;

				packet.limit(packetLen);

				int srcPort = 0; // To identify the proxy socket we want to send it to
				srcPort += packet.get(20) & 0xFF;
				srcPort <<= 8;
				srcPort += packet.get(21) & 0xFF;

				byte[] packetArr = Arrays.copyOf(packet.array(), packetLen);

				if (socks.containsKey(srcPort)) { // Already have an existing socket for this port
					socks.get(srcPort).sharedBuffer.put(packetArr);
				} else {

					if ((packet.get(33) & 0x2) != 0) { // If SYN bit is set -> new connection
						ProxySocket newSock = new ProxySocket(packetArr, out);

						socks.put(srcPort, newSock);

						// Start new thread for this proxy and put the packet into the shared buffer
						new Thread(newSock, String.valueOf(srcPort)).start();
						newSock.sharedBuffer.put(packetArr);
					} else {
						System.out.println("Received packet that cannot be associated to previous connection");
					}

				}

				packet.clear();

			}

		} catch (IOException | InterruptedException | PackageManager.NameNotFoundException e) {
			e.printStackTrace();
		}

	}

	/*
	 * Stops the VPN and all underlying services
	 * returns true if successful, false otherwise
	 * Synchronized, in order to make callee wait
	 */
	public synchronized boolean stopServices() {

		boolean success = true;

		try {
			isRunning = false;

			tunnel.close();
			localTunnel.close();

			for (ProxySocket sock : socks.values()) {
				success = sock.stopServices() && success;
			}

		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}

		return success;
	}

}
