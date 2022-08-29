package eu.siacs.conversations.services;

import android.util.Log;

import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.ReceiptRequest;
import eu.siacs.conversations.generator.AbstractGenerator;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnAdvancedStreamFeaturesLoaded;
import eu.siacs.conversations.xmpp.mam.MamReference;
import eu.siacs.conversations.xmpp.stanzas.IqPacket;
import eu.siacs.conversations.xmpp.stanzas.MessagePacket;

public class MessageArchiveService implements OnAdvancedStreamFeaturesLoaded {

    private final XmppConnectionService mXmppConnectionService;

    private final HashSet<Query> queries = new HashSet<>();
    private final ArrayList<Query> pendingQueries = new ArrayList<>();

    public enum Version {
        MAM_0("urn:xmpp:mam:0", true),
        MAM_1("urn:xmpp:mam:1", false),
        MAM_2("urn:xmpp:mam:2", false);

        public final boolean legacy;
        public final String namespace;

        Version(String namespace, boolean legacy) {
            this.namespace = namespace;
            this.legacy = legacy;
        }

        public static Version get(Account account) {
            return get(account, null);
        }

        public static Version get(Account account, Conversation conversation) {
            if (conversation == null || conversation.getMode() == Conversation.MODE_SINGLE) {
                return get(account.getXmppConnection().getFeatures().getAccountFeatures());
            } else {
                return get(conversation.getMucOptions().getFeatures());
            }
        }

        private static Version get(List<String> features) {
            final Version[] values = values();
            for (int i = values.length - 1; i >= 0; --i) {
                for (String feature : features) {
                    if (values[i].namespace.equals(feature)) {
                        return values[i];
                    }
                }
            }
            return MAM_0;
        }

        public static boolean has(List<String> features) {
            for (String feature : features) {
                for (Version version : values()) {
                    if (version.namespace.equals(feature)) {
                        return true;
                    }
                }
            }
            return false;
        }

        public static Element findResult(MessagePacket packet) {
            for (Version version : values()) {
                Element result = packet.findChild("result", version.namespace);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }

    }

    MessageArchiveService(final XmppConnectionService service) {
        this.mXmppConnectionService = service;
    }

    private void catchup(final Account account) {
        synchronized (this.queries) {
            for (Iterator<Query> iterator = this.queries.iterator(); iterator.hasNext(); ) {
                Query query = iterator.next();
                if (query.getAccount() == account) {
                    iterator.remove();
                }
            }
        }
        MamReference mamReference = MamReference.max(
                mXmppConnectionService.databaseBackend.getLastMessageReceived(account),
                mXmppConnectionService.databaseBackend.getLastClearDate(account)
        );
        mamReference = MamReference.max(mamReference, mXmppConnectionService.getAutomaticMessageDeletionDate());
        long endCatchup = account.getXmppConnection().getLastSessionEstablished();
        final Query query;
        if (mamReference.getTimestamp() == 0) {
            return;
        } else if (endCatchup - mamReference.getTimestamp() >= Config.MAM_MAX_CATCHUP) {
            long startCatchup = endCatchup - Config.MAM_MAX_CATCHUP;
            List<Conversation> conversations = mXmppConnectionService.getConversations();
            for (Conversation conversation : conversations) {
                if (conversation.getMode() == Conversation.MODE_SINGLE && conversation.getAccount() == account && startCatchup > conversation.getLastMessageTransmitted().getTimestamp()) {
                    this.query(conversation, startCatchup, true);
                }
            }
            query = new Query(account, new MamReference(startCatchup), 0);
        } else {
            query = new Query(account, mamReference, 0);
        }
        synchronized (this.queries) {
            this.queries.add(query);
        }
        this.execute(query);
    }

    void catchupMUC(final Conversation conversation) {
        if (conversation.getLastMessageTransmitted().getTimestamp() < 0 && conversation.countMessages() == 0) {
            query(conversation,
                    new MamReference(0),
                    0,
                    true);
        } else {
            query(conversation,
                    conversation.getLastMessageTransmitted(),
                    0,
                    true);
        }
    }

    public Query query(final Conversation conversation) {
        if (conversation.getLastMessageTransmitted().getTimestamp() < 0 && conversation.countMessages() == 0) {
            return query(conversation,
                    new MamReference(0),
                    System.currentTimeMillis(),
                    false);
        } else {
            return query(conversation,
                    conversation.getLastMessageTransmitted(),
                    conversation.getAccount().getXmppConnection().getLastSessionEstablished(),
                    false);
        }
    }

    public boolean isCatchingUp(Conversation conversation) {
        final Account account = conversation.getAccount();
        if (account.getXmppConnection().isWaitingForSmCatchup()) {
            return true;
        } else {
            synchronized (this.queries) {
                for (Query query : this.queries) {
                    if (query.getAccount() == account && query.isCatchup() && ((conversation.getMode() == Conversation.MODE_SINGLE && query.getWith() == null) || query.getConversation() == conversation)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public Query query(final Conversation conversation, long end, boolean allowCatchup) {
        return this.query(conversation, conversation.getLastMessageTransmitted(), end, allowCatchup);
    }

    public Query query(Conversation conversation, MamReference start, long end, boolean allowCatchup) {
        synchronized (this.queries) {
            final Query query;
            final MamReference startActual = MamReference.max(start, mXmppConnectionService.getAutomaticMessageDeletionDate());
            if (start.getTimestamp() == 0) {
                query = new Query(conversation, startActual, end, false);
                query.reference = conversation.getFirstMamReference();
            } else {
                if (allowCatchup) {
                    MamReference maxCatchup = MamReference.max(startActual, System.currentTimeMillis() - Config.MAM_MAX_CATCHUP);
                    if (maxCatchup.greaterThan(startActual)) {
                        Query reverseCatchup = new Query(conversation, startActual, maxCatchup.getTimestamp(), false);
                        this.queries.add(reverseCatchup);
                        this.execute(reverseCatchup);
                    }
                    query = new Query(conversation, maxCatchup, end, true);
                } else {
                    query = new Query(conversation, startActual, end, false);
                }
            }
            if (end != 0 && start.greaterThan(end)) {
                return null;
            }
            this.queries.add(query);
            this.execute(query);
            return query;
        }
    }

    void executePendingQueries(final Account account) {
        final List<Query> pending = new ArrayList<>();
        synchronized (this.pendingQueries) {
            for (Iterator<Query> iterator = this.pendingQueries.iterator(); iterator.hasNext(); ) {
                Query query = iterator.next();
                if (query.getAccount() == account) {
                    pending.add(query);
                    iterator.remove();
                }
            }
        }
        for (Query query : pending) {
            this.execute(query);
        }
    }

    private void execute(final Query query) {
        final Account account = query.getAccount();
        if (account.getStatus() == Account.State.ONLINE) {
            final Conversation conversation = query.getConversation();
            if (conversation != null && conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
                throw new IllegalStateException("Attempted to run MAM query for archived conversation");
            }
            Log.d(Config.LOGTAG, account.getJid().asBareJid().toString() + ": running mam query " + query.toString());
            final IqPacket packet = this.mXmppConnectionService.getIqGenerator().queryMessageArchiveManagement(query);
            this.mXmppConnectionService.sendIqPacket(account, packet, (a, p) -> {
                final Element fin = p.findChild("fin", query.version.namespace);
                if (p.getType() == IqPacket.TYPE.TIMEOUT) {
                    synchronized (this.queries) {
                        this.queries.remove(query);
                        if (query.hasCallback()) {
                            query.callback(false);
                        }
                    }
                } else if (p.getType() == IqPacket.TYPE.RESULT && fin != null) {
                    final boolean running;
                    synchronized (this.queries) {
                        running = this.queries.contains(query);
                    }
                    if (running) {
                        processFin(query, fin);
                    } else {
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": ignoring MAM iq result because query had been killed");
                    }
                } else if (p.getType() == IqPacket.TYPE.RESULT && query.isLegacy()) {
                    //do nothing
                } else {
                    Log.d(Config.LOGTAG, a.getJid().asBareJid().toString() + ": error executing mam: " + p.toString());
                    try {
                        finalizeQuery(query, true);
                    } catch (final IllegalStateException e) {
                        //ignored
                    }
                }
            });
        } else {
            synchronized (this.pendingQueries) {
                this.pendingQueries.add(query);
            }
        }
    }

    private void finalizeQuery(final Query query, boolean done) {
        synchronized (this.queries) {
            if (!this.queries.remove(query)) {
                throw new IllegalStateException("Unable to remove query from queries");
            }
        }
        final Conversation conversation = query.getConversation();
        if (conversation != null) {
            conversation.sort();
            conversation.setHasMessagesLeftOnServer(!done);
        } else {
            for (Conversation tmp : this.mXmppConnectionService.getConversations()) {
                if (tmp.getAccount() == query.getAccount()) {
                    tmp.sort();
                }
            }
        }
        if (query.hasCallback()) {
            query.callback(done);
        } else {
            this.mXmppConnectionService.updateConversationUi();
        }
    }

    boolean inCatchup(Account account) {
        synchronized (this.queries) {
            for (Query query : queries) {
                if (query.account == account && query.isCatchup() && query.getWith() == null) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isCatchupInProgress(Conversation conversation) {
        synchronized (this.queries) {
            for (Query query : queries) {
                if (query.account == conversation.getAccount() && query.isCatchup()) {
                    final Jid with = query.getWith() == null ? null : query.getWith().asBareJid();
                    if ((conversation.getMode() == Conversational.MODE_SINGLE && with == null) || (conversation.getJid().asBareJid().equals(with))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    boolean queryInProgress(Conversation conversation, XmppConnectionService.OnMoreMessagesLoaded callback) {
        synchronized (this.queries) {
            for (Query query : queries) {
                if (query.conversation == conversation) {
                    if (!query.hasCallback() && callback != null) {
                        query.setCallback(callback);
                    }
                    return true;
                }
            }
            return false;
        }
    }

    public boolean queryInProgress(Conversation conversation) {
        return queryInProgress(conversation, null);
    }

    public void processFinLegacy(Element fin, Jid from) {
        Query query = findQuery(fin.getAttribute("queryid"));
        if (query != null && query.validFrom(from)) {
            processFin(query, fin);
        }
    }

    private void processFin(Query query, Element fin) {
        boolean complete = fin.getAttributeAsBoolean("complete");
        Element set = fin.findChild("set", "http://jabber.org/protocol/rsm");
        Element last = set == null ? null : set.findChild("last");
        String count = set == null ? null : set.findChildContent("count");
        Element first = set == null ? null : set.findChild("first");
        Element relevant = query.getPagingOrder() == PagingOrder.NORMAL ? last : first;
        boolean abort = (!query.isCatchup() && query.getTotalCount() >= Config.PAGE_SIZE) || query.getTotalCount() >= Config.MAM_MAX_MESSAGES;
        if (query.getConversation() != null) {
            query.getConversation().setFirstMamReference(first == null ? null : first.getContent());
        }
        if (complete || relevant == null || abort) {
            //TODO: FIX done logic to look at complete. using count is probably unreliable because it can be ommited and doesn’t work with paging.
            boolean done;
            if (query.isCatchup()) {
                done = false;
            } else {
                if (count != null) {
                    try {
                        done = Integer.parseInt(count) <= query.getTotalCount();
                    } catch (NumberFormatException e) {
                        done = false;
                    }
                } else {
                    done = query.getTotalCount() == 0;
                }
            }
            done = done || (query.getActualMessageCount() == 0 && !query.isCatchup());
            this.finalizeQuery(query, done);

            Log.d(Config.LOGTAG, query.getAccount().getJid().asBareJid() + ": finished mam after " + query.getTotalCount() + "(" + query.getActualMessageCount() + ") messages. messages left=" + !done + " count=" + count);
            if (query.isCatchup() && query.getActualMessageCount() > 0) {
                mXmppConnectionService.getNotificationService().finishBacklog(true, query.getAccount());
            }
            processPostponed(query);
        } else {
            final Query nextQuery;
            if (query.getPagingOrder() == PagingOrder.NORMAL) {
                nextQuery = query.next(last == null ? null : last.getContent());
            } else {
                nextQuery = query.prev(first == null ? null : first.getContent());
            }
            this.execute(nextQuery);
            this.finalizeQuery(query, false);
            synchronized (this.queries) {
                this.queries.add(nextQuery);
            }
        }
    }

    void kill(final Conversation conversation) {
        final ArrayList<Query> toBeKilled = new ArrayList<>();
        synchronized (this.pendingQueries) {
            for (final Iterator<Query> iterator = this.pendingQueries.iterator(); iterator.hasNext(); ) {
                final Query query = iterator.next();
                if (query.getConversation() == conversation) {
                    iterator.remove();
                    Log.d(Config.LOGTAG, conversation.getAccount().getJid().asBareJid() + ": killed pending MAM query for archived conversation");
                }
            }
        }
        synchronized (this.queries) {
            for (final Query q : queries) {
                if (q.conversation == conversation) {
                    toBeKilled.add(q);
                }
            }
        }
        for (final Query q : toBeKilled) {
            kill(q);
        }
    }

    private void kill(Query query) {
        Log.d(Config.LOGTAG, query.getAccount().getJid().asBareJid() + ": killing mam query prematurely");
        query.callback = null;
        this.finalizeQuery(query, false);
        if (query.isCatchup() && query.getActualMessageCount() > 0) {
            mXmppConnectionService.getNotificationService().finishBacklog(true, query.getAccount());
        }
        this.processPostponed(query);
    }

    private void processPostponed(Query query) {
        query.account.getAxolotlService().processPostponed();
        query.pendingReceiptRequests.removeAll(query.receiptRequests);
        Log.d(Config.LOGTAG, query.getAccount().getJid().asBareJid() + ": found " + query.pendingReceiptRequests.size() + " pending receipt requests");
        Iterator<ReceiptRequest> iterator = query.pendingReceiptRequests.iterator();
        while (iterator.hasNext()) {
            ReceiptRequest rr = iterator.next();
            mXmppConnectionService.sendMessagePacket(query.account, mXmppConnectionService.getMessageGenerator().received(query.account, rr.getJid(), rr.getId()));
            iterator.remove();
        }
    }

    public Query findQuery(String id) {
        if (id == null) {
            return null;
        }
        synchronized (this.queries) {
            for (Query query : this.queries) {
                if (query.getQueryId().equals(id)) {
                    return query;
                }
            }
            return null;
        }
    }

    @Override
    public void onAdvancedStreamFeaturesAvailable(Account account) {
        if (account.getXmppConnection() != null && account.getXmppConnection().getFeatures().mam()) {
            this.catchup(account);
        }
    }

    public enum PagingOrder {
        NORMAL,
        REVERSE
    }

    public class Query {
        private HashSet<ReceiptRequest> pendingReceiptRequests = new HashSet<>();
        private HashSet<ReceiptRequest> receiptRequests = new HashSet<>();
        private int totalCount = 0;
        private int actualCount = 0;
        private int actualInThisQuery = 0;
        private long start;
        private final long end;
        private final String queryId;
        private String reference = null;
        private final Account account;
        private Conversation conversation;
        private PagingOrder pagingOrder = PagingOrder.NORMAL;
        private XmppConnectionService.OnMoreMessagesLoaded callback = null;
        private boolean catchup = true;
        public final Version version;


        Query(Conversation conversation, MamReference start, long end, boolean catchup) {
            this(conversation.getAccount(), Version.get(conversation.getAccount(), conversation), catchup ? start : start.timeOnly(), end);
            this.conversation = conversation;
            this.pagingOrder = catchup ? PagingOrder.NORMAL : PagingOrder.REVERSE;
            this.catchup = catchup;
        }

        Query(Account account, MamReference start, long end) {
            this(account, Version.get(account), start, end);
        }

        Query(Account account, Version version, MamReference start, long end) {
            this.account = account;
            if (start.getReference() != null) {
                this.reference = start.getReference();
            } else {
                this.start = start.getTimestamp();
            }
            this.end = end;
            this.queryId = new BigInteger(50, mXmppConnectionService.getRNG()).toString(32);
            this.version = version;
        }

        private Query page(String reference) {
            Query query = new Query(this.account, this.version, new MamReference(this.start, reference), this.end);
            query.conversation = conversation;
            query.totalCount = totalCount;
            query.actualCount = actualCount;
            query.pendingReceiptRequests = pendingReceiptRequests;
            query.receiptRequests = receiptRequests;
            query.callback = callback;
            query.catchup = catchup;
            return query;
        }

        public void removePendingReceiptRequest(ReceiptRequest receiptRequest) {
            if (!this.pendingReceiptRequests.remove(receiptRequest)) {
                this.receiptRequests.add(receiptRequest);
            }
        }

        public void addPendingReceiptRequest(ReceiptRequest receiptRequest) {
            this.pendingReceiptRequests.add(receiptRequest);
        }

        public boolean isLegacy() {
            return version.legacy;
        }

        public boolean safeToExtractTrueCounterpart() {
            return muc() && !isLegacy();
        }

        public Query next(String reference) {
            Query query = page(reference);
            query.pagingOrder = PagingOrder.NORMAL;
            return query;
        }

        Query prev(String reference) {
            Query query = page(reference);
            query.pagingOrder = PagingOrder.REVERSE;
            return query;
        }

        public String getReference() {
            return reference;
        }

        public PagingOrder getPagingOrder() {
            return this.pagingOrder;
        }

        public String getQueryId() {
            return queryId;
        }

        public Jid getWith() {
            return conversation == null ? null : conversation.getJid().asBareJid();
        }

        public boolean muc() {
            return conversation != null && conversation.getMode() == Conversation.MODE_MULTI;
        }

        public long getStart() {
            return start;
        }

        public boolean isCatchup() {
            return catchup;
        }

        public void setCallback(XmppConnectionService.OnMoreMessagesLoaded callback) {
            this.callback = callback;
        }

        public void callback(boolean done) {
            if (this.callback != null) {
                this.callback.onMoreMessagesLoaded(actualCount, conversation);
                if (done) {
                    this.callback.informUser(R.string.no_more_history_on_server);
                }
            }
        }

        public long getEnd() {
            return end;
        }

        public Conversation getConversation() {
            return conversation;
        }

        public Account getAccount() {
            return this.account;
        }

        public void incrementMessageCount() {
            this.totalCount++;
        }

        public void incrementActualMessageCount() {
            this.actualInThisQuery++;
            this.actualCount++;
        }

        int getTotalCount() {
            return this.totalCount;
        }

        int getActualMessageCount() {
            return this.actualCount;
        }

        public int getActualInThisQuery() {
            return this.actualInThisQuery;
        }

        public boolean validFrom(Jid from) {
            if (muc()) {
                return getWith().equals(from);
            } else {
                return (from == null) || account.getJid().asBareJid().equals(from.asBareJid());
            }
        }

        @NotNull
        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            if (this.muc()) {
                builder.append("to=");
                builder.append(this.getWith().toString());
            } else {
                builder.append("with=");
                if (this.getWith() == null) {
                    builder.append("*");
                } else {
                    builder.append(getWith().toString());
                }
            }
            if (this.start != 0) {
                builder.append(", start=");
                builder.append(AbstractGenerator.getTimestamp(this.start));
            }
            if (this.end != 0) {
                builder.append(", end=");
                builder.append(AbstractGenerator.getTimestamp(this.end));
            }
            builder.append(", order=").append(pagingOrder.toString());
            if (this.reference != null) {
                if (this.pagingOrder == PagingOrder.NORMAL) {
                    builder.append(", after=");
                } else {
                    builder.append(", before=");
                }
                builder.append(this.reference);
            }
            builder.append(", catchup=").append(catchup);
            builder.append(", ns=").append(version.namespace);
            return builder.toString();
        }

        boolean hasCallback() {
            return this.callback != null;
        }
    }
}
