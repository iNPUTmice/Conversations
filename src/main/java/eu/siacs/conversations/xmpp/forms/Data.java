package eu.siacs.conversations.xmpp.forms;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;

public class Data extends Element {

	public static final String FORM_TYPE = "FORM_TYPE";

	public Data() {
		super("x");
		this.setAttribute("xmlns", Namespace.DATA);
	}

	public List<Field> getFields() {
		ArrayList<Field> fields = new ArrayList<Field>();
		for(Element child : getChildren()) {
			if (child.getName().equals("field")
					&& !FORM_TYPE.equals(child.getAttribute("var"))) {
				fields.add(Field.parse(child));
			}
		}
		return fields;
	}

	public Field getFieldByName(String needle) {
		for(Element child : getChildren()) {
			if (child.getName().equals("field")
					&& needle.equals(child.getAttribute("var"))) {
				return Field.parse(child);
			}
		}
		return null;
	}

	public Field put(String name, String value) {
		Field field = getFieldByName(name);
		if (field == null) {
			field = new Field(name);
			this.addChild(field);
		}
		field.setValue(value);
		return field;
	}

	public void put(String name, Collection<String> values) {
		Field field = getFieldByName(name);
		if (field == null) {
			field = new Field(name);
			this.addChild(field);
		}
		field.setValues(values);
	}

	public void submit(Bundle options) {
		for (Field field : getFields()) {
			if (options.containsKey(field.getFieldName())) {
				field.setValue(options.getString(field.getFieldName()));
			}
		}
		submit();
	}

	public void submit() {
		this.setAttribute("type","submit");
		removeUnnecessaryChildren();
		for(Field field : getFields()) {
			field.removeNonValueChildren();
		}
	}

	private void removeUnnecessaryChildren() {
		setChildren(getChildren().stream().filter(element -> element.getName().equals("field") || element.getName().equals("title")).collect(Collectors.toList()));
	}

	public static Data parse(Element element) {
		Data data = new Data();
		data.setAttributes(element.getAttributes());
		data.setChildren(element.getChildren());
		return data;
	}

	public void setFormType(String formType) {
		Field field = this.put(FORM_TYPE, formType);
		field.setAttribute("type","hidden");
	}

	public String getFormType() {
		String type = getValue(FORM_TYPE);
		return type == null ? "" : type;
	}

	public String getValue(String name) {
		Field field = this.getFieldByName(name);
		return field == null ? null : field.getValue();
	}

	public String getTitle() {
		return findChildContent("title");
	}


	public static Data create(String type, Bundle bundle) {
		Data data = new Data();
		data.setFormType(type);
		data.setAttribute("type","submit");
		for(String key : bundle.keySet()) {
			data.put(key,bundle.getString(key));
		}
		return data;
	}

}
