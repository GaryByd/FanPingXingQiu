package com.hmdp.utils;

public interface ILock {
    boolean tryLock(Long timeOutSec);
    void unlock();
}
