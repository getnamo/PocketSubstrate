package dev.pocketsubstrate.node;
import android.os.ParcelFileDescriptor;
interface INodeService {
    void start(in ParcelFileDescriptor input, in ParcelFileDescriptor output, String script);
    oneway void stop();
}
