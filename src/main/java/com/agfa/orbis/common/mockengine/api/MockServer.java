package com.agfa.orbis.common.mockengine.api;


public interface MockServer {

    void start(String mappingsDir, int port);

    void stop();

    int getPort();

    boolean isRunning();
}
