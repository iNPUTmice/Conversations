package eu.siacs.conversations.dns.record;

import java.io.DataInputStream;
import java.io.IOException;

import eu.siacs.conversations.dns.Record.TYPE;

public class A implements Data {

    private byte[] ip;

    @Override
    public TYPE getType() {
        return TYPE.A;
    }

    @Override
    public byte[] toByteArray() {
        return ip;
    }

    @Override
    public void parse(DataInputStream dis, byte[] data, int length)
            throws IOException {
        ip = new byte[4];
        dis.readFully(ip);
    }

    @Override
    public String toString() {
        return Integer.toString(ip[0] & 0xff) + "." +
               Integer.toString(ip[1] & 0xff) + "." +
               Integer.toString(ip[2] & 0xff) + "." +
               Integer.toString(ip[3] & 0xff);
    }

}
