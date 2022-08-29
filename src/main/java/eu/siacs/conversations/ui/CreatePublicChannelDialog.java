package eu.siacs.conversations.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.DialogFragment;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.CreatePublicChannelDialogBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.adapter.KnownHostsAdapter;
import eu.siacs.conversations.ui.interfaces.OnBackendConnected;
import eu.siacs.conversations.ui.util.DelayedHintHelper;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;

public class CreatePublicChannelDialog extends DialogFragment implements OnBackendConnected {

    private static final char[] FORBIDDEN = new char[]{'\u0022','&','\'','/',':','<','>','@'};

    private static final String ACCOUNTS_LIST_KEY = "activated_accounts_list";
    private CreatePublicChannelDialogListener mListener;
    private KnownHostsAdapter knownHostsAdapter;
    private boolean jidWasModified = false;
    private boolean nameEntered = false;
    private boolean skipTetxWatcher = false;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static CreatePublicChannelDialog newInstance(List<String> accounts) {
        CreatePublicChannelDialog dialog = new CreatePublicChannelDialog();
        Bundle bundle = new Bundle();
        bundle.putStringArrayList(ACCOUNTS_LIST_KEY, (ArrayList<String>) accounts);
        dialog.setArguments(bundle);
        return dialog;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setRetainInstance(true);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        jidWasModified = savedInstanceState != null && savedInstanceState.getBoolean("jid_was_modified_false", false);
        nameEntered = savedInstanceState != null && savedInstanceState.getBoolean("name_entered", false);
        final AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle(R.string.create_public_channel);
        final CreatePublicChannelDialogBinding binding = DataBindingUtil.inflate(getActivity().getLayoutInflater(), R.layout.create_public_channel_dialog, null, false);
        binding.account.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateJidSuggestion(binding);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
        binding.jid.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                if (skipTetxWatcher) {
                    return;
                }
                if (jidWasModified) {
                    jidWasModified = !TextUtils.isEmpty(s);
                } else {
                    jidWasModified = !s.toString().equals(getJidSuggestion(binding));
                }
            }
        });
        updateInputs(binding,false);
        ArrayList<String> mActivatedAccounts = getArguments().getStringArrayList(ACCOUNTS_LIST_KEY);
        StartConversationActivity.populateAccountSpinner(getActivity(), mActivatedAccounts, binding.account);
        builder.setView(binding.getRoot());
        builder.setPositiveButton(nameEntered ? R.string.create : R.string.next, null);
        builder.setNegativeButton(nameEntered ? R.string.back : R.string.cancel, null);
        DelayedHintHelper.setHint(R.string.channel_bare_jid_example, binding.jid);
        this.knownHostsAdapter = new KnownHostsAdapter(getActivity(), R.layout.simple_list_item);
        binding.jid.setAdapter(knownHostsAdapter);
        final AlertDialog dialog = builder.create();
        binding.groupChatName.setOnEditorActionListener((v, actionId, event) -> {
            submit(dialog, binding);
            return true;
        });
        dialog.setOnShowListener(dialogInterface -> {
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> goBack(dialog, binding));
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> submit(dialog, binding));
        });
        return dialog;
    }

    private void updateJidSuggestion(CreatePublicChannelDialogBinding binding) {
        if (jidWasModified) {
            return;
        }
        String jid = getJidSuggestion(binding);
        skipTetxWatcher = true;
        binding.jid.setText(jid);
        skipTetxWatcher = false;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("jid_was_modified",jidWasModified);
        outState.putBoolean("name_entered", nameEntered);
        super.onSaveInstanceState(outState);
    }

    private static String getJidSuggestion(CreatePublicChannelDialogBinding binding) {
        final Account account = StartConversationActivity.getSelectedAccount(binding.getRoot().getContext(), binding.account);
        final XmppConnection connection = account == null ? null : account.getXmppConnection();
        if (connection == null) {
            return "";
        }
        final Editable nameText = binding.groupChatName.getText();
        final String name = nameText == null ? "" : nameText.toString().trim();
        final String domain = connection.getMucServer();
        if (domain == null) {
            return "";
        }
        final String localpart = clean(name);
        if (TextUtils.isEmpty(localpart)) {
            return "";
        } else {
            try {
                return Jid.of(localpart, domain, null).toEscapedString();
            } catch (IllegalArgumentException e) {
                return Jid.of(CryptoHelper.pronounceable(RANDOM), domain, null).toEscapedString();
            }
        }
    }

    private static String clean(String name) {
        for(char c : FORBIDDEN) {
            name = name.replace(String.valueOf(c),"");
        }
        return name.replaceAll("\\s+","-");
    }

    private void goBack(AlertDialog dialog, CreatePublicChannelDialogBinding binding) {
        if (nameEntered) {
            nameEntered = false;
            updateInputs(binding, true);
            updateButtons(dialog);
        } else {
            dialog.dismiss();
        }
    }

    private void submit(AlertDialog dialog, CreatePublicChannelDialogBinding binding) {
        final Context context = binding.getRoot().getContext();
        final Editable nameText = binding.groupChatName.getText();
        final String name = nameText == null ? "" : nameText.toString().trim();
        final Editable addressText = binding.jid.getText();
        final String address = addressText == null ? "" : addressText.toString().trim();
        if (nameEntered) {
            binding.nameLayout.setError(null);
            if (address.isEmpty()) {
                binding.xmppAddressLayout.setError(context.getText(R.string.please_enter_xmpp_address));
            } else {
                final Jid jid;
                try {
                    jid = Jid.ofEscaped(address);
                } catch (IllegalArgumentException e) {
                    binding.xmppAddressLayout.setError(context.getText(R.string.invalid_jid));
                    return;
                }
                final Account account = StartConversationActivity.getSelectedAccount(context, binding.account);
                if (account == null) {
                    return;
                }
                final XmppConnectionService service = ((XmppActivity )context).xmppConnectionService;
                if (service != null && service.findFirstMuc(jid) != null) {
                    binding.xmppAddressLayout.setError(context.getString(R.string.channel_already_exists));
                    return;
                }
                mListener.onCreatePublicChannel(account, name, jid);
                dialog.dismiss();
            }
        } else {
            binding.xmppAddressLayout.setError(null);
            if (name.isEmpty()) {
                binding.nameLayout.setError(context.getText(R.string.please_enter_name));
            } else if (StartConversationActivity.isValidJid(name)){
                binding.nameLayout.setError(context.getText(R.string.this_is_an_xmpp_address));
            } else {
                binding.nameLayout.setError(null);
                nameEntered = true;
                updateInputs(binding, true);
                updateButtons(dialog);
                binding.jid.setText("");
                binding.jid.append(getJidSuggestion(binding));
            }
        }
    }


    private void updateInputs(CreatePublicChannelDialogBinding binding, boolean requestFocus) {
        binding.xmppAddressLayout.setVisibility(nameEntered ? View.VISIBLE : View.GONE);
        binding.nameLayout.setVisibility(nameEntered ? View.GONE : View.VISIBLE);
        if (!requestFocus) {
            return;
        }
        if (nameEntered) {
            binding.xmppAddressLayout.requestFocus();
        } else {
            binding.nameLayout.requestFocus();
        }
    }

    private void updateButtons(AlertDialog dialog) {
        final Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        final Button negative = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        positive.setText(nameEntered ? R.string.create : R.string.next);
        negative.setText(nameEntered ? R.string.back : R.string.cancel);
    }

    @Override
    public void onBackendConnected() {
        refreshKnownHosts();
    }

    private void refreshKnownHosts() {
        Activity activity = getActivity();
        if (activity instanceof XmppActivity) {
            Collection<String> hosts = ((XmppActivity) activity).xmppConnectionService.getKnownConferenceHosts();
            this.knownHostsAdapter.refresh(hosts);
        }
    }

    public interface CreatePublicChannelDialogListener {
        void onCreatePublicChannel(Account account, String name, Jid address);
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            mListener = (CreatePublicChannelDialogListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString()
                    + " must implement CreateConferenceDialogListener");
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        final Activity activity = getActivity();
        if (activity instanceof XmppActivity && ((XmppActivity) activity).xmppConnectionService != null) {
            refreshKnownHosts();
        }
    }

    @Override
    public void onDestroyView() {
        Dialog dialog = getDialog();
        if (dialog != null && getRetainInstance()) {
            dialog.setDismissMessage(null);
        }
        super.onDestroyView();
    }
}
