package eu.siacs.conversations.xmpp.forms;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import eu.siacs.conversations.xml.Element;

public class Field extends Element {

	public Field(String name) {
		super("field");
		this.setAttribute("var",name);
	}

	private Field() {
		super("field");
	}

	public String getFieldName() {
		return this.getAttribute("var");
	}

	public void setValue(String value) {
		setChildren(List.of(new Element("value").setContent(value)));
	}

	public void setValues(Collection<String> values) {
		setChildren(values.stream().map(val -> new Element("value").setContent(val)).collect(Collectors.toList()));
	}

	public void removeNonValueChildren() {
		setChildren(getChildren().stream().filter(element -> element.getName().equals("value")).collect(Collectors.toList()));
	}

	public static Field parse(Element element) {
		Field field = new Field();
		field.setAttributes(element.getAttributes());
		field.setChildren(element.getChildren());
		return field;
	}

	public String getValue() {
		return findChildContent("value");
	}

	public List<String> getValues() {
		List<String> values = new ArrayList<>();
		for(Element child : getChildren()) {
			if ("value".equals(child.getName())) {
				values.add(child.getContent());
			}
		}
		return values;
	}

	public String getLabel() {
		return getAttribute("label");
	}

	public String getType() {
		return getAttribute("type");
	}

	public boolean isRequired() {
		return hasChild("required");
	}
}
