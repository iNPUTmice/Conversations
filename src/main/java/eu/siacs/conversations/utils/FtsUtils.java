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

package eu.siacs.conversations.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;


public class FtsUtils {

	private static final List<String> KEYWORDS = Arrays.asList("OR", "AND");

	public static List<String> parse(String input) {
		List<String> term = new ArrayList<>();
		for (String part : input.replace('"',' ').split("\\s+")) {
			if (part.isEmpty()) {
				continue;
			}
			final String cleaned = clean(part);
			if (isKeyword(cleaned) || cleaned.contains("*")) {
				term.add(part);
			} else if (!cleaned.isEmpty()) {
				term.add(cleaned);
			}
		}
		return term;
	}

	public static String toMatchString(List<String> terms) {
		StringBuilder builder = new StringBuilder();
		for (String term : terms) {
			if (builder.length() != 0) {
				builder.append(' ');
			}
			if (isKeyword(term)) {
				builder.append(term.toUpperCase(Locale.ENGLISH));
			} else if (term.contains("*") || term.startsWith("-")) {
				builder.append(term);
			} else {
				builder.append(term).append('*');
			}
		}
		return builder.toString();
	}

	static boolean isKeyword(String term) {
		return KEYWORDS.contains(term.toUpperCase(Locale.ENGLISH));
	}

	private static int getStartIndex(String term) {
		int length = term.length();
		int index = 0;
		while (term.charAt(index) == '*') {
			++index;
			if (index >= length) {
				break;
			}
		}
		return index;
	}

	private static int getEndIndex(String term) {
		int index = term.length() - 1;
		while (term.charAt(index) == '*') {
			--index;
			if (index < 0) {
				break;
			}
		}
		return index;
	}

	private static String clean(String input) {
		int begin = getStartIndex(input);
		int end = getEndIndex(input);
		if (begin > end) {
			return "";
		} else {
			return input.substring(begin, end + 1);
		}
	}

	public static String toUserEnteredString(List<String> term) {
		final StringBuilder builder = new StringBuilder();
		for(String part : term) {
			if (builder.length() != 0) {
				builder.append(' ');
			}
			builder.append(part);
		}
		return builder.toString();
	}
}
