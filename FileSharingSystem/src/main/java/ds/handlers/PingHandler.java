package main.java.ds.handlers;

import main.java.ds.Constants;
import main.java.ds.BSServerClient.ChannelMessage;
import main.java.ds.core.RoutingTable;
import main.java.ds.core.TimeHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Logger;

public class PingHandler implements AbstractRequestHandler, AbstractResponseHandler{

    private final Logger LOG = Logger.getLogger(PingHandler.class.getName());

    private static PingHandler pingHandler;

    private boolean initiated;
    private BlockingQueue<ChannelMessage> channelOut;
    private RoutingTable routingTable;
    private TimeHandler timeHandler;
    private Map<String, Integer> pingFailureCount = new HashMap<String, Integer>();
    private TimeoutCallback callback = new pingTimeoutCallback();

    private PingHandler() {
        this.initiated = true;
    }

    public synchronized static PingHandler getInstance() {
        if (pingHandler == null){
            pingHandler = new PingHandler();
        }
        return pingHandler;
    }

    @Override
    public void sendRequest(ChannelMessage message) {
        try {
            channelOut.put(message);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void handleResponse(ChannelMessage message) {

        LOG.fine("Received PING : " + message.getMessage()
                + " from: " + message.getAddress()
                + " port: " + message.getPort());

        StringTokenizer stringToken = new StringTokenizer(message.getMessage(), " ");

        String length = stringToken.nextToken();
        String keyword = stringToken.nextToken();
        String address = stringToken.nextToken().trim();
        int port = Integer.parseInt(stringToken.nextToken().trim());
        switch (keyword) {
            case "BPING":
                int hops = Integer.parseInt(stringToken.nextToken().trim());

                //if a neighbour sends a bping, pass it to the other neighbours
                if (routingTable.is_Neighbour(address, port)) {
                    if (hops > 0) {
                        forwardBPing(address, port, hops - 1);
                    }
                } else {

                    //check if we can add another neighbour
                    int result = this.routingTable.getCount();
                    if (result < Constants.MAX_NEIGHBOURS) {
                        //if can add, then send a bpong
                        String payload = String.format(Constants.BPONG_FORMAT,
                                this.routingTable.get_Address(), this.routingTable.get_Port());

                        String rawMessage = String.format(Constants.MSG_FORMAT, payload.length() + 5, payload);
                        ChannelMessage outGoingMsg = new ChannelMessage(address,
                                port, rawMessage);
                        this.sendRequest(outGoingMsg);
                    } else {
                        //otherwise send it to the neighbours
                        if (hops > 0) {
                            forwardBPing(address, port, hops - 1);
                        }
                    }
                }

                break;
            case "LEAVE":
                System.out.println("receiving leave");
                this.routingTable.removeNeighbour(address, port);
                if (routingTable.getCount() <= Constants.MIN_NEIGHBOURS) {
                    sendBPing(address, port);
                }
                this.routingTable.print();

                break;
            default:
                int result = this.routingTable.add_Neighbour(address, port, message.getPort());

                if (result != 0) {
                    String payload = String.format(Constants.PONG_FORMAT,
                            this.routingTable.get_Address(), this.routingTable.get_Port());

                    String rawMessage = String.format(Constants.MSG_FORMAT, payload.length() + 5, payload);
                    ChannelMessage outGoingMsg = new ChannelMessage(address,
                            port, rawMessage);
                    this.sendRequest(outGoingMsg);
                }
                break;
        }

    }

    public void sendPing(String address, int port) {
        String payload = String.format(Constants.PING_FORMAT,
                this.routingTable.get_Address(),
                this.routingTable.get_Port());
        String rawMessage = String.format(Constants.MSG_FORMAT, payload.length() + 5,payload);
        ChannelMessage message = new ChannelMessage(address, port,rawMessage);
        this.pingFailureCount.putIfAbsent(
                String.format(Constants.PING_MESSAGE_ID_FORMAT, address, port),
                0);
        this.timeHandler.registering_Request(
                String.format(Constants.PING_MESSAGE_ID_FORMAT, address, port),
                Constants.PING_TIMEOUT,
                this.callback
                );
        this.sendRequest(message);

    }

    private void sendBPing(String address, int port) {
        ArrayList<String> targets = routingTable.getOtherNeighbours(address,port);
        String payload = String.format(Constants.BPING_FORMAT,
                this.routingTable.get_Address(),
                this.routingTable.get_Port(),
                Constants.BPING_HOP_LIMIT);
        String rawMessage = String.format(Constants.MSG_FORMAT, payload.length() + 5,payload);
        for (String target: targets) {
            ChannelMessage message = new ChannelMessage(
                    target.split(":")[0],
                    Integer.parseInt(target.split(":")[1]), rawMessage);
            sendRequest(message);
        }
    }

    private void forwardBPing(
            String originAddress,
            int originPort,
            int currentHop) {
        ArrayList<String> targets = routingTable.getOtherNeighbours(originAddress,originPort);
        String payload = String.format(Constants.BPING_FORMAT,
                originAddress,
                originPort,
                currentHop);
        String rawMessage = String.format(Constants.MSG_FORMAT, payload.length() + 5,payload);
        for (String target: targets) {
            ChannelMessage message = new ChannelMessage(
                    target.split(":")[0],
                    Integer.parseInt(target.split(":")[1]), rawMessage);
            sendRequest(message);
        }
    }


    @Override
    public void init(
            RoutingTable routingTable,
            BlockingQueue<ChannelMessage> channelOut,
            TimeHandler timeHandler) {
            this.routingTable = routingTable;
            this.channelOut = channelOut;
            this.timeHandler = timeHandler;

    }

    private class pingTimeoutCallback implements TimeoutCallback {

        @Override
        public void onTimeout(String messageId) {
            pingFailureCount.put(messageId,pingFailureCount.get(messageId) + 1);
            if(pingFailureCount.get(messageId) >= Constants.PING_RETRY) {
                LOG.fine("neighbour lost :( =>" + messageId);
                routingTable.removeNeighbour(
                        messageId.split(":")[1],
                        Integer.valueOf(messageId.split(":")[2]));
            }

            if (routingTable.getCount() < Constants.MIN_NEIGHBOURS) {
                sendBPing(
                        messageId.split(":")[1],
                        Integer.valueOf(messageId.split(":")[2]));
            }
        }

        @Override
        public void onResponse(String messageId) {
            pingFailureCount.put(messageId, 0);
        }
    }


}
