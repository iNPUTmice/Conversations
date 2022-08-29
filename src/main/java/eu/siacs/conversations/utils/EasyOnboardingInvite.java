package eu.siacs.conversations.utils;

import android.os.Parcel;
import android.os.Parcelable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import java.util.Collections;
import java.util.List;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.XmppConnection;

public class EasyOnboardingInvite implements Parcelable {

    private final String domain;
    private final String uri;
    private final String landingUrl;

    protected EasyOnboardingInvite(Parcel in) {
        domain = in.readString();
        uri = in.readString();
        landingUrl = in.readString();
    }

    public EasyOnboardingInvite(String domain, String uri, String landingUrl) {
        this.domain = domain;
        this.uri = uri;
        this.landingUrl = landingUrl;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(domain);
        dest.writeString(uri);
        dest.writeString(landingUrl);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<EasyOnboardingInvite> CREATOR = new Creator<EasyOnboardingInvite>() {
        @Override
        public EasyOnboardingInvite createFromParcel(Parcel in) {
            return new EasyOnboardingInvite(in);
        }

        @Override
        public EasyOnboardingInvite[] newArray(int size) {
            return new EasyOnboardingInvite[size];
        }
    };

    public static boolean anyHasSupport(final XmppConnectionService service) {
        if (QuickConversationsService.isQuicksy()) {
            return false;
        }
        return getSupportingAccounts(service).size() > 0;

    }

    public static List<Account> getSupportingAccounts(final XmppConnectionService service) {
        final ImmutableList.Builder<Account> supportingAccountsBuilder = new ImmutableList.Builder<>();
        final List<Account> accounts = service == null ? Collections.emptyList() : service.getAccounts();
        for(Account account : accounts) {
            final XmppConnection xmppConnection = account.getXmppConnection();
            if (xmppConnection != null && xmppConnection.getFeatures().easyOnboardingInvites()) {
                supportingAccountsBuilder.add(account);
            }
        }
        return supportingAccountsBuilder.build();
    }


    public String getShareableLink() {
        return Strings.isNullOrEmpty(landingUrl) ? uri : landingUrl;
    }


    public String getDomain() {
        return domain;
    }

    public interface OnInviteRequested {
        void inviteRequested(EasyOnboardingInvite invite);
        void inviteRequestFailed(String message);
    }
}
