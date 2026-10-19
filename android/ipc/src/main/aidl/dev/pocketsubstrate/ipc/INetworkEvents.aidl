package dev.pocketsubstrate.ipc;
import android.os.ParcelFileDescriptor;
oneway interface INetworkEvents {
    void status(String value);
    void delivery(in ParcelFileDescriptor payload, String owner);
}
