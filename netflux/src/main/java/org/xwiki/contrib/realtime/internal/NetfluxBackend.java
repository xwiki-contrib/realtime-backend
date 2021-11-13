/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.realtime.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.websocket.WebSocket;
import org.xwiki.contrib.websocket.WebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The Netflux WebSocket handler.
 * 
 * @version $Id$
 */
@Component
@Singleton
@Named("realtimeNetflux")
public class NetfluxBackend implements WebSocketHandler
{
    private static final long TIMEOUT_MILLISECONDS = 30000;

    private static final boolean USE_HISTORY_KEEPER = true;

    private static final String CMD_LEAVE = "LEAVE";

    private static final String CMD_MSG = "MSG";

    private static final String CMD_JOIN = "JOIN";

    private static final String ERR_INVALID = "EINVAL";

    private static final String ERR_NO_ENTRY = "ENOENT";

    /**
     * All existing channels.
     */
    public final ChannelBox channels = new ChannelBox();

    private final ObjectMapper mapper = new ObjectMapper();

    private final String historyKeeper = getRandomHexString((16));

    private final Object bigLock = new Object();

    private final UserBox users = new UserBox();

    /**
     * Store/remove/get users in memory.
     */
    private static class UserBox
    {
        private Map<WebSocket, User> userBySocket = new HashMap<WebSocket, User>();

        private Map<String, User> userByName = new HashMap<>();

        /**
         * Get a User by his name.
         * 
         * @param name the user name
         * @return the user with the specified name
         */
        User byName(String name)
        {
            return userByName.get(name);
        }

        /**
         * Get a user from a socket.
         * 
         * @param sock the WebSocket
         * @return the user connected to the given WebSocket
         */
        User bySocket(WebSocket sock)
        {
            return userBySocket.get(sock);
        }

        /**
         * Remove a user from memory.
         * 
         * @param u the User to remove
         * @return {@code true} if the user war removed, {@code false} otherwise
         */
        boolean removeUser(User u)
        {
            if (userBySocket.get(u.sock) == null && userByName.get(u.name) == null) {
                return false;
            }
            if (userBySocket.remove(u.sock) == null) {
                throw new RuntimeException("userBySocket does not contain user");
            }
            if (userByName.remove(u.name) == null) {
                throw new RuntimeException("userByName does not contain user");
            }
            return true;
        }

        /**
         * Add a user in memory.
         * 
         * @param u the User to add
         */
        void addUser(User u)
        {
            userBySocket.put(u.sock, u);
            userByName.put(u.name, u);
        }
    }

    /**
     * Store/remove/get channels in memory.
     */
    public static class ChannelBox
    {
        private Map<String, String> keyByName = new ConcurrentHashMap<String, String>();

        private Map<String, Channel> channelByKey = new ConcurrentHashMap<String, Channel>();

        /**
         * Get a Channel by its name.
         * 
         * @param name the channel name
         * @param type the channel type
         * @return the channel key
         */
        public String getKeyByName(String name, String type)
        {
            return keyByName.get(name + '-' + type);
        }

        /**
         * Get a Channel from its key.
         * 
         * @param key the WebSocket
         * @return the channel with the specified key
         */
        public Channel byKey(String key)
        {
            return channelByKey.get(key);
        }

        /**
         * Remove a Channel from memory.
         * 
         * @param c the Channel
         * @return {@code true} if the channel was removed, {@code false} otherwise
         */
        boolean removeChannel(Channel c)
        {
            if (channelByKey.get(c.key) == null && keyByName.get(c.name + '-' + c.type) == null) {
                return false;
            }
            if (channelByKey.remove(c.key) == null) {
                throw new RuntimeException("channelByKey does not contain that channel");
            }
            if (keyByName.remove(c.name + '-' + c.type) == null) {
                throw new RuntimeException("keyByName does not contain that channel");
            }
            return true;
        }

        /**
         * Add a Channel in memory.
         * 
         * @param c the Channel to add
         */
        void addChannel(Channel c)
        {
            channelByKey.put(c.key, c);
            keyByName.put(c.name + "-" + c.type, c.key);
        }

        /**
         * Clean empty channels (no users connected).
         */
        public void cleanEmpty()
        {
            try {
                long currentTime = System.currentTimeMillis();
                List<Channel> chans = new ArrayList<>(channelByKey.values());
                for (Channel channel : chans) {
                    Integer empty = USE_HISTORY_KEEPER ? 1 : 0;
                    if (channel.users.keySet().size() == empty
                        && (currentTime - channel.createdTime) > (1000 * 60 * 60 * 2)) {
                        removeChannel(channel);
                    }
                }
            } catch (Exception e) {
                // Keep going if cleanEmpty fails...
            }
        }

        /**
         * @param name the name of an XWiki document
         * @return the channels associated with the specified XWiki document
         */
        public Map<String, Object> getKeysFromDocName(String name)
        {
            Map<String, Object> keyByType = new HashMap<>();
            List<Channel> chans = new ArrayList<>(channelByKey.values());
            Integer removeUsers = USE_HISTORY_KEEPER ? 1 : 0;
            for (Channel channel : chans) {
                if (channel.name.equals(name)) {
                    Map<String, Object> chanMap = new HashMap<>();
                    chanMap.put("key", channel.key);
                    chanMap.put("users", channel.users.size() - removeUsers);
                    keyByType.put(channel.type, chanMap);
                }
            }
            return keyByType;
        }
    }

    /**
     * Represents a communication channel.
     */
    public static class Channel
    {
        /**
         * The users connected to this channel.
         */
        public final Map<String, User> users = new HashMap<String, User>();

        /**
         * Messages sent through this channel.
         */
        public LinkedList<String> messages = new LinkedList<String>();

        /**
         * The channel type.
         */
        public final String type;

        /**
         * The channel name.
         */
        public final String name;

        /**
         * Channel creation time.
         */
        public final long createdTime = System.currentTimeMillis();

        /**
         * Channel key.
         */
        public final String key;

        Channel(String name, String type)
        {
            this.type = type;
            this.name = name;
            this.key = getRandomHexString(48);
        }
    }

    private static class User
    {
        /**
         * The WebSocket connection to this user.
         */
        public final WebSocket sock;

        /**
         * The user name.
         */
        public final String name;

        /**
         * The messages that are pending to be sent to this user.
         */
        public final Queue<String> toBeSent = new LinkedList<>();

        /**
         * The channels this user has joined.
         */
        public final Set<Channel> chans = new HashSet<Channel>();

        /**
         * Whether the user is still connected.
         */
        public boolean connected;

        /**
         * The time when the last message was received from this user.
         */
        public long timeOfLastMessage = System.currentTimeMillis();

        User(WebSocket ws, String name)
        {
            this.sock = ws;
            this.name = name;
            this.connected = true;
        }
    }

    /**
     * Create a channel based on a "name" String. A random key will be generated for that channel. If the name is empty,
     * a 32 chars "cryptpad" key will be generated.
     * 
     * @param id the document identifier (reference and unique modifier)
     * @param type the channel type (events, rtwiki, etc.)
     * @return the created channel
     */
    public Channel createChannel(String id, String type)
    {
        Channel chan = new Channel(id, type);
        if (USE_HISTORY_KEEPER) {
            chan.users.put(historyKeeper, null);
        }
        channels.addChannel(chan);
        return chan;
    }

    /**
     * Handler called when a socket is closed/disconnected.
     * 
     * @param ws the WebSocket
     */
    private void wsDisconnect(WebSocket ws)
    {
        synchronized (bigLock) {

            User user = users.bySocket(ws);

            if (user == null) {
                return;
            }

            users.removeUser(user);
            user.connected = false;

            for (Channel chan : user.chans) {
                chan.users.remove(user.name);
                List<Object> leaveMsg = buildDefault(user.name, CMD_LEAVE, chan.key, "Quit: [ wsDisconnect() ]");
                String msgStr = display(leaveMsg);
                sendChannelMessage(CMD_LEAVE, user, chan, msgStr);
                // Remove the channel when there is no user anymore (the history keeper doesn't count)
                Integer minSize = (USE_HISTORY_KEEPER) ? 1 : 0;
                if (chan.users.keySet().size() == minSize) {
                    channels.removeChannel(chan);
                }
            }
        }
    }

    private static String getRandomHexString(int numchars)
    {
        Random r = new Random();
        StringBuffer sb = new StringBuffer();
        while (sb.length() < numchars) {
            sb.append(Integer.toHexString(r.nextInt()));
        }
        return sb.toString().substring(0, numchars);
    }

    private String display(List<Object> list)
    {
        try {
            return mapper.writeValueAsString(list);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize message", e);
        }
    }

    /**
     * Add a message to the sending queue of a User.
     * 
     * @param toUser the User
     * @param msgStr the string message
     */
    private void sendMessage(User toUser, String msgStr)
    {
        toUser.toBeSent.add(msgStr);
    }

    private boolean isCheckpoint(String msgStr)
    {
        try {
            ArrayList<?> msg = mapper.readValue(msgStr, ArrayList.class);
            return ((String) msg.get(msg.size() - 1)).indexOf("cp|[4,[") == 0;
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse message.", e);
        }
    }

    /**
     * Broadcast a message to a channel.
     * 
     * @param cmd the message type/command
     * @param me the sender
     * @param chan the channel where the message is sent
     * @param msgStr the message
     */
    private void sendChannelMessage(String cmd, User me, Channel chan, String msgStr)
    {
        chan.users.values().stream().filter(Objects::nonNull).filter(u -> !CMD_MSG.equals(cmd) || !u.equals(me))
            .forEach(u -> sendMessage(u, msgStr));
        if (USE_HISTORY_KEEPER && Arrays.asList(CMD_MSG, CMD_LEAVE).contains(cmd)) {
            if (CMD_MSG.equals(cmd) && isCheckpoint(msgStr)) {
                // Prune old messages from memory
                LinkedList<String> msgsNext = new LinkedList<String>();
                for (Iterator<String> it = chan.messages.descendingIterator(); it.hasNext();) {
                    String msg = it.next();
                    msgsNext.addFirst(msg);
                    if (isCheckpoint(msg)) {
                        break;
                    }
                }
                chan.messages = msgsNext;
            }
            chan.messages.add(msgStr);
        }
    }

    /*
     * The following function are used to build the different types of messages sent by the server : ACK, JACK
     * (Join-ACK), JOIN, LEAVE, MSG, ERROR
     */
    private ArrayList<Object> buildAck(Integer seq)
    {
        ArrayList<Object> msg = new ArrayList<>();
        msg.add(seq);
        msg.add("ACK");
        return msg;
    }

    private ArrayList<Object> buildJack(Integer seq, String obj)
    {
        ArrayList<Object> msg = new ArrayList<>();
        msg.add(seq);
        msg.add("JACK");
        msg.add(obj);
        return msg;
    }

    private ArrayList<Object> buildDefault(String userId, String cmd, String chanName, String reason)
    {
        ArrayList<Object> msg = new ArrayList<>();
        msg.add(0);
        msg.add(userId);
        msg.add(cmd);
        msg.add(chanName);
        if (reason != null) {
            msg.add(reason);
        }
        return msg;
    }

    private ArrayList<Object> buildMessage(Integer seq, String userId, String obj, Object msgStr)
    {
        ArrayList<Object> msg = new ArrayList<>();
        msg.add(0);
        msg.add(userId);
        msg.add(CMD_MSG);
        msg.add(obj);
        msg.add(msgStr);
        return msg;
    }

    private ArrayList<Object> buildError(Integer seq, String errorType, String errorMessage)
    {
        ArrayList<Object> msg = new ArrayList<>();
        msg.add(seq);
        msg.add("ERROR");
        msg.add(errorType);
        msg.add(errorMessage);
        return msg;
    }

    /**
     * Handler called when a message is received by the server from a socket.
     * 
     * @param message the received message
     * @param ws the socket from which the message is received
     */
    private void onMessage(String message, WebSocket ws)
    {
        ArrayList<?> msg;
        try {
            msg = mapper.readValue(message, ArrayList.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse received message.", e);
        }
        if (msg == null) {
            return;
        }

        User user = users.bySocket(ws);

        if (user == null) {
            wsDisconnect(ws);
            return;
        }

        long now = System.currentTimeMillis();
        if (user != null) {
            user.timeOfLastMessage = now;
        }

        // It's way too much of a pain to hunt down the setTimeout() equiv
        // in netty so I'm just going to run the check every time something comes in
        // on the websocket to disconnect anyone who hasn't written to the WS in
        // more than 30 seconds.
        List<WebSocket> socks = new LinkedList<WebSocket>(users.userBySocket.keySet());
        for (WebSocket sock : socks) {
            if (now - users.bySocket(sock).timeOfLastMessage > TIMEOUT_MILLISECONDS) {
                wsDisconnect(sock);
            }
        }

        // We have to clean unused channel

        Integer seq = (Integer) msg.get(0);
        String cmd = msg.get(1).toString();
        String obj = "";
        if (msg.size() >= 3) {
            obj = (msg.get(2) != null) ? msg.get(2).toString() : null;
        }

        if (CMD_JOIN.equals(cmd)) {
            // JOIN request: - Send a JACK - Join or create the channel - Send a JOIN message to the selected channel
            onJoinCommand(user, obj, seq);
        } else if (CMD_LEAVE.equals(cmd)) {
            // LEAVE request: - Check if the request is correct - Send an ACK - Leave the channel - Send a LEAVE message
            // to the selected channel
            onLeaveCommand(user, obj, seq);
        } else if ("PING".equals(cmd)) {
            // PING: - Send an ACK
            ArrayList<Object> ackMsg = buildAck(seq);
            sendMessage(user, display(ackMsg));
        } else if (CMD_MSG.equals(cmd)) {
            // MSG (patch): - Send an ACK - Check if the history of the channel is requested - Yes : send the history -
            // No : transfer the message to the recipient
            onMessageCommand(user, obj, msg, seq);
        }
    }

    private void onJoinCommand(User user, String obj, Integer seq)
    {
        // Length == 32 ==> Cryptpad key
        // Length == 48 ==> RTFrontend key
        if (obj != null && obj.length() != 0 && obj.length() != 32 && obj.length() != 48) {
            ArrayList<Object> errorMsg = buildError(seq, ERR_INVALID, "");
            sendMessage(user, display(errorMsg));
            return;
        }
        Channel chan = (obj == null) ? null : channels.byKey(obj);
        // No key provided : create a new channel
        if (chan == null && StringUtils.isEmpty(obj)) {
            chan = createChannel(getRandomHexString(32), null);
        } else if (chan == null) {
            ArrayList<Object> errorMsg = buildError(seq, ERR_NO_ENTRY, "");
            sendMessage(user, display(errorMsg));
            return;
        }
        ArrayList<Object> jackMsg = buildJack(seq, chan.key);
        sendMessage(user, display(jackMsg));
        user.chans.add(chan);
        for (String userId : chan.users.keySet()) {
            ArrayList<Object> inChannelMsg = buildDefault(userId, CMD_JOIN, chan.key, null);
            sendMessage(user, display(inChannelMsg));
        }
        chan.users.put(user.name, user);
        channels.cleanEmpty();
        ArrayList<Object> joinMsg = buildDefault(user.name, CMD_JOIN, chan.key, null);
        sendChannelMessage(CMD_JOIN, user, chan, display(joinMsg));
    }

    private void onLeaveCommand(User user, String obj, Integer seq)
    {
        ArrayList<Object> errorMsg = null;
        if (obj == null || obj.length() == 0) {
            errorMsg = buildError(seq, ERR_INVALID, "undefined");
        }
        if (errorMsg != null && channels.byKey(obj) == null) {
            errorMsg = buildError(seq, ERR_NO_ENTRY, obj);
        }
        if (errorMsg != null && !channels.byKey(obj).users.containsKey(user.name)) {
            errorMsg = buildError(seq, "NOT_IN_CHAN", obj);
        }
        if (errorMsg != null) {
            sendMessage(user, display(errorMsg));
            return;
        }
        ArrayList<Object> ackMsg = buildAck(seq);
        sendMessage(user, display(ackMsg));
        Channel chan = channels.byKey(obj);
        chan.users.remove(user.name);
        user.chans.remove(chan);
        ArrayList<Object> leaveMsg = buildDefault(user.name, CMD_LEAVE, obj, "");
        sendChannelMessage(CMD_LEAVE, user, chan, display(leaveMsg));
    }

    private void onMessageCommand(User user, String obj, List<?> msg, Integer seq)
    {
        ArrayList<Object> ackMsg = buildAck(seq);
        sendMessage(user, display(ackMsg));
        if (USE_HISTORY_KEEPER && obj.equals(historyKeeper)) {
            ArrayList<String> msgHistory;
            try {
                msgHistory = new ObjectMapper().readValue(msg.get(3).toString(),
                    this.mapper.getTypeFactory().constructCollectionType(List.class, String.class));
            } catch (IOException e) {
                msgHistory = null;
                e.printStackTrace();
            }
            String text = (msgHistory == null) ? "" : msgHistory.get(0);
            if (text.equals("GET_HISTORY")) {
                String chanName = msgHistory.get(1);
                Channel chan = channels.byKey(chanName);
                if (chan != null && chan.messages != null) {
                    for (String msgStr : chan.messages) {
                        sendMessage(user, msgStr);
                    }
                }
                String endHistoryMsg = "{\"state\":1, \"channel\":\"" + chanName + "\"}";
                ArrayList<Object> msgEndHistory = buildMessage(0, historyKeeper, user.name, endHistoryMsg);
                sendMessage(user, display(msgEndHistory));
            }
            return;
        }
        if (obj.length() != 0 && channels.byKey(obj) == null && users.byName(obj) == null) {
            ArrayList<Object> errorMsg = buildError(seq, ERR_NO_ENTRY, obj);
            sendMessage(user, display(errorMsg));
            return;
        }
        if (channels.byKey(obj) != null) {
            ArrayList<Object> msgMsg = buildMessage(0, user.name, obj, msg.get(3));
            Channel chan = channels.byKey(obj);
            sendChannelMessage(CMD_MSG, user, chan, display(msgMsg));
            return;
        }
        if (users.byName(obj) != null) {
            ArrayList<Object> msgMsg = buildMessage(0, user.name, obj, msg.get(3));
            sendMessage(users.byName(obj), display(msgMsg));
            return;
        }
    }

    private static class SendJob
    {
        /**
         * The user to send the messages to.
         */
        public User user;

        /**
         * The messages to send to the specified user.
         */
        public List<String> messages;
    }

    private SendJob getSendJob()
    {
        synchronized (bigLock) {
            for (User u : users.userByName.values()) {
                if (u.connected && !u.toBeSent.isEmpty()) {
                    SendJob out = new SendJob();
                    out.messages = new ArrayList<String>(u.toBeSent);
                    out.user = u;
                    u.toBeSent.clear();
                    return out;
                }
            }
            return null;
        }
    }

    @Override
    public void onConnect(WebSocket sock)
    {
        synchronized (bigLock) {
            User user = users.bySocket(sock);

            // Send the IDENT message
            if (user == null) {
                // Register the user
                String userName = getRandomHexString(32);
                user = new User(sock, userName);
                users.addUser(user);
            }
            sock.onDisconnect(() -> {
                synchronized (bigLock) {
                    wsDisconnect(sock);
                }
            });

            ArrayList<Object> identMsg = buildDefault("", "IDENT", user.name, null);
            String identMsgStr = display(identMsg);
            try {
                user.sock.send(identMsgStr);
            } catch (Exception e) {
                wsDisconnect(user.sock);
                return;
            }

            sock.onMessage(message -> {
                SendJob sj;
                synchronized (bigLock) {
                    onMessage(message, sock);
                    sj = getSendJob();
                }
                while (sj != null) {
                    for (String msg : sj.messages) {
                        if (!sj.user.connected) {
                            break;
                        }
                        try {
                            sj.user.sock.send(msg);
                        } catch (Exception e) {
                            wsDisconnect(sj.user.sock);
                            return;
                        }
                    }
                    sj = getSendJob();
                }
            });
        }
    }
}
