package eu.siacs.conversations.ui.forms;

import android.content.Context;
import android.widget.CheckBox;
import android.widget.CompoundButton;

import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.xmpp.forms.Field;

public class FormBooleanFieldWrapper extends FormFieldWrapper {

	protected CheckBox checkBox;

	protected FormBooleanFieldWrapper(Context context, Field field) {
		super(context, field);
		checkBox = view.findViewById(R.id.field);
		checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				checkBox.setError(null);
				invokeOnFormFieldValuesEdited();
			}
		});
	}

	@Override
	protected void setLabel(String label, boolean required) {
		CheckBox checkBox = view.findViewById(R.id.field);
		checkBox.setText(createSpannableLabelString(label, required));
	}

	@Override
	public List<String> getValues() {
		List<String> values = new ArrayList<>();
		values.add(Boolean.toString(checkBox.isChecked()));
		return values;
	}

	@Override
	protected void setValues(List<String> values) {
		if (values.size() == 0) {
			checkBox.setChecked(false);
		} else {
			checkBox.setChecked(Boolean.parseBoolean(values.get(0)));
		}
	}

	@Override
	public boolean validates() {
		if (checkBox.isChecked() || !field.isRequired()) {
			return true;
		} else {
			checkBox.setError(context.getString(R.string.this_field_is_required));
			checkBox.requestFocus();
			return false;
		}
	}

	@Override
	public boolean edited() {
		if (field.getValues().size() == 0) {
			return checkBox.isChecked();
		} else {
			return super.edited();
		}
	}

	@Override
	protected int getLayoutResource() {
		return R.layout.form_boolean;
	}

	@Override
	void setReadOnly(boolean readOnly) {
		checkBox.setEnabled(!readOnly);
	}
}
