package eu.siacs.conversations.xmpp.jingle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import android.util.Log;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.OnIqPacketReceived;
import eu.siacs.conversations.xmpp.jingle.stanzas.Content;
import eu.siacs.conversations.xmpp.jingle.stanzas.JinglePacket;
import eu.siacs.conversations.xmpp.jingle.stanzas.Reason;
import eu.siacs.conversations.xmpp.stanzas.IqPacket;

public class JingleConnection {

	private JingleConnectionManager mJingleConnectionManager;
	private XmppConnectionService mXmppConnectionService;
	
	public static final int STATUS_INITIATED = 0;
	public static final int STATUS_ACCEPTED = 1;
	public static final int STATUS_TERMINATED = 2;
	public static final int STATUS_CANCELED = 3;
	public static final int STATUS_FINISHED = 4;
	public static final int STATUS_TRANSMITTING = 5;
	public static final int STATUS_FAILED = 99;
	
	private int status = -1;
	private Message message;
	private String sessionId;
	private Account account;
	private String initiator;
	private String responder;
	private List<JingleCandidate> candidates = new ArrayList<JingleCandidate>();
	private HashMap<String, SocksConnection> connections = new HashMap<String, SocksConnection>();
	
	private String transportId;
	private Element fileOffer;
	private JingleFile file = null;
	
	private boolean receivedCandidateError = false;
	
	private OnIqPacketReceived responseListener = new OnIqPacketReceived() {
		
		@Override
		public void onIqPacketReceived(Account account, IqPacket packet) {
			if (packet.getType() == IqPacket.TYPE_ERROR) {
				mXmppConnectionService.markMessage(message, Message.STATUS_SEND_FAILED);
				status = STATUS_FAILED;
			}
		}
	};
	
	public JingleConnection(JingleConnectionManager mJingleConnectionManager) {
		this.mJingleConnectionManager = mJingleConnectionManager;
		this.mXmppConnectionService = mJingleConnectionManager.getXmppConnectionService();
	}
	
	public String getSessionId() {
		return this.sessionId;
	}
	
	public String getAccountJid() {
		return this.account.getFullJid();
	}
	
	public String getCounterPart() {
		return this.message.getCounterpart();
	}
	
	public void deliverPacket(JinglePacket packet) {
		
		if (packet.isAction("session-terminate")) {
			Reason reason = packet.getReason();
			if (reason!=null) {
				if (reason.hasChild("cancel")) {
					this.cancel();
				} else if (reason.hasChild("success")) {
					this.finish();
				}
			} else {
				Log.d("xmppService","remote terminated for no reason");
				this.cancel();
			}
			} else if (packet.isAction("session-accept")) {
			accept(packet);
		} else if (packet.isAction("transport-info")) {
			transportInfo(packet);
		} else {
			Log.d("xmppService","packet arrived in connection. action was "+packet.getAction());
		}
	}
	
	public void init(Message message) {
		this.message = message;
		this.account = message.getConversation().getAccount();
		this.initiator = this.account.getFullJid();
		this.responder = this.message.getCounterpart();
		this.sessionId = this.mJingleConnectionManager.nextRandomId();
		if (this.candidates.size() > 0) {
			this.sendInitRequest();
		} else {
			this.mJingleConnectionManager.getPrimaryCandidate(account, new OnPrimaryCandidateFound() {
				
				@Override
				public void onPrimaryCandidateFound(boolean success, JingleCandidate candidate) {
					if (success) {
						mergeCandidate(candidate);
					}
					openOurCandidates();
					sendInitRequest();
				}
			});
		}
		
	}
	
	public void init(Account account, JinglePacket packet) {
		this.status = STATUS_INITIATED;
		Conversation conversation = this.mXmppConnectionService.findOrCreateConversation(account, packet.getFrom().split("/")[0], false);
		this.message = new Message(conversation, "receiving image file", Message.ENCRYPTION_NONE);
		this.message.setType(Message.TYPE_IMAGE);
		this.message.setStatus(Message.STATUS_RECIEVING);
		String[] fromParts = packet.getFrom().split("/");
		this.message.setPresence(fromParts[1]);
		this.account = account;
		this.initiator = packet.getFrom();
		this.responder = this.account.getFullJid();
		this.sessionId = packet.getSessionId();
		Content content = packet.getJingleContent();
		this.transportId = content.getTransportId();
		this.mergeCandidates(JingleCandidate.parse(content.getCanditates()));
		this.fileOffer = packet.getJingleContent().getFileOffer();
		if (fileOffer!=null) {
			this.file = this.mXmppConnectionService.getFileBackend().getJingleFile(message);
			Element fileSize = fileOffer.findChild("size");
			Element fileName = fileOffer.findChild("name");
			this.file.setExpectedSize(Long.parseLong(fileSize.getContent()));
			conversation.getMessages().add(message);
			this.mXmppConnectionService.databaseBackend.createMessage(message);
			if (this.mXmppConnectionService.convChangedListener!=null) {
				this.mXmppConnectionService.convChangedListener.onConversationListChanged();
			}
			if (this.file.getExpectedSize()>=this.mJingleConnectionManager.getAutoAcceptFileSize()) {
				Log.d("xmppService","auto accepting file from "+packet.getFrom());
				this.sendAccept();
			} else {
				Log.d("xmppService","not auto accepting new file offer with size: "+this.file.getExpectedSize()+" allowed size:"+this.mJingleConnectionManager.getAutoAcceptFileSize());
			}
		} else {
			Log.d("xmppService","no file offer was attached. aborting");
		}
	}
	
	private void sendInitRequest() {
		JinglePacket packet = this.bootstrapPacket("session-initiate");
		Content content = new Content();
		if (message.getType() == Message.TYPE_IMAGE) {
			content.setAttribute("creator", "initiator");
			content.setAttribute("name", "a-file-offer");
			this.file = this.mXmppConnectionService.getFileBackend().getJingleFile(message);
			content.setFileOffer(this.file);
			this.transportId = this.mJingleConnectionManager.nextRandomId();
			content.setCandidates(this.transportId,getCandidatesAsElements());
			packet.setContent(content);
			Log.d("xmppService",packet.toString());
			account.getXmppConnection().sendIqPacket(packet, this.responseListener);
			this.status = STATUS_INITIATED;
		}
	}
	
	private List<Element> getCandidatesAsElements() {
		List<Element> elements = new ArrayList<Element>();
		for(JingleCandidate c : this.candidates) {
			elements.add(c.toElement());
		}
		return elements;
	}
	
	private void sendAccept() {
		this.mJingleConnectionManager.getPrimaryCandidate(this.account, new OnPrimaryCandidateFound() {
			
			@Override
			public void onPrimaryCandidateFound(boolean success, JingleCandidate candidate) {
				Content content = new Content();
				content.setFileOffer(fileOffer);
				if (success) {
					if (!equalCandidateExists(candidate)) {
						mergeCandidate(candidate);
					}
				}
				openOurCandidates();
				content.setCandidates(transportId, getCandidatesAsElements());
				JinglePacket packet = bootstrapPacket("session-accept");
				packet.setContent(content);
				account.getXmppConnection().sendIqPacket(packet, new OnIqPacketReceived() {
					
					@Override
					public void onIqPacketReceived(Account account, IqPacket packet) {
						if (packet.getType() != IqPacket.TYPE_ERROR) {
							status = STATUS_ACCEPTED;
							connectNextCandidate();
						}
					}
				});
			}
		});
		
	}
	
	private JinglePacket bootstrapPacket(String action) {
		JinglePacket packet = new JinglePacket();
		packet.setAction(action);
		packet.setFrom(account.getFullJid());
		packet.setTo(this.message.getCounterpart()); //fixme, not right in all cases;
		packet.setSessionId(this.sessionId);
		packet.setInitiator(this.initiator);
		return packet;
	}
	
	private void accept(JinglePacket packet) {
		Log.d("xmppService","session-accept: "+packet.toString());
		Content content = packet.getJingleContent();
		mergeCandidates(JingleCandidate.parse(content.getCanditates()));
		this.status = STATUS_ACCEPTED;
		this.connectNextCandidate();
		IqPacket response = packet.generateRespone(IqPacket.TYPE_RESULT);
		account.getXmppConnection().sendIqPacket(response, null);
	}

	private void transportInfo(JinglePacket packet) {
		Content content = packet.getJingleContent();
		String cid = content.getUsedCandidate();
		IqPacket response = packet.generateRespone(IqPacket.TYPE_RESULT);
		if (cid!=null) {
			Log.d("xmppService","candidate used by counterpart:"+cid);
			JingleCandidate candidate = getCandidate(cid);
			candidate.flagAsUsedByCounterpart();
			if (status == STATUS_ACCEPTED) {
				this.connect();
			} else {
				Log.d("xmppService","ignoring because file is already in transmission");
			}
		} else if (content.hasCandidateError()) {
			Log.d("xmppService","received candidate error");
			this.receivedCandidateError = true;
			if (status == STATUS_ACCEPTED) {
				this.connect();
			}
		}
		account.getXmppConnection().sendIqPacket(response, null);
	}

	private void connect() {
		final SocksConnection connection = chooseConnection();
		this.status = STATUS_TRANSMITTING;
		final OnFileTransmitted callback = new OnFileTransmitted() {
			
			@Override
			public void onFileTransmitted(JingleFile file) {
				if (responder.equals(account.getFullJid())) {
					sendSuccess();
					mXmppConnectionService.markMessage(message, Message.STATUS_SEND);
				}
				Log.d("xmppService","sucessfully transmitted file. sha1:"+file.getSha1Sum());
			}
		};
		if (connection.isProxy()&&(connection.getCandidate().isOurs())) {
			Log.d("xmppService","candidate "+connection.getCandidate().getCid()+" was our proxy and needs activation");
			IqPacket activation = new IqPacket(IqPacket.TYPE_SET);
			activation.setTo(connection.getCandidate().getJid());
			activation.query("http://jabber.org/protocol/bytestreams").setAttribute("sid", this.getSessionId());
			activation.query().addChild("activate").setContent(this.getCounterPart());
			this.account.getXmppConnection().sendIqPacket(activation, new OnIqPacketReceived() {
				
				@Override
				public void onIqPacketReceived(Account account, IqPacket packet) {
					Log.d("xmppService","activation result: "+packet.toString());
					if (initiator.equals(account.getFullJid())) {
						Log.d("xmppService","we were initiating. sending file");
						connection.send(file,callback);
					} else {
						connection.receive(file,callback);
						Log.d("xmppService","we were responding. receiving file");
					}
				}
			});
		} else {
			if (initiator.equals(account.getFullJid())) {
				Log.d("xmppService","we were initiating. sending file");
				connection.send(file,callback);
			} else {
				Log.d("xmppService","we were responding. receiving file");
				connection.receive(file,callback);
			}
		}
	}
	
	private SocksConnection chooseConnection() {
		Log.d("xmppService","choosing connection from "+this.connections.size()+" possibilties");
		SocksConnection connection = null;
		Iterator<Entry<String, SocksConnection>> it = this.connections.entrySet().iterator();
	    while (it.hasNext()) {
	    	Entry<String, SocksConnection> pairs = it.next();
	    	SocksConnection currentConnection = pairs.getValue();
	    	Log.d("xmppService","comparing candidate: "+currentConnection.getCandidate().toString());
	        if (currentConnection.isEstablished()&&(currentConnection.getCandidate().isUsedByCounterpart()||(!currentConnection.getCandidate().isOurs()))) {
	        	Log.d("xmppService","is usable");
	        	if (connection==null) {
	        		connection = currentConnection;
	        	} else {
	        		if (connection.getCandidate().getPriority()<currentConnection.getCandidate().getPriority()) {
	        			connection = currentConnection;
	        		} else if (connection.getCandidate().getPriority()==currentConnection.getCandidate().getPriority()) {
	        			Log.d("xmppService","found two candidates with same priority");
	        			if (initiator.equals(account.getFullJid())) {
	        				if (currentConnection.getCandidate().isOurs()) {
	        					connection = currentConnection;
	        				}
	        			} else {
	        				if (!currentConnection.getCandidate().isOurs()) {
	        					connection = currentConnection;
	        				}
	        			}
	        		}
	        	}
	        }
	        it.remove();
	    }
	    Log.d("xmppService","chose candidate: "+connection.getCandidate().getHost());
		return connection;
	}

	private void sendSuccess() {
		JinglePacket packet = bootstrapPacket("session-terminate");
		Reason reason = new Reason();
		reason.addChild("success");
		packet.setReason(reason);
		Log.d("xmppService","sending success. "+packet.toString());
		this.account.getXmppConnection().sendIqPacket(packet, responseListener);
		this.disconnect();
		this.status = STATUS_FINISHED;
		this.mXmppConnectionService.markMessage(this.message, Message.STATUS_RECIEVED);
	}
	
	private void finish() {
		this.status = STATUS_FINISHED;
		this.mXmppConnectionService.markMessage(this.message, Message.STATUS_SEND);
		this.disconnect();
	}
	
	public void cancel() {
		this.disconnect();
		this.status = STATUS_CANCELED;
		this.mXmppConnectionService.markMessage(this.message, Message.STATUS_SEND_REJECTED);
	}
	
	private void openOurCandidates() {
		for(JingleCandidate candidate : this.candidates) {
			if (candidate.isOurs()) {
				final SocksConnection socksConnection = new SocksConnection(this,candidate);
				connections.put(candidate.getCid(), socksConnection);
				socksConnection.connect(new OnSocksConnection() {
					
					@Override
					public void failed() {
						Log.d("xmppService","connection to our candidate failed");
					}
					
					@Override
					public void established() {
						Log.d("xmppService","connection to our candidate was successful");
					}
				});
			}
		}
	}
	
	private void connectNextCandidate() {
		for(JingleCandidate candidate : this.candidates) {
			if ((!connections.containsKey(candidate.getCid())&&(!candidate.isOurs()))) {
				this.connectWithCandidate(candidate);
				return;
			}
		}
		this.sendCandidateError();
	}
	
	private void connectWithCandidate(final JingleCandidate candidate) {
		final SocksConnection socksConnection = new SocksConnection(this,candidate);
		connections.put(candidate.getCid(), socksConnection);
		socksConnection.connect(new OnSocksConnection() {
			
			@Override
			public void failed() {
				connectNextCandidate();
			}
			
			@Override
			public void established() {
				sendCandidateUsed(candidate.getCid());
				if ((receivedCandidateError)&&(status == STATUS_ACCEPTED)) {
					Log.d("xmppService","received candidate error before. trying to connect");
					connect();
				}
			}
		});
	}

	private void disconnect() {
		Iterator<Entry<String, SocksConnection>> it = this.connections.entrySet().iterator();
	    while (it.hasNext()) {
	        Entry<String, SocksConnection> pairs = it.next();
	        pairs.getValue().disconnect();
	        it.remove();
	    }
	}
	
	private void sendCandidateUsed(final String cid) {
		JinglePacket packet = bootstrapPacket("transport-info");
		Content content = new Content();
		//TODO: put these into actual variables
		content.setAttribute("creator", "initiator");
		content.setAttribute("name", "a-file-offer");
		content.setUsedCandidate(this.transportId, cid);
		packet.setContent(content);
		Log.d("xmppService","send using candidate: "+cid);
		this.account.getXmppConnection().sendIqPacket(packet,responseListener);
	}
	
	private void sendCandidateError() {
		JinglePacket packet = bootstrapPacket("transport-info");
		Content content = new Content();
		//TODO: put these into actual variables
		content.setAttribute("creator", "initiator");
		content.setAttribute("name", "a-file-offer");
		content.setCandidateError(this.transportId);
		packet.setContent(content);
		Log.d("xmppService","send candidate error");
		this.account.getXmppConnection().sendIqPacket(packet,responseListener);
	}

	public String getInitiator() {
		return this.initiator;
	}
	
	public String getResponder() {
		return this.responder;
	}
	
	public int getStatus() {
		return this.status;
	}
	
	private boolean equalCandidateExists(JingleCandidate candidate) {
		for(JingleCandidate c : this.candidates) {
			if (c.equalValues(candidate)) {
				return true;
			}
		}
		return false;
	}
	
	private void mergeCandidate(JingleCandidate candidate) {
		for(JingleCandidate c : this.candidates) {
			if (c.equals(candidate)) {
				return;
			}
		}
		this.candidates.add(candidate);
	}
	
	private void mergeCandidates(List<JingleCandidate> candidates) {
		for(JingleCandidate c : candidates) {
			mergeCandidate(c);
		}
	}
	
	private JingleCandidate getCandidate(String cid) {
		for(JingleCandidate c : this.candidates) {
			if (c.getCid().equals(cid)) {
				return c;
			}
		}
		return null;
	}
}
