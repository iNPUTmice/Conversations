package eu.siacs.conversations.xml;

import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import eu.siacs.conversations.Config;

public class XmlReader implements Closeable {
	private final XmlPullParser parser;
	private InputStream is;

	public XmlReader() {
		this.parser = Xml.newPullParser();
		try {
			this.parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
		} catch (XmlPullParserException e) {
			Log.d(Config.LOGTAG, "error setting namespace feature on parser");
		}
	}

	public void setInputStream(InputStream inputStream) throws IOException {
		if (inputStream == null) {
			throw new IOException();
		}
		this.is = inputStream;
		try {
			parser.setInput(new InputStreamReader(this.is));
		} catch (XmlPullParserException e) {
			throw new IOException("error resetting parser");
		}
	}

	public void reset() throws IOException {
		if (this.is == null) {
			throw new IOException();
		}
		try {
			parser.setInput(new InputStreamReader(this.is));
		} catch (XmlPullParserException e) {
			throw new IOException("error resetting parser");
		}
	}

	@Override
	public void close() {
		this.is = null;
	}

	public Tag readTag() throws IOException {
		try {
			while (this.is != null && parser.next() != XmlPullParser.END_DOCUMENT) {
				if (parser.getEventType() == XmlPullParser.START_TAG) {
					Tag tag = Tag.start(parser.getName());
					final String xmlns = parser.getNamespace();
					for (int i = 0; i < parser.getAttributeCount(); ++i) {
						final String prefix = parser.getAttributePrefix(i);
						String name;
						if (prefix != null && !prefix.isEmpty()) {
							name = prefix+":"+parser.getAttributeName(i);
						} else {
							name = parser.getAttributeName(i);
						}
						tag.setAttribute(name,parser.getAttributeValue(i));
					}
					if (xmlns != null) {
						tag.setAttribute("xmlns", xmlns);
					}
					return tag;
				} else if (parser.getEventType() == XmlPullParser.END_TAG) {
					return Tag.end(parser.getName());
				} else if (parser.getEventType() == XmlPullParser.TEXT) {
					return Tag.no(parser.getText());
				}
			}

		} catch (Throwable throwable) {
			throw new IOException("xml parser mishandled "+throwable.getClass().getSimpleName()+"("+throwable.getMessage()+")", throwable);
		}
		return null;
	}

	public Element readElement(Tag currentTag) throws IOException {
		Element element = new Element(currentTag.getName());
		element.setAttributes(currentTag.getAttributes());
		Tag nextTag = this.readTag();
		if (nextTag == null) {
			throw new IOException("interrupted mid tag");
		}
		while (!nextTag.isEnd(element.getName())) {
			if (nextTag.isNo()) {
				element.addChild(new TextNode(nextTag.getName()));
			} else {
				Element child = this.readElement(nextTag);
				element.addChild(child);
			}
			nextTag = this.readTag();
			if (nextTag == null) {
				throw new IOException("interrupted mid tag");
			}
		}
		return element;
	}
}
