package com.par9uet.jm.utils

import android.util.Log

inline fun <reified T> T.log(msg: String) {
    val tag = T::class.java.simpleName
    Log.d("[JM-MOBILE] $tag", msg)
    LogBuffer.append(tag, msg)
}

fun log(tag: String, msg: String) {
    Log.d("[JM-MOBILE] $tag", msg)
    LogBuffer.append(tag, msg)
}

fun logError(tag: String, msg: String) {
    Log.e("[JM-MOBILE] $tag", msg)
    LogBuffer.appendError(tag, msg)
}