package org.xwiki.contrib.realtime.internal;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.websocket.WebSocket;
import org.xwiki.contrib.websocket.WebSocketHandler;
import org.xwiki.model.reference.DocumentReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.inject.Named;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

//import java.util.

@Component
@Named("realtimeNetflux")
public class NetfluxBackend implements WebSocketHandler
{
    private static final long TIMEOUT_MILLISECONDS = 30000;
    private static final boolean USE_HISTORY_KEEPER = true;

    private final Map<String, Channel> channelByName = new HashMap<String, Channel>();
    private final Map<WebSocket, User> userBySocket = new HashMap<WebSocket, User>();
    private final Map<String, User> users = new HashMap<>();
    private final String historyKeeper = getRandomHexString((16));
    private final Object bigLock = new Object();

    private static class Channel
    {
        final Map<String, User> users = new HashMap<String, User>();
        final List<String> messages = new LinkedList<String>();
        final String name;
        Channel(String name) {
            this.name = name;
        }
    }

    private static class User
    {
        final WebSocket sock;
        final String name;
        final Queue<String> toBeSent = new ConcurrentLinkedQueue<>();
        final Set<Channel> chans = new HashSet<Channel>();
        final Object smallLock = new Object();
        long timeOfLastMessage;

        User(WebSocket ws, String name)
        {
            this.sock = ws;
            this.name = name;
        }
    }

    private void wsDisconnect(WebSocket ws)
    {
        synchronized (bigLock) {
            for (String uname : new ArrayList<String>(users.keySet())) {
                if (users.get(uname).sock == ws) {
                    users.remove(uname);
                }
            }

            User user = userBySocket.get(ws);
            if (user == null) { return; }
            //System.out.println("Disconnect "+user.name);
            userBySocket.remove(ws);

            for (Channel chan : user.chans) {
                try {
                    chan.users.remove(user.name);
                    List<Object> leaveMsg =
                            buildDefault(user.name, "LEAVE", chan.name, "Quit: [ wsDisconnect() ]");
                    String msgStr = display(leaveMsg);
                    sendChannelMessage("LEAVE", user, chan, msgStr);
                    chan.messages.add(msgStr);
                    // Remove the channel when there is no user anymore (the history keeper doesn't count)
                    Integer minSize = (USE_HISTORY_KEEPER) ? 1 : 0;
                    if (chan.users.keySet().size() == minSize) {
                        channelByName.remove(chan.name);
                    }
                } catch (Exception e) {
                    System.out.println("Unable to leave the channel");
                    e.printStackTrace();
                    // TODO something, anything
                }
            }
        }
    }

    private String getRandomHexString(int numchars){
        Random r = new Random();
        StringBuffer sb = new StringBuffer();
        while(sb.length() < numchars){
            sb.append(Integer.toHexString(r.nextInt()));
        }

        return sb.toString().substring(0, numchars);
    }

    private String display(List<Object> list) {
        String result = "[";
        Boolean first = true;
        for (Object elmt : list) {
            if (!first) {
                result += ",";
            } else {
                first = false;
            }
            if (elmt instanceof Integer) {
                result += elmt.toString();
            } else {
                String newstring = elmt.toString().replaceAll("\\\\","\\\\\\\\");
                newstring = newstring.replaceAll("\"","\\\\\\\"");
                result += "\"" + newstring + "\"";
            }
        }
        result += "]";
        return result;
    }

    private void sendMessage(User toUser, String msgStr) {
        toUser.toBeSent.add(msgStr);
    }

    private void sendChannelMessage(String cmd, User me, Channel chan, String msgStr) {
        for (User u : chan.users.values()) {
            //System.out.println("Sending to " + clientName + "  " + msgStr);
            if (u != null && (cmd != "MSG" || !u.equals(me))) {
                sendMessage(u, msgStr);
            }
        }
        if(USE_HISTORY_KEEPER && cmd == "MSG") {
            //System.out.println("Added in history : "+msgStr);
            chan.messages.add(msgStr);
        }
    }

    private ArrayList<Object> buildAck (Integer seq) {
        ArrayList<Object> msg = new ArrayList<>();
        msg.add(seq);
        msg.add("ACK");
        return msg;
    }
    private ArrayList<Object> buildJack (Integer seq, String obj) {
        ArrayList<Object> msg = new ArrayList<>();
        msg.add(seq);
        msg.add("JACK");
        msg.add(obj);
        return msg;
    }
    private ArrayList<Object> buildDefault (String userId, String cmd, String chanName, String reason) {
        ArrayList<Object> msg = new ArrayList<>();
        msg.add(0);
        msg.add(userId);
        msg.add(cmd);
        msg.add(chanName);
        if(reason != null) {
            msg.add(reason);
        }
        return msg;
    }
    private ArrayList<Object> buildMessage(Integer seq, String userId, String obj, Object msgStr) {
        ArrayList<Object> msg = new ArrayList<>();
        msg.add(0);
        msg.add(userId);
        msg.add("MSG");
        msg.add(obj);
        msg.add(msgStr);
        return msg;
    }
    private ArrayList<Object> buildError(Integer seq, String errorType, String errorMessage) {
        ArrayList<Object> msg = new ArrayList<>();
        msg.add(seq);
        msg.add("ERROR");
        msg.add(errorType);
        msg.add(errorMessage);
        return msg;
    }

    private void onMessage(WebSocket ws) {
        ArrayList<Object> msg;
        try {
            msg = new ObjectMapper().readValue(ws.recv(), ArrayList.class);
        } catch (IOException e) {
            msg = null;
            e.printStackTrace();
        }
        if (msg == null) return;
        //System.out.println("> " + msg);

        User user = userBySocket.get(ws);

        long now = System.currentTimeMillis();
        if (user != null) {
            user.timeOfLastMessage = now;
        }

        // It's way too much of a pain to hunt down the setTimeout() equiv
        // in netty so I'm just going to run the check every time something comes in
        // on the websocket to disconnect anyone who hasn't written to the WS in
        // more than 30 seconds.
        List<WebSocket> socks = new LinkedList<WebSocket>(userBySocket.keySet());
        for (WebSocket sock : socks) {
            if (now - userBySocket.get(sock).timeOfLastMessage > TIMEOUT_MILLISECONDS) {
                wsDisconnect(sock);
            }
        }

        Integer seq = (Integer) msg.get(0);
        String cmd = msg.get(1).toString();
        String obj = (msg.get(2) != null) ? msg.get(2).toString() : null;

        if(cmd.equals("JOIN")) {
            if (obj != null && obj.length() == 0) {
                ArrayList<Object> errorMsg = buildError(seq, "ENOENT", "");
                sendMessage(user, display(errorMsg));
                return;
            }
            else if (obj == null) {
                obj = getRandomHexString(32);
            }
            Channel chan = channelByName.get(obj);
            if (chan == null) {
                if(USE_HISTORY_KEEPER) {
                    chan = new Channel(obj);
                    chan.users.put(historyKeeper, null);
                }
                channelByName.put(obj, chan);
            }
            ArrayList<Object> jackMsg = buildJack(seq, chan.name);
            sendMessage(user, display(jackMsg));
            user.chans.add(chan);
            for(String userId : chan.users.keySet()) {
                ArrayList<Object> inChannelMsg = buildDefault(userId, "JOIN", obj, null);
                sendMessage(user, display(inChannelMsg));
            }
            chan.users.put(user.name, user);
            ArrayList<Object> joinMsg = buildDefault(user.name, "JOIN", obj, null);
            sendChannelMessage("JOIN", user, chan, display(joinMsg));
            return;
        }
        if(cmd.equals("LEAVE")) {
            ArrayList<Object> errorMsg = null;
            if (obj == null || obj.length() == 0)
                errorMsg = buildError(seq, "EINVAL", "undefined");
            if (errorMsg != null && channelByName.get(obj) == null)
                errorMsg = buildError(seq, "ENOENT", obj);
            if (errorMsg != null && !channelByName.get(obj).users.containsKey(user.name))
                errorMsg = buildError(seq, "NOT_IN_CHAN", obj);
            if (errorMsg != null) {
                sendMessage(user, display(errorMsg));
                return;
            }
            ArrayList<Object> ackMsg = buildAck(seq);
            sendMessage(user, display(ackMsg));
            Channel chan = channelByName.get(obj);
            ArrayList<Object> leaveMsg = buildDefault(user.name, "LEAVE", obj, "");
            sendChannelMessage("LEAVE", user, chan, display(leaveMsg));
            chan.users.remove(user.name);
            user.chans.remove(chan);
        }
        if(cmd.equals("PING")) {
            ArrayList<Object> ackMsg = buildAck(seq);
            sendMessage(user, display(ackMsg));
        }
        if(cmd.equals("MSG")) {
            if(USE_HISTORY_KEEPER && obj.equals(historyKeeper)) {
                ArrayList<String> msgHistory;
                try {
                    msgHistory = new ObjectMapper().readValue(msg.get(3).toString(), ArrayList.class);
                } catch (IOException e) {
                    msgHistory = null;
                    e.printStackTrace();
                }
                String text = (msgHistory == null) ? "" : msgHistory.get(0);
                if(text.equals("GET_HISTORY")) {
                    String chanName = msgHistory.get(1);
                    Channel chan = channelByName.get(chanName);
                    if(chan != null && chan.messages != null && chan.messages.size() > 0) {
                        Integer i = 0;
                        for (String msgStr : chan.messages) {
                            sendMessage(user, msgStr);
                            i++;
                        }
                    }
                    ArrayList<Object> msgEndHistory = buildMessage(0, historyKeeper, user.name, 0);
                    sendMessage(user, display(msgEndHistory));
                }
                return;
            }
            if (obj.length() != 0 && channelByName.get(obj) == null && users.get(obj) == null) {
                ArrayList<Object> errorMsg = buildError(seq, "ENOENT", obj);
                sendMessage(user, display(errorMsg));
                return;
            }
            ArrayList<Object> ackMsg = buildAck(seq);
            sendMessage(user, display(ackMsg));
            if (channelByName.get(obj) != null) {
                ArrayList<Object> msgMsg = buildMessage(0, user.name, obj, msg.get(3));
                Channel chan = channelByName.get(obj);
                sendChannelMessage("MSG", user, chan, display(msgMsg));
                return;
            }
            if (users.get(obj) != null) {
                ArrayList<Object> msgMsg = buildMessage(0, user.name, obj, msg.get(3));
                sendMessage(users.get(obj), display(msgMsg));
                return;
            }
        }
    }

    public void onWebSocketConnect(WebSocket sock)
    {
        synchronized (bigLock) {
            User user = userBySocket.get(sock);
            System.out.println("New user");

            // Send the IDENT message
            String userName = getRandomHexString(32);
            if (user == null) { // Register the user
                System.out.println("User is null : create");
                user = new User(sock, userName);
                users.put(userName, user);
                userBySocket.put(sock, user);
                // System.out.println("Registered " + userName);
                sock.onDisconnect(new WebSocket.Callback() {
                    public void call(WebSocket ws) {
                        synchronized(bigLock) {
                            wsDisconnect(ws);
                        }
                    }
                });
            }
            ArrayList<Object> identMsg = buildDefault("", "IDENT", user.name, null);
            String identMsgStr = display(identMsg);
            //sendMessage(user, display(identMsg));
            try {
                //System.out.println("Sending to " + user.name + " : " + identMsgStr);
                user.sock.send(identMsgStr);
            } catch (Exception e) {
                System.out.println("Sending failed");
                //wsDisconnect(dest.sock); TODO
            }

            sock.onMessage(new WebSocket.Callback() {
                public void call(WebSocket ws) {
                    List<User> ul;
                    synchronized (bigLock) {
                        onMessage(ws);
                        ul = new ArrayList<>(users.values());
                    }
                    for (User u : ul) {
                        synchronized (u.smallLock) {
                            for (; ; ) {
                                String m = u.toBeSent.poll();
                                if (m == null) {
                                    break;
                                }
                                try {
                                    //System.out.println("Sending to " + u.name + " : " + m);
                                    u.sock.send(m);
                                } catch (Exception e) {
                                    System.out.println("Sending failed");
                                    //wsDisconnect(dest.sock); TODO
                                }
                            }
                        }
                    }
                }
            });
        }
    }
}