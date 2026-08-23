package com.par9uet.jm.retrofit

import com.par9uet.jm.BuildConfig
import com.par9uet.jm.utils.md5

val API_TS = System.currentTimeMillis() / 1000
const val API_VERSION = "1.8.2"
val API_TOKEN_HASH = md5("${API_TS}${BuildConfig.JM_APP_DATA_SECRET}")
