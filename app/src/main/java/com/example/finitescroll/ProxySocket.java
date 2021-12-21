package com.example.finitescroll;

public class ProxySocket implements Runnable {

    @Override
    public void run() {

    }

    /*
     * Stops the proxy socket and all underlying services
     * returns true if successful, false otherwise
     * Synchronized, in order to make callee wait
     */
    public synchronized boolean stopServices() {
        return true;
    }

}
