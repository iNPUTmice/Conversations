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

package eu.siacs.conversations.ui.adapter;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Filter;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import eu.siacs.conversations.entities.PresenceTemplate;


public class PresenceTemplateAdapter extends ArrayAdapter<PresenceTemplate> {

	private final List<PresenceTemplate> templates;

	private final Filter filter = new Filter() {

		@Override
		protected FilterResults performFiltering(CharSequence constraint) {
			FilterResults results = new FilterResults();
			if (constraint == null || constraint.length() == 0) {
				results.values = new ArrayList<>(templates);
				results.count = templates.size();
			} else {
				ArrayList<PresenceTemplate> suggestions = new ArrayList<>();
				final String needle = constraint.toString().trim().toLowerCase(Locale.getDefault());
				for(PresenceTemplate template : templates) {
					final String lc = template.getStatusMessage().toLowerCase(Locale.getDefault());
					if (needle.isEmpty() || lc.contains(needle)) {
						suggestions.add(template);
					}
				}
				results.values = suggestions;
				results.count = suggestions.size();
			}
			return results;
		}

		@Override
		protected void publishResults(CharSequence constraint, FilterResults results) {
			ArrayList filteredList = (ArrayList) results.values;
			clear();
			for (Object c : filteredList) {
				add((PresenceTemplate) c);
			}
			notifyDataSetChanged();
		}
	};

	public PresenceTemplateAdapter(@NonNull Context context, int resource, @NonNull List<PresenceTemplate> templates) {
		super(context, resource, new ArrayList<>());
		this.templates = new ArrayList<>(templates);
	}

	@Override
	@NonNull
	public Filter getFilter() {
		return this.filter;
	}
}
