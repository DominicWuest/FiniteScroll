package com.example.finitescroll;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.SynchronousQueue;

public class ProxySocket implements Runnable {

	SynchronousQueue<byte[]> sharedBuffer;

	private final FileOutputStream out; // This is where the outgoing packets are written to

	private Socket sock; // Remote connection

	private byte[] remoteIp, localIp;
	private int remotePort, localPort;

	private boolean isRunning;

	ProxySocket(byte[] firstPacket, FileOutputStream out) {
		this.sharedBuffer = new SynchronousQueue<>();
		this.out = out;

		extractHeaderInfo(firstPacket);

		initialiseRemoteConnection();

		this.isRunning = true;
	}

	@Override
	public void run() {

		try {
			byte[] packet;

			while (this.isRunning) {
				packet = sharedBuffer.take();

				if (packet.length != 0) { // As length is zero if proxy is getting shut down
					proxyPacket(packet);
				}
			}

		} catch (InterruptedException | IOException e) {
			e.printStackTrace();
		}

	}

	// Extracts all the relevant info about the local client and remote host from the first packet sent
	private void extractHeaderInfo(byte[] firstPacket) {

		ByteBuffer wrapper = ByteBuffer.wrap(firstPacket);

		this.localIp = this.remoteIp = new byte[4];

		wrapper.position(12);
		wrapper.get(localIp);

		wrapper.position(16);
		wrapper.get(remoteIp);

		this.localPort = wrapper.getShort(20) & 0xFFFF; // Masking because java
		this.remotePort = wrapper.getShort(22) & 0xFFFF;

	}

	// Initialises the socket between the local client and the remote host
	private void initialiseRemoteConnection() {
		this.sock = new Socket();

		try {
			this.sock.connect(new InetSocketAddress(InetAddress.getByAddress(remoteIp), remotePort));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// Takes in a packet from the local client, sends it to the remote host and sends back the remote response to the local client
	private void proxyPacket(byte[] packet) throws IOException {
		System.out.println(byteArrToHexString(packet));

		int dgHeaderLength = 4 * (packet[0] & 0xF); // Datagram header length
		int segHeaderLength = 4 * ((packet[dgHeaderLength + 12] >> 4) & 0xF); // Segment header length

		System.out.println(dgHeaderLength + " " + segHeaderLength);

		// No message, might have to react to certain flags
		if (dgHeaderLength + segHeaderLength == packet.length) {

			System.out.println("Received SYN packet");

			byte flags = (byte) (packet[dgHeaderLength + 13] & 0xF); // TCP flags

			if ((flags & 0b10) != 0) { // SYN packet
				// TODO: respond with SYN/ACK
			} else if ((flags & 0b1) != 0) { // FIN packet
				// TODO: respond with FIN/ACK
			}
			// TODO: Might have to handle other cases later

			return;
		}

		// Extract the message from the packet
		byte[] message = ByteBuffer.wrap(packet, dgHeaderLength + segHeaderLength, packet.length - dgHeaderLength - segHeaderLength).array();

		sock.getOutputStream().write(message); // Send segment to remote host

		byte[] response = new byte[Interceptor.MAX_PACKET_SIZE];
		int bytes;

		while ((bytes = sock.getInputStream().read(response)) != -1) { // Receive response from remote host as long as we haven't reached EOF
			System.out.println(byteArrToHexString(Arrays.copyOf(response, bytes)));
			// TODO: send message to local client
		}

	}

	// Returns a string of the bytes in the packet formatted in hex
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

		try {
			this.sock.close();
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}

		return true;
	}

}
