package com.example.finitescroll;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.SynchronousQueue;

public class ProxySocket implements Runnable {

	SynchronousQueue<byte[]> sharedBuffer;

	Interceptor interceptor; // The interceptor which invoked the constructor

	private final FileOutputStream out; // This is where the outgoing packets are written to

	private final Socket sock; // Remote connection
	private InputStream sockIn;
	private OutputStream sockOut;

	private int remoteIp, localIp;
	private int remotePort, localPort;

	private int MSS;

	private final HashMap<Integer, byte[]> cachedRequests;

	private boolean isRunning;

	private int pseudoHeaderChecksum;

	private byte[] ipHeaderTemplate;
	private byte[] tcpHeaderTemplate;

	// Default TCP options sent in response to SYN packets
	private static final byte[] defaultOptions = new byte[] {
													0x02, 0x04, (byte) 0x7F, (byte) 0xFF,  	// Maximum segment size options
													0x01, 0x03, 0x03, 0x06 					// Window options
												};

	ProxySocket(byte[] firstPacket, FileOutputStream out, Interceptor interceptor) {
		this.interceptor = interceptor;

		this.sharedBuffer = new SynchronousQueue<>();
		this.out = out;

		extractHeaderInfo(firstPacket);

		this.cachedRequests = new HashMap<>();

		this.sock = new Socket();
		interceptor.protect(this.sock);
		initialiseRemoteConnection();

		this.isRunning = true;
	}

	@Override
	public void run() {
		byte[] packet;

		while (this.isRunning) {

			try {
				packet = sharedBuffer.take();

				if (packet.length != 0) { // As length is zero if proxy is getting shut down
					proxyPacket(packet);
				}
			} catch (InterruptedException | IOException e) {
				e.printStackTrace();
			}
		}
	}

	// Extracts all the relevant info about the local client and remote host from the first packet sent
	private void extractHeaderInfo(byte[] firstPacket) {
		ByteBuffer wrapper = ByteBuffer.wrap(firstPacket);

		wrapper.position(12);
		localIp = wrapper.getInt(12);

		remoteIp = wrapper.getInt(16);

		this.localPort = wrapper.getShort(20) & 0xFFFF; // Masking because java
		this.remotePort = wrapper.getShort(22) & 0xFFFF;

		this.MSS = wrapper.getShort(42);

		// Calculate TCP pseudo header checksum excluding segment length
		this.pseudoHeaderChecksum = 6; // Protocol

		pseudoHeaderChecksum += (localIp & 0xFFFF) + ((localIp >> 16) & 0xFFFF); // Source address
		pseudoHeaderChecksum += (remoteIp & 0xFFFF) + ((remoteIp >> 16) & 0xFFFF); // Dest address

		// Add overflow
		pseudoHeaderChecksum = (pseudoHeaderChecksum & 0xFFFF) + (pseudoHeaderChecksum >> 16); // Greatest achievable number can't cause overflow twice

		// Create the templates for the IP- and TCP-header
		ByteBuffer ipHeader = ByteBuffer.allocate(20);
		ipHeader.put((byte) 0x45) 					// Version & header length
				.put((byte) 0x00)					// Differentiated services
				.putShort((short) 0x0000)			// Total length
				.putShort((short) 0x0000)		    // Identification
				.putShort((short) 0x4000) 			// Fragmentation flags
				.put((byte) 0x40) 					// TTL
				.put((byte) 0x06) 					// Protocol
				.putShort((short) 0x0000) 			// Checksum
				.putInt(remoteIp)
				.putInt(localIp);
		this.ipHeaderTemplate = ipHeader.array();

		ByteBuffer tcpHeader = ByteBuffer.allocate(20);
		tcpHeader.putShort((short) remotePort)
				.putShort((short) localPort)
				.putInt(0)										 // Sequence number
				.putInt(0)					   				     // Acknowledgment number
				.put((byte) 0x50) 			 					 // Header Length
				.put((byte) 0x00) 			 					 // Flags
				.putShort((short) Interceptor.MAX_PACKET_SIZE) 	 // Window size
				.putShort((short) 0x0000) 	 					 // Checksum
				.putShort((short) 0x0000); 	 					 // Urgent pointer
		this.tcpHeaderTemplate = tcpHeader.array();
	}

	// Initialises the socket between the local client and the remote host
	private void initialiseRemoteConnection() {
		try {
			byte[] ipAddr = ByteBuffer.allocate(4).putInt(remoteIp).array(); // Convert int remoteIp to byte arr

			this.sock.connect(new InetSocketAddress(InetAddress.getByAddress(ipAddr), remotePort));

			this.sockIn = this.sock.getInputStream();
			this.sockOut = this.sock.getOutputStream();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// Takes in a packet from the local client, sends it to the remote host and sends back the remote response to the local client
	private void proxyPacket(byte[] packet) throws IOException {
		int dgHeaderLength = 4 * (packet[0] & 0xF); // Datagram header length
		int segHeaderLength = 4 * ((packet[dgHeaderLength + 12] >> 4) & 0xF); // Segment header length

		ByteBuffer packetWrapper = ByteBuffer.wrap(packet);

		int seqNum = packetWrapper.getInt(24);
		int ackNum = packetWrapper.getInt(28);

		// Extract the message from the packet
		byte[] message = Arrays.copyOfRange(packet, dgHeaderLength + segHeaderLength, packet.length);

		seqNum += message.length;

		// No message, might have to react to certain flags
		if (message.length == 0) {

			System.out.println(Thread.currentThread().getId() + ": received packet with no message: " + byteArrToHexString(packet));

			byte flags = (byte) (packet[dgHeaderLength + 13] & 0xF); // TCP flags

			if ((flags & 0x02) != 0) { // SYN packet
				respondToSyn(packet);
			} else if ((flags & 0b1) != 0) { // FIN packet
				System.out.println("Fin packet " + Thread.currentThread().getId() + ": " + byteArrToHexString(packet));
				closeConnection(ackNum, seqNum + 1);
			}
			// TODO: Might have to handle other cases later

			return;
		}

		if (cachedRequests.containsKey(ackNum)) { // Resending cached reply

			byte[] segment = cachedRequests.get(ackNum);

			short identification = (short) Interceptor.random.nextInt();

			System.out.println(Thread.currentThread().getId() + ": Resending " + byteArrToHexString(segment));

			// Send packet to local client with PSH and ACK set
			synchronized (out) {
				out.write(createDatagram(segment, identification));
			}

			return;
		}

		sockOut.write(message); // Send message to remote host

		byte[] response = new byte[Interceptor.MAX_PACKET_SIZE];
		ByteBuffer responseBuf = ByteBuffer.wrap(response);

		int bytes = 0;

		do {
			byte[] tempBuf = new byte[Interceptor.MAX_PACKET_SIZE];
			int bufLen = sockIn.read(tempBuf);

			if (bufLen < 0)
				break;

			responseBuf.put(tempBuf, 0, bufLen);

			bytes += bufLen;

			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

		} while (sockIn.available() > 0);

		System.out.println(Thread.currentThread().getId() + ": " + byteArrToHexString(packet) + "\nProduced response: " + byteArrToHexString(response));

		if (bytes <= 0) {
			System.out.println(Thread.currentThread().getId() + ": Received invalid length of response: " + byteArrToHexString(packet));
			return;
		}

		segmentAndSend(ackNum, seqNum, Arrays.copyOfRange(response, 0, bytes));
	}

	/*
	 * Creates the reply for a SYN packet and sends it to the local port
	 */
	private void respondToSyn(byte[] packet) throws IOException {
		// Get TCP segment
		int seqNum = ByteBuffer.wrap(packet).getInt(24); // Their sequence number

		byte[] segment = createSegment(Interceptor.random.nextInt(), seqNum + 1, (byte) 0x12, defaultOptions, new byte[0]);
		byte[] datagram = createDatagram(segment, (short) Interceptor.random.nextInt());

		// Write reply to local buffer
		synchronized (out) {
			out.write(datagram);
		}
	}

	/*
	 * Calls interceptor to destroy this, also responds with FIN-ACK packet
	 */
	private void closeConnection(int seqNum, int ackNum) throws IOException {
		System.out.println(Thread.currentThread().getId() + ": shutting down, sending FIN - ACK");

		segmentAndSend(seqNum, ackNum, (byte) 0x11, new byte[0]);

		this.interceptor.shutdownProxy(this, this.localPort);
	}

	/*
	 * Takes in a message, segments it and sends it to the local client
	 * The flags are the ones to be used in the TCP-header
	 */
	private void segmentAndSend(int seqNum, int ackNum, byte flags, byte[] message) throws IOException {
		int offset;

		for (offset = 0; offset + MSS < message.length; offset += MSS) {

			short identification = (short) Interceptor.random.nextInt();
			byte[] segment = createSegment(seqNum + offset, ackNum, (byte) flags, Arrays.copyOfRange(message, offset, offset + MSS));

			cachedRequests.put(seqNum + offset, segment);

			synchronized (out) {
				out.write(createDatagram(segment, identification));
			}
		}

		short identification = (short) Interceptor.random.nextInt();
		byte[] segment = createSegment(seqNum + offset, ackNum, (byte) flags, Arrays.copyOfRange(message, offset, message.length));

		cachedRequests.put(seqNum + offset, segment);

		synchronized (out) {
			out.write(createDatagram(segment, identification));
		}

		System.out.println(Thread.currentThread().getId() + ": sent: " + identification);
	}

	/*
	 * Calls segmentAndSend with the flags with ACK and PSH set
	 */
	private void segmentAndSend(int ackNum, int seqNum, byte[] packet) throws IOException {
		segmentAndSend(ackNum, seqNum, (byte) 0x18, packet);
	}

	/*
	 * Encapsulates the supplied segment in a datagram with the given identification
	 */
	private byte[] createDatagram(byte[] segment, short identification) {
		ByteBuffer datagram = ByteBuffer.allocate(segment.length + 20);
		datagram.put(createDatagramHeader(segment.length + 20, identification)).put(segment);


		System.out.println(Thread.currentThread().getId() + ": created Datagram: " + byteArrToHexString(datagram.array()));
		return datagram.array();
	}

	/*
	* Creates the IP header from the given parameters
	 */
	private byte[] createDatagramHeader(int length, short identification) {
		ByteBuffer ipHeader = ByteBuffer.wrap(Arrays.copyOf(this.ipHeaderTemplate, this.ipHeaderTemplate.length));

		ipHeader.putShort(2, (short) length)
				.putShort(4, identification);

		// Calculate the checksum
		int ipChecksum = calculateChecksum(ipHeader.array());
		ipHeader.putShort(10, (short) ipChecksum);

		return ipHeader.array();
	}

	/*
	 * Creates the TCP segment from the given parameters
	 */
	private byte[] createSegment(int seqNum, int ackNum, byte flags, byte[] options, byte[] data) {
		ByteBuffer tcpSegment = ByteBuffer.allocate(tcpHeaderTemplate.length + options.length + data.length);

		tcpSegment.put(tcpHeaderTemplate)
				.put(options)
				.put(data);

		int headerLength = (tcpHeaderTemplate.length + options.length + 3) / 4; // + 3 in order to round up

		tcpSegment.putInt(4, seqNum)						// Our sequence number
				.putInt(8, ackNum)						// ACK num
				.put(12, (byte) (headerLength << 4))		// Length
				.put(13, flags); 							// Flags

		// Create TCP checksum
		int tcpChecksum = calculateTcpChecksum(tcpSegment.array());
		tcpSegment.putShort(16, (short) tcpChecksum);

		return tcpSegment.array();
	}

	/*
	 * Creates a TCP segment with only data and no options set
	 */
	private byte[] createSegment(int seqNum, int ackNum, byte flags, byte[] data) {
		return createSegment(seqNum, ackNum, flags, new byte[0], data);
	}

	/*
	 * Calculates the 16-bit checksum of the given byte array
	 */
	private static int calculateChecksum(byte[] packet) {
		int checksum = 0;

		for (int i = 0; i < packet.length - 1; i += 2) {
			int newVal = ((packet[i] & 0xFF) << 8) | (packet[i + 1] & 0xFF);

			checksum += newVal;

			checksum = (checksum & 0xFFFF) + (checksum >> 16); // Add overflow
		}

		if (packet.length % 2 != 0) { // Add last byte
			checksum += (packet[packet.length - 1] & 0xFF) << 8;

			checksum = (checksum & 0xFFFF) + (checksum >> 16); // Add overflow
		}

		// Perform 1's complement
		return ~checksum;
	}

	private int calculateTcpChecksum(byte[] packet) {
		int checksum = ~calculateChecksum(packet); // Have to "undo" 1's complement

		checksum += this.pseudoHeaderChecksum + packet.length; // Pseudo-header checksum + TCP segment length
		checksum = (checksum & 0xFFFF) + (checksum >> 16); // Add overflow

		return ~checksum;
	}

	// Returns a string of the bytes in the packet formatted in hex
	static String byteArrToHexString(byte[] packet) {
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
		this.sharedBuffer.poll();

		try {
			this.sock.close();
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}

		return true;
	}

}
