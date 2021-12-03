package com.example.finitescroll;

import android.content.Context;

public class Connections implements Runnable {

    public static final String LOCAL_ADDRESS     = "192.168.0.1";
    public static final int    LOCAL_VPN_PORT    = 1723;
    public static final int    LOCAL_SOCKET_PORT = 1337;

    private Interceptor interceptor;
    private Thread interceptorThread;

    private ProxySocket proxySocket;
    private Thread proxyThread;

    private Context context;

    public Connections(Context context) {
       this.context = context;
    }

    @Override
    public void run() {

        // Start the interceptor (Pseudo VPN)
        interceptor = new Interceptor(this.context);
        interceptorThread = new Thread(interceptor);
        interceptorThread.start();

        // Wait for VPN service to start
        while (!interceptor.isReady());

        // Start the proxy-socket
        proxySocket = new ProxySocket();
        proxyThread = new Thread(proxySocket);
        proxyThread.run();

    }

    /*
     * Stops all connections
     * returns true if successful, false otherwise
     * Synchronized, in order to make callee wait
     */
    public synchronized boolean stopServices() {

        boolean success = true;

        // Shut down proxy socket
        synchronized (proxySocket) {
            try {
                // Request proxy socket to stop it's services
                success = proxySocket.stopServices() && success;

                // Terminate connections thread
                proxyThread.join();

            } catch (InterruptedException e) {
                e.printStackTrace();
                success = false;
            }
        }

        // Shut down interceptor
        synchronized (interceptor) {
            try {
                // Request interceptor to stop it's services
                success = interceptor.stopServices() && success;

                // Terminate connections thread
                interceptorThread.join();

            } catch (InterruptedException e) {
                e.printStackTrace();
                success = false;
            }
        }

        return success;

    }

    public Interceptor getInterceptor() {
        return this.interceptor;
    }

}
