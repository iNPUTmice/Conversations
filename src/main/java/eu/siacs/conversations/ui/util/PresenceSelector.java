/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.ui.util;

import android.app.Activity;
import android.content.Context;
import android.util.Pair;

import androidx.appcompat.app.AlertDialog;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Presences;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.jingle.RtpCapability;

public class PresenceSelector {

    public static void showPresenceSelectionDialog(Activity activity, final Conversation conversation, final OnPresenceSelected listener) {
        final Contact contact = conversation.getContact();
        final String[] resourceArray = contact.getPresences().toResourceArray();
        showPresenceSelectionDialog(activity, contact, resourceArray, fullJid -> {
            conversation.setNextCounterpart(fullJid);
            listener.onPresenceSelected();
        });
    }

    public static void selectFullJidForDirectRtpConnection(final Activity activity, final Contact contact, final RtpCapability.Capability required, final OnFullJidSelected onFullJidSelected) {
        final String[] resources = RtpCapability.filterPresences(contact, required);
        if (resources.length == 1) {
            onFullJidSelected.onFullJidSelected(contact.getJid().withResource(resources[0]));
        } else {
            showPresenceSelectionDialog(activity, contact, resources, onFullJidSelected);
        }
    }

    private static void showPresenceSelectionDialog(final Activity activity, final Contact contact, final String[] resourceArray, final OnFullJidSelected onFullJidSelected) {
        final Presences presences = contact.getPresences();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(activity.getString(R.string.choose_presence));
        Pair<Map<String, String>, Map<String, String>> typeAndName = presences.toTypeAndNameMap();
        final Map<String, String> resourceTypeMap = typeAndName.first;
        final Map<String, String> resourceNameMap = typeAndName.second;
        final String[] readableIdentities = new String[resourceArray.length];
        final AtomicInteger selectedResource = new AtomicInteger(0);
        for (int i = 0; i < resourceArray.length; ++i) {
            String resource = resourceArray[i];
            if (resource.equals(contact.getLastResource())) {
                selectedResource.set(i);
            }
            String type = resourceTypeMap.get(resource);
            String name = resourceNameMap.get(resource);
            if (type != null) {
                if (Collections.frequency(resourceTypeMap.values(), type) == 1) {
                    readableIdentities[i] = translateType(activity, type);
                } else if (name != null) {
                    if (Collections.frequency(resourceNameMap.values(), name) == 1
                            || CryptoHelper.UUID_PATTERN.matcher(resource).matches()) {
                        readableIdentities[i] = translateType(activity, type) + "  (" + name + ")";
                    } else {
                        readableIdentities[i] = translateType(activity, type) + " (" + name + " / " + resource + ")";
                    }
                } else {
                    readableIdentities[i] = translateType(activity, type) + " (" + resource + ")";
                }
            } else {
                readableIdentities[i] = resource;
            }
        }
        builder.setSingleChoiceItems(readableIdentities,
                selectedResource.get(),
                (dialog, which) -> selectedResource.set(which));
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(
                R.string.ok,
                (dialog, which) -> onFullJidSelected.onFullJidSelected(
                        getNextCounterpart(contact, resourceArray[selectedResource.get()])
                )
        );
        builder.create().show();
    }

    public static Jid getNextCounterpart(final Contact contact, final String resource) {
        return getNextCounterpart(contact.getJid(), resource);
    }

    public static Jid getNextCounterpart(final Jid jid, final String resource) {
        if (resource.isEmpty()) {
            return jid.asBareJid();
        } else {
            return jid.withResource(resource);
        }
    }

    public static void warnMutualPresenceSubscription(Activity activity, final Conversation conversation, final OnPresenceSelected listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(conversation.getContact().getJid().toString());
        builder.setMessage(R.string.without_mutual_presence_updates);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.ignore, (dialog, which) -> {
            conversation.setNextCounterpart(null);
            if (listener != null) {
                listener.onPresenceSelected();
            }
        });
        builder.create().show();
    }

    private static String translateType(Context context, String type) {
        switch (type.toLowerCase()) {
            case "pc":
                return context.getString(R.string.type_pc);
            case "phone":
                return context.getString(R.string.type_phone);
            case "tablet":
                return context.getString(R.string.type_tablet);
            case "web":
                return context.getString(R.string.type_web);
            case "console":
                return context.getString(R.string.type_console);
            default:
                return type;
        }
    }

    public interface OnPresenceSelected {
        void onPresenceSelected();
    }

    public interface OnFullJidSelected {
        void onFullJidSelected(Jid jid);
    }
}
