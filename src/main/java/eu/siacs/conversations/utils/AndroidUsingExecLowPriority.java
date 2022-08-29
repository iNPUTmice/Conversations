/*
 * Copyright 2015-2016 the original author or authors
 *
 * This software is licensed under the Apache License, Version 2.0,
 * the GNU Lesser General Public License version 2 or later ("LGPL")
 * and the WTFPL.
 * You may choose either license to govern your use of this software only
 * upon the condition that you accept all of the terms of either
 * the Apache License 2.0, the LGPL 2.1+ or the WTFPL.
 */
package eu.siacs.conversations.utils;


import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.logging.Level;

import de.measite.minidns.dnsserverlookup.AbstractDNSServerLookupMechanism;
import de.measite.minidns.dnsserverlookup.AndroidUsingReflection;
import de.measite.minidns.dnsserverlookup.DNSServerLookupMechanism;
import de.measite.minidns.util.PlatformDetection;

/**
 * Try to retrieve the list of DNS server by executing getprop.
 */
public class AndroidUsingExecLowPriority extends AbstractDNSServerLookupMechanism {

	public static final DNSServerLookupMechanism INSTANCE = new AndroidUsingExecLowPriority();
	public static final int PRIORITY = AndroidUsingReflection.PRIORITY + 1;

	private AndroidUsingExecLowPriority() {
		super(AndroidUsingExecLowPriority.class.getSimpleName(), PRIORITY);
	}

	@Override
	public String[] getDnsServerAddresses() {
		try {
			Process process = Runtime.getRuntime().exec("getprop");
			InputStream inputStream = process.getInputStream();
			LineNumberReader lnr = new LineNumberReader(
					new InputStreamReader(inputStream));
			String line;
			HashSet<String> server = new HashSet<>(6);
			while ((line = lnr.readLine()) != null) {
				int split = line.indexOf("]: [");
				if (split == -1) {
					continue;
				}
				String property = line.substring(1, split);
				String value = line.substring(split + 4, line.length() - 1);

				if (value.isEmpty()) {
					continue;
				}

				if (property.endsWith(".dns") || property.endsWith(".dns1") ||
						property.endsWith(".dns2") || property.endsWith(".dns3") ||
						property.endsWith(".dns4")) {

					// normalize the address

					InetAddress ip = InetAddress.getByName(value);

					if (ip == null) continue;

					value = ip.getHostAddress();

					if (value == null) continue;
					if (value.length() == 0) continue;

					server.add(value);
				}
			}
			if (server.size() > 0) {
				return server.toArray(new String[server.size()]);
			}
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Exception in findDNSByExec", e);
		}
		return null;
	}

	@Override
	public boolean isAvailable() {
		return PlatformDetection.isAndroid();
	}

}
