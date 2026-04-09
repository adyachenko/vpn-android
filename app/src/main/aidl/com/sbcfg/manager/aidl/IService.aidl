package com.sbcfg.manager.aidl;

import com.sbcfg.manager.aidl.IServiceCallback;

interface IService {
    int getStatus();
    void registerCallback(in IServiceCallback callback);
    oneway void unregisterCallback(in IServiceCallback callback);
}
