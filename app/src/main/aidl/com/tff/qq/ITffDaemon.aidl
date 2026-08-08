package com.tff.qq;

interface ITffDaemon {
    int init();
    int getMode();
    void log(int mode, String line);
}
