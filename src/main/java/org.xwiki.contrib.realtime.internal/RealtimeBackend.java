package org.xwiki.contrib.realtime.internal;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedList;
//import java.util.
import javax.inject.Named;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.websocket.WebSocketHandler;
import org.xwiki.contrib.websocket.WebSocket;
import org.xwiki.model.reference.DocumentReference;

@Component
@Named("realtime")
public class RealtimeBackend implements WebSocketHandler
{
    private Map<String, Channel> channelByName = new HashMap<String, Channel>();
    private Map<WebSocket, User> userBySocket = new HashMap<WebSocket, User>();

    private static String[] parseBenc(String msg)
    {
        int lenlen = msg.indexOf(':');
        int len = Integer.parseInt(msg.substring(0,lenlen));
        String out = msg.substring(lenlen+1, lenlen+1+len);
        String rem = msg.substring(lenlen+1+len);
        return new String[] { out, rem };
    }

    private static Message parseMessage(String msgStr)
    {
        Message m = new Message();
        String[] ret;
        ret = parseBenc(msgStr);
        m.senderPass = ret[0];
        ret = parseBenc(ret[1]);
        m.sender = ret[0];
        ret = parseBenc(ret[1]);
        m.channel = ret[0];
        ret = parseBenc(ret[1]);
        m.content = ret[0];
        if (!("").equals(ret[1])) {
            throw new RuntimeException("Crap following message [" + msgStr + "]");
        }
        return m;
    }

    private static class Message
    {
        String senderPass;
        String sender;
        String content;
        String channel;
    }

    private static class User
    {
        final WebSocket sock;
        final String name;
        final Channel chan;

        User(WebSocket ws, String name, Channel chan)
        {
            this.sock = ws;
            this.name = name;
            this.chan = chan;
        }
    }

    public void onWebSocketConnect(WebSocket sock)
    {
        sock.onMessage(new WebSocket.Callback() {
            public void call(WebSocket ws) {

                DocumentReference userRef = ws.getUser();

                final String userName =
                      userRef.getWikiReference().getName() + ":"
                    + userRef.getLastSpaceReference().getName() + "."
                    + userRef.getName();

                Message msg = parseMessage(ws.recv());

                //System.out.println("Incoming message " + msg.sender + "  " + msg.content);

                if (!userName.equals(msg.sender.substring(0,msg.sender.lastIndexOf('-')))) {
                    return;
                }

                User user = userBySocket.get(ws);

                if (user == null) {
                    // user not registered in chan
                    if (!("[0]").equals(msg.content)) {
                        return;
                    }

                    Channel chan = channelByName.get(msg.channel);
                    // he wants to register
                    if (chan == null) {
                        chan = new Channel(msg.channel);
                        channelByName.put(msg.channel, chan);
                    }

                    user = new User(ws, msg.sender, chan);
                    userBySocket.put(ws, user);

                    chan.users.put(msg.sender, user);
                    ws.send("0:" + msg.channel.length() + ":" + msg.channel + "5:[1,0]");
                    for (String m : chan.messages) {
                        ws.send(m);
                    }
                    //System.out.println("Registered " + msg.sender + " in " + mag.channel);
                    //return;
                    ws.onDisconnect(new WebSocket.Callback() {
                        public void call(WebSocket ws) {
                            User user = userBySocket.get(ws);
                            user.chan.users.remove(user.name);
                            userBySocket.remove(ws);
                            String msgStr = user.name.length() + ":" + user.name
                                + user.chan.name.length() + ":" + user.chan.name
                                + "5:[3,0]";
                            for (User u : user.chan.users.values()) {
                                u.sock.send(msgStr);
                            }
                            user.chan.messages.add(msgStr);
                            if (user.chan.users.keySet().size() == 0) {
                                channelByName.remove(user.chan.name);
                            }
                        }
                    });
                }

                String msgStr = msg.sender.length() + ":" + msg.sender
                    + msg.channel.length() + ":" + msg.channel
                    + msg.content.length() + ":" + msg.content;

                for (User u : user.chan.users.values()) {
                    //System.out.println("Sending to " + clientName + "  " + msgStr);
                    u.sock.send(msgStr);
                }
                user.chan.messages.add(msgStr);
            }
        });
    }

    private static class Channel 
    {
        final Map<String, User> users = new HashMap<String, User>();
        final List<String> messages = new LinkedList<String>();
        final String name;
        Channel(String name) { this.name = name; }
    }
}
