package com.example.finitescroll;

import android.content.Context;

public class Connections implements Runnable {

    public static final String LOCAL_ADDRESS     = "192.168.0.1";
    public static final int    LOCAL_VPN_PORT    = 1723;
    public static final int    LOCAL_SOCKET_PORT = 1337;

    Context context;

    public Connections(Context context) {
       this.context = context;
    }

    @Override
    public void run() {

        // Start the interceptor (Pseudo VPN)
        Interceptor interceptor = new Interceptor(this.context);
        Thread interceptorThread = new Thread(interceptor);
        interceptorThread.start();

        // Wait for VPN service to start
        while (!interceptor.isReady());

        // Start the proxy-socket
        Thread proxySocket = new Thread(new ProxySocket());
        proxySocket.run();

    }
}
