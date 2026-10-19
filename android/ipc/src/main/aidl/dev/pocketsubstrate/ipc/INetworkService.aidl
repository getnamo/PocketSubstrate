package dev.pocketsubstrate.ipc;
import dev.pocketsubstrate.ipc.IReply;
import dev.pocketsubstrate.ipc.INetworkEvents;
oneway interface INetworkService {
    void pair(String link, IReply reply);
    void connect(String pairing, INetworkEvents events);
    void disconnect();
    void send(String message);
    void fetch(String requestId, String url, String allowedOrigins, IReply reply);
    void cancelFetch(String requestId);
}
