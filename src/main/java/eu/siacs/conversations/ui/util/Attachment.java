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

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import com.google.common.base.MoreObjects;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.MimeUtils;

public class Attachment implements Parcelable {

    Attachment(Parcel in) {
        uri = in.readParcelable(Uri.class.getClassLoader());
        mime = in.readString();
        uuid = UUID.fromString(in.readString());
        type = Type.valueOf(in.readString());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(uri, flags);
        dest.writeString(mime);
        dest.writeString(uuid.toString());
        dest.writeString(type.toString());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<Attachment> CREATOR = new Creator<Attachment>() {
        @Override
        public Attachment createFromParcel(Parcel in) {
            return new Attachment(in);
        }

        @Override
        public Attachment[] newArray(int size) {
            return new Attachment[size];
        }
    };

    public String getMime() {
        return mime;
    }

    public Type getType() {
        return type;
    }

    @NotNull
    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("uri", uri)
                .add("type", type)
                .add("uuid", uuid)
                .add("mime", mime)
                .toString();
    }

    public enum Type {
        FILE, IMAGE, LOCATION, RECORDING
    }

    private final Uri uri;
    private final Type type;
    private final UUID uuid;
    private final String mime;

    private Attachment(UUID uuid, Uri uri, Type type, String mime) {
        this.uri = uri;
        this.type = type;
        this.mime = mime;
        this.uuid = uuid;
    }

    private Attachment(Uri uri, Type type, String mime) {
        this.uri = uri;
        this.type = type;
        this.mime = mime;
        this.uuid = UUID.randomUUID();
    }

    public static boolean canBeSendInband(final List<Attachment> attachments) {
        for (Attachment attachment : attachments) {
            if (attachment.type != Type.LOCATION) {
                return false;
            }
        }
        return true;
    }

    public static List<Attachment> of(final Context context, Uri uri, Type type) {
        final String mime = type == Type.LOCATION ? null : MimeUtils.guessMimeTypeFromUri(context, uri);
        return Collections.singletonList(new Attachment(uri, type, mime));
    }

    public static List<Attachment> of(final Context context, List<Uri> uris, final String type) {
        final List<Attachment> attachments = new ArrayList<>();
        for (final Uri uri : uris) {
            if (uri == null) {
                continue;
            }
            final String mime = MimeUtils.guessMimeTypeFromUriAndMime(context, uri, type);
            attachments.add(new Attachment(uri, mime != null && isImage(mime) ? Type.IMAGE : Type.FILE, mime));
        }
        return attachments;
    }

    public static Attachment of(UUID uuid, final File file, String mime) {
        return new Attachment(uuid, Uri.fromFile(file), mime != null && (isImage(mime) || mime.startsWith("video/")) ? Type.IMAGE : Type.FILE, mime);
    }

    public static List<Attachment> extractAttachments(final Context context, final Intent intent, Type type) {
        List<Attachment> uris = new ArrayList<>();
        if (intent == null) {
            return uris;
        }
        final String contentType = intent.getType();
        final Uri data = intent.getData();
        if (data == null) {
            final ClipData clipData = intent.getClipData();
            if (clipData != null) {
                for (int i = 0; i < clipData.getItemCount(); ++i) {
                    final Uri uri = clipData.getItemAt(i).getUri();
                    final String mime = MimeUtils.guessMimeTypeFromUriAndMime(context, uri, contentType);
                    uris.add(new Attachment(uri, type, mime));
                }
            }
        } else {
            final String mime = MimeUtils.guessMimeTypeFromUriAndMime(context, data, contentType);
            uris.add(new Attachment(data, type, mime));
        }
        return uris;
    }

    public boolean renderThumbnail() {
        return type == Type.IMAGE || (type == Type.FILE && mime != null && renderFileThumbnail(mime));
    }

    private static boolean renderFileThumbnail(final String mime) {
        return mime.startsWith("video/")
                || isImage(mime)
                || "application/pdf".equals(mime);
    }

    public Uri getUri() {
        return uri;
    }

    public UUID getUuid() {
        return uuid;
    }

    private static boolean isImage(final String mime) {
        return mime.startsWith("image/") && !mime.equals("image/svg+xml");
    }
}
