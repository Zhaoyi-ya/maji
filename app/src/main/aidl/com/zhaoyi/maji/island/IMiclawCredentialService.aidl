package com.zhaoyi.maji.island;

interface IMiclawCredentialService {
    String getSessionJson(boolean forceRefresh);
    void destroy();
}
