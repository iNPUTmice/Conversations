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

package eu.siacs.conversations.xmpp;

import androidx.annotation.NonNull;

import eu.siacs.conversations.xmpp.stanzas.AbstractStanza;

public class InvalidJid implements Jid {

	private final String value;

	private InvalidJid(String jid) {
		this.value = jid;
	}

	public  static Jid of(String jid, boolean fallback) {
		final int pos = jid.indexOf('/');
		if (fallback && pos >= 0 && jid.length() >= pos + 1) {
			if (jid.substring(pos+1).trim().isEmpty()) {
				return Jid.ofEscaped(jid.substring(0,pos));
			}
		}
		return new InvalidJid(jid);
	}

	@Override
	@NonNull
	public String toString() {
		return value;
	}

	@Override
	public boolean isFullJid() {
		throw new AssertionError("Not implemented");
	}

	@Override
	public boolean isBareJid() {
		throw new AssertionError("Not implemented");
	}

	@Override
	public boolean isDomainJid() {
		throw new AssertionError("Not implemented");
	}

	@Override
	public Jid asBareJid() {
		throw new AssertionError("Not implemented");
	}


	@Override
	public Jid withResource(CharSequence charSequence) {
		throw new AssertionError("Not implemented");
	}

	@Override
	public String getLocal() {
		throw new AssertionError("Not implemented");
	}

	@Override
	public String getEscapedLocal() {
		throw new AssertionError("Not implemented");
	}

	@Override
	public Jid getDomain() {
		throw new AssertionError("Not implemented");
	}

	@Override
	public String getResource() {
		throw new AssertionError("Not implemented");
	}

	@Override
	public String toEscapedString() {
		throw new AssertionError("Not implemented");
	}

	@Override
	public int length() {
		return value.length();
	}

	@Override
	public char charAt(int index) {
		return value.charAt(index);
	}

	@Override
	public CharSequence subSequence(int start, int end) {
		return value.subSequence(start, end);
	}

	@Override
	public int compareTo(@NonNull Jid o) {
		throw new AssertionError("Not implemented");
	}

	public static Jid getNullForInvalid(Jid jid) {
		if (jid instanceof InvalidJid) {
			return null;
		} else {
			return jid;
		}
	}

	public static boolean isValid(Jid jid) {
		return !(jid != null && jid instanceof InvalidJid);
	}

	public static boolean hasValidFrom(AbstractStanza stanza) {
		final String from = stanza.getAttribute("from");
		if (from == null) {
			return false;
		}
		try {
			Jid.ofEscaped(from);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
