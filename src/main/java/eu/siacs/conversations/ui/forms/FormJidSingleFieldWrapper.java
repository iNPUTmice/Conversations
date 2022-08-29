package eu.siacs.conversations.ui.forms;

import android.content.Context;
import android.text.InputType;

import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.forms.Field;

public class FormJidSingleFieldWrapper extends FormTextFieldWrapper {

	protected FormJidSingleFieldWrapper(Context context, Field field) {
		super(context, field);
		editText.setInputType(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
		editText.setHint(R.string.account_settings_example_jabber_id);
	}

	@Override
	public boolean validates() {
		String value = getValue();
		if (!value.isEmpty()) {
			try {
				Jid.of(value);
			} catch (IllegalArgumentException e) {
				editText.setError(context.getString(R.string.invalid_jid));
				editText.requestFocus();
				return false;
			}
		}
		return super.validates();
	}

	@Override
	protected void setValues(List<String> values) {
		StringBuilder builder = new StringBuilder();
		for(String value : values) {
			builder.append(value);
		}
		editText.setText(builder.toString());
	}
}
