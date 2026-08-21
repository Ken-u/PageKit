package com.kenjc.pagekit.profile;

import android.os.ParcelFileDescriptor;

interface IProfileWorker {
    ParcelFileDescriptor execute(String requestJson);
}
