package de.fhg.aisec.ids.idscp2.drivers.default_driver_impl.secure_channel.server;

import de.fhg.aisec.ids.idscp2.idscp_core.configuration.IDSCPv2Callback;
import de.fhg.aisec.ids.idscp2.idscp_core.secure_channel.SecureChannel;
import de.fhg.aisec.ids.idscp2.idscp_core.secure_channel.SecureChannelEndpoint;
import de.fhg.aisec.ids.idscp2.idscp_core.secure_channel.SecureChannelListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * A TLSServerThread that notifies an IDSCPv2Config when a secure channel was created and the TLS handshake is done
 * When new data are available the serverThread transfers them to the SecureChannelListener
 *
 * Developer API
 *
 * constructors:
 * TLSServerThread(IDSCPv2Settings, IDSCPv2Callback) initializes the TLS Socket
 *
 * Methods:
 * run()    runs the serverThread and starts listening for new data
 *
 * close()  disconnects the serverThread
 *
 * setConnectionId(ConnectionId) set the internal connectionId, which is used for notifying the IDSCPv2Configuration
 *                                when the client quits the connection
 *
 * handshakeCompleted()        create a secureChannel, including this serverThread and provides it to the IDSCPv2Config
 *
 * send(byte[] data)            send data to the client
 *
 * onMessage(int len, byte[] rawData) is called when new data are available. Transfer them to the SecureChannelListener
 *
 * @author Leon Beckmann (leon.beckmann@aisec.fraunhofer.de)
 */
public class TLSServerThread extends Thread implements HandshakeCompletedListener, SecureChannelEndpoint {
    private static final Logger LOG = LoggerFactory.getLogger(TLSServerThread.class);

    private SSLSocket sslSocket;
    private volatile boolean running = true;
    private DataInputStream in;
    private DataOutputStream out;
    private String connectionId = null; //race condition avoided using CountDownLatch
    private CountDownLatch connectionIdLatch = new CountDownLatch(1);
    private SecureChannelListener listener = null;  // race conditions are avoided using CountDownLatch
    private IDSCPv2Callback callback;  // race conditions are avoided because callback is initialized by constructor
    private CountDownLatch listenerLatch = new CountDownLatch(1);


    TLSServerThread(SSLSocket sslSocket, IDSCPv2Callback callback){
        this.sslSocket = sslSocket;
        this.callback = callback;

        try {
            //set timout for blocking read
            sslSocket.setSoTimeout(5000);
            in = new DataInputStream(sslSocket.getInputStream());
            out = new DataOutputStream(sslSocket.getOutputStream());
        } catch (IOException e){
            LOG.error(e.getMessage());
            running = false;
        }
    }

    @Override
    public void setConnectionId(String connectionId){
        this.connectionId = connectionId;
        connectionIdLatch.countDown();
    }

    @Override
    public void run(){
        //wait for new data while running
        byte[] buf;
        while (running){
            try {
                int len = in.readInt();
                buf = new byte[len];
                in.readFully(buf, 0, len);
                onMessage(buf);

            } catch (SocketTimeoutException e){
                //timeout catches safeStop() call and allows to send server_goodbye
                //alternative: close sslSocket and catch SocketException
                //continue
            } catch (SSLException e) {
                LOG.error("SSL error");
                e.printStackTrace();
                running = false;
                return;
            } catch (EOFException e){
                running = false;
            } catch (IOException e){
                e.printStackTrace();
                running = false;
            }
        }

        try {
            connectionIdLatch.await();
            callback.connectionClosedHandler(this.connectionId);
        } catch (InterruptedException e){
            Thread.currentThread().interrupt();
        }

        LOG.trace("ServerThread is terminating");
        try {
            out.close();
            in.close();
            sslSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void send(byte[] data) {
        if (!isConnected()){
            LOG.error("Server cannot send data because socket is not connected");
        } else {
            try {
                out.writeInt(data.length);
                out.write(data);
                out.flush();
                LOG.trace("Send message: " + new String(data));
            } catch (IOException e){
                LOG.error("Server cannot send data. {}",e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        safeStop();
    }

    @Override
    public void onMessage(byte[] data)  {
        try{
            listenerLatch.await();
            this.listener.onMessage(data);
        } catch (InterruptedException e){
            Thread.currentThread().interrupt();
        }
    }

    private void safeStop(){
        running = false;
    }

    public boolean isConnected() {
        return (sslSocket != null && sslSocket.isConnected());
    }

    @Override
    public void handshakeCompleted(HandshakeCompletedEvent handshakeCompletedEvent) {
        LOG.debug("TLS handshake was successful");
        SecureChannel secureChannel = new SecureChannel(this);
        this.listener = secureChannel;
        listenerLatch.countDown();
        callback.secureChannelListenHandler(secureChannel);
    }
}