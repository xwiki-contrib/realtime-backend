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
package org.xwiki.contrib.realtime.script;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.*;

import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.realtime.internal.NetfluxBackend;
import org.xwiki.contrib.websocket.WebSocketHandler;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.script.service.ScriptService;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;

@Component
@Singleton
@Named("realtime")
public class NetfluxBackendScriptService implements ScriptService
{
    public static final DocumentReference GUEST_USER =
            new DocumentReference("xwiki", "XWiki", "XWikiGuest");

    @Inject
    private DocumentAccessBridge bridge;

    @Inject
    @Named("realtimeNetflux")
    private WebSocketHandler backend;

    @Inject
    private AuthorizationManager authMgr;

    private DocumentReference getUser()
    {
        DocumentReference user = this.bridge.getCurrentUserReference();
        if (user == null) { user = GUEST_USER; }
        return user;
    }

    /**
     * Get the channel key for a document and create the channel if it doesn't exist.
     * @param docRef the DocumentReference of the edited page
     * @param language the document language ("default", "en", "fr", etc.)
     * @param type the editor type ("rtwiki", "rtwysiwyg" or "events")
     * @return
     */
    public Map<String,Object> getChannelKey(DocumentReference docRef, String language, String type, Boolean createIfNull)
    {
        Map<String,Object> result = new HashMap<>();
        if (!this.authMgr.hasAccess(Right.EDIT, getUser(), docRef)) {
            result.put("error", "EPERM");
            return result;
        }
        List<String> stringList = Arrays.asList(docRef.toString(), language, type);
        String channelName = stringList.toString();
        NetfluxBackend nfBackend = (NetfluxBackend) backend;
        String key = nfBackend.channels.getKeyByName(channelName);
        // Check if the channel exists, and create it if requested
        if(key == null && createIfNull) {
            key = nfBackend.createChannel(channelName).key;
        }
        Integer users = 0;
        // Compute the number of users in the channel if it exists
        if(key != null) {
            try {
                // Get the size of the userlist and remove 1 if the history keeper is used.
                users = nfBackend.channels.byKey(key).users.size();
                if(nfBackend.USE_HISTORY_KEEPER) {
                    users--;
                }
            } catch (Exception e) {
                users = -1;
            }
        }
        result.put("key", key);
        result.put("users", users);
        return result;
    }
}