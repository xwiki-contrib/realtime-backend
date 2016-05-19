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
     * Get the channel keys for a document and create channels if they don't exist.
     * @param docRef the DocumentReference of the edited page
     * @param modifier the unique modifier/language ("default", "en", "fr", etc.)
     * @param editorsToCreate the editor types ("rtwiki", "rtwysiwyg", "events", etc.)
     */
    public Map<String,Object> getChannelKeys(DocumentReference docRef,
                                            String modifier,
                                            List<String> editorsToCreate,
                                            Boolean multipleChannels)
    {
        Map<String,Object> result = new HashMap<>();
        Map<String,Object> keyResult = new HashMap<>();

        if (!this.authMgr.hasAccess(Right.EDIT, getUser(), docRef)) {
            keyResult.put("error", "EPERM");
            return keyResult;
        }
        NetfluxBackend nfBackend = (NetfluxBackend) backend;

        // Clean empty channels
        nfBackend.channels.cleanEmpty();

        // Find all existing editor types
        List<String> channelDocId = Arrays.asList(docRef.toString(), modifier);
        String docIdString = channelDocId.toString();
        keyResult = nfBackend.channels.getKeysFromDocName(docIdString);

        // Check if we are allowed to create multiple channels for that document
        if (multipleChannels || keyResult.size() == 0 || (keyResult.size() == 1 && keyResult.containsKey("events"))) {
            // Create keys for requested editor types
            for (String editor : editorsToCreate) {
                // Check if the channel doesn't already exist
                if (!keyResult.containsKey(editor)) {
                    NetfluxBackend.Channel channel = nfBackend.createChannel(docIdString, editor);
                    Map<String, Object> chanMap = new HashMap<>();
                    chanMap.put("keys", channel.key);
                    chanMap.put("users", channel.users.size());
                    keyResult.put(editor, chanMap);
                }
            }
        }

        result.put("keys", keyResult);
        return result;
    }
}
