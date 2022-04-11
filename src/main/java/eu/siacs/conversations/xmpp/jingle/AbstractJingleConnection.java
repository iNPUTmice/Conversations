package eu.siacs.conversations.xmpp.jingle;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.jingle.stanzas.JinglePacket;

public abstract class AbstractJingleConnection {

    public static final String JINGLE_MESSAGE_PROPOSE_ID_PREFIX = "jm-propose-";
    public static final String JINGLE_MESSAGE_PROCEED_ID_PREFIX = "jm-proceed-";

    final JingleConnectionManager jingleConnectionManager;
    protected final XmppConnectionService xmppConnectionService;
    protected final Id id;
    private final Jid initiator;

    AbstractJingleConnection(final JingleConnectionManager jingleConnectionManager, final Id id, final Jid initiator) {
        this.jingleConnectionManager = jingleConnectionManager;
        this.xmppConnectionService = jingleConnectionManager.getXmppConnectionService();
        this.id = id;
        this.initiator = initiator;
    }

    boolean isInitiator() {
        return initiator.equals(id.account.getJid());
    }

    abstract void deliverPacket(JinglePacket jinglePacket);

    public Id getId() {
        return id;
    }

    abstract void notifyRebound();


    public static class Id implements OngoingRtpSession {
        public final Account account;
        public final Jid with;
        public final String sessionId;

        private Id(final Account account, final Jid with, final String sessionId) {
            Preconditions.checkNotNull(account);
            Preconditions.checkNotNull(with);
            Preconditions.checkNotNull(sessionId);
            this.account = account;
            this.with = with;
            this.sessionId = sessionId;
        }

        public static Id of(Account account, JinglePacket jinglePacket) {
            return new Id(account, jinglePacket.getFrom(), jinglePacket.getSessionId());
        }

        public static Id of(Account account, Jid with, final String sessionId) {
            return new Id(account, with, sessionId);
        }

        public static Id of(Account account, Jid with) {
            return new Id(account, with, JingleConnectionManager.nextRandomId());
        }

        public static Id of(Message message) {
            return new Id(
                    message.getConversation().getAccount(),
                    message.getCounterpart(),
                    JingleConnectionManager.nextRandomId()
            );
        }

        public Contact getContact() {
            return account.getRoster().getContact(with);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Id id = (Id) o;
            return Objects.equal(account.getUuid(), id.account.getUuid()) &&
                    Objects.equal(with, id.with) &&
                    Objects.equal(sessionId, id.sessionId);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(account.getUuid(), with, sessionId);
        }

        @Override
        public Account getAccount() {
            return account;
        }

        @Override
        public Jid getWith() {
            return with;
        }

        @Override
        public String getSessionId() {
            return sessionId;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("account", account.getJid())
                    .add("with", with)
                    .add("sessionId", sessionId)
                    .toString();
        }
    }


    public enum State {
        NULL, //default value; nothing has been sent or received yet
        PROPOSED,
        ACCEPTED,
        PROCEED,
        REJECTED,
        REJECTED_RACED, //used when we want to reject but haven’t received session init yet
        RETRACTED,
        RETRACTED_RACED, //used when receiving a retract after we already asked to proceed
        SESSION_INITIALIZED, //equal to 'PENDING'
        SESSION_INITIALIZED_PRE_APPROVED,
        SESSION_ACCEPTED, //equal to 'ACTIVE'
        TERMINATED_SUCCESS, //equal to 'ENDED' (after successful call) ui will just close
        TERMINATED_DECLINED_OR_BUSY, //equal to 'ENDED' (after other party declined the call)
        TERMINATED_CONNECTIVITY_ERROR, //equal to 'ENDED' (but after network failures; ui will display retry button)
        TERMINATED_CANCEL_OR_TIMEOUT, //more or less the same as retracted; caller pressed end call before session was accepted
        TERMINATED_APPLICATION_FAILURE,
        TERMINATED_SECURITY_ERROR
    }
}
