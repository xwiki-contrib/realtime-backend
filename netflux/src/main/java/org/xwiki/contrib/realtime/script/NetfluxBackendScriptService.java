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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.realtime.internal.NetfluxBackend;
import org.xwiki.contrib.websocket.WebSocketHandler;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.script.service.ScriptService;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;

/**
 * Script service for the Netflux back-end.
 * 
 * @version $Id$
 */
@Component
@Singleton
@Named("realtime")
public class NetfluxBackendScriptService implements ScriptService
{
    @Inject
    @Named("realtimeNetflux")
    private WebSocketHandler backend;

    @Inject
    private ContextualAuthorizationManager authorization;

    /**
     * Get the channel keys for a document and create channels if they don't exist.
     * 
     * @param docRef the DocumentReference of the edited page
     * @param modifier the unique modifier/language ("default", "en", "fr", etc.)
     * @param editor the editor type ("rtwiki", "rtwysiwyg", "events", etc.)
     * @return the document channel keys
     */
    public Map<String, Object> getChannelKey(DocumentReference docRef, String modifier, String editor)
    {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> keyResult = new HashMap<>();

        if (!this.authorization.hasAccess(Right.EDIT, docRef)) {
            keyResult.put("error", "EPERM");
            return keyResult;
        }
        NetfluxBackend nfBackend = (NetfluxBackend) backend;

        // Clean empty channels.
        nfBackend.channels.cleanEmpty();

        // Find all existing editor types.
        List<String> channelDocId = Arrays.asList(docRef.toString(), modifier);
        String docIdString = channelDocId.toString();
        keyResult = nfBackend.channels.getKeysFromDocName(docIdString);

        // Check if the "editor" channel doesn't already exist.
        if (editor != null && editor.trim().length() > 0 && !keyResult.containsKey(editor)) {
            String key = nfBackend.createChannel(docIdString, editor).key;
            Map<String, Object> chanMap = new HashMap<>();
            chanMap.put("key", key);
            chanMap.put("users", 0);
            keyResult.put(editor, chanMap);
        }

        result.put("keys", keyResult);
        return result;
    }
}
